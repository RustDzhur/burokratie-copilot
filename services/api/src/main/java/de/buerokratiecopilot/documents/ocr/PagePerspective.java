package de.buerokratiecopilot.documents.ocr;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Finds the sheet of paper in a photograph of a document and removes the perspective (spec §7.3).
 *
 * <p>A phone held over a page rarely looks straight down. The sheet becomes a trapezoid, its lines
 * converge towards one side, and the engine reads worse than it would from the same page flat. This
 * class looks for the four corners of the largest light area, checks them against everything that
 * can be checked without knowing the document, and only then maps the quadrilateral onto a
 * rectangle. A page that already lies straight is left exactly as it came in.</p>
 *
 * <p><strong>The fallback is the point of the class, not an error path.</strong> A wrong correction
 * is worse than none: it resamples a page the engine would have read and can cut text that lies
 * outside the assumed sheet. Every check that fails — no light sheet, corners cut off by the frame,
 * an implausible angle, an edge that is not really the page's edge — returns the original bytes
 * unchanged, so a caller can always use the result and the worst case is what happened before this
 * class existed. The same holds for a picture that cannot be decoded: the engine has decoders of its
 * own and may still read it.</p>
 *
 * <p>Nothing here changes brightness or contrast; that is {@link ImagePreparer}'s job and doing it
 * twice would only make a page grainy. This class concerns itself with geometry alone.</p>
 */
public final class PagePerspective {

    /** The picture is reduced to this long side while the sheet is searched: the geometry needs no more. */
    private static final int ANALYSIS_LONG_SIDE = 600;

    /** A smaller photograph carries too little of the page for its corners to be worth trusting. */
    private static final int MIN_LONG_SIDE = 240;

    /** A sheet that covers less of the frame than this is usually not what was photographed. */
    private static final double MIN_SHEET_FRACTION = 0.40;

    /** Corners sharper or flatter than this are not the corners of a photographed sheet. */
    private static final double MIN_CORNER_ANGLE_DEGREES = 45.0;
    private static final double MAX_CORNER_ANGLE_DEGREES = 135.0;

    /** A4 is 0.71 portrait and 1.41 landscape; the window is wide because a tilted photo foreshortens a side. */
    private static final double MIN_ASPECT = 0.30;
    private static final double MAX_ASPECT = 3.40;

    /** A quadrilateral whose sides meet an axis this closely is a sheet that already lies straight. */
    private static final double STRAIGHT_ANGLE_DEGREES = 1.5;
    private static final double STRAIGHT_SIDE_RATIO = 0.02;

    /** The warp is not worth its resampling below this size, and is capped to what the engine reads. */
    private static final int MIN_OUTPUT_SIDE = 200;
    private static final int MAX_OUTPUT_SIDE = 3000;

    /** How far from a found edge the inside and outside samples are taken, in analysis pixels. */
    private static final double EDGE_PROBE_PIXELS = 3.0;
    private static final double MIN_INSIDE_SUPPORT = 0.80;
    private static final double MIN_OUTSIDE_SUPPORT = 0.60;

    /** Below this share of light pixels the area inside the quadrilateral is not a sheet of paper. */
    private static final double MIN_LIGHT_FRACTION = 0.55;

    /** The outline is simplified to at most this many points before the largest quadrilateral is searched. */
    private static final int MAX_QUADRILATERAL_POINTS = 80;

    private PagePerspective() {
    }

    /** Why a picture was left as it was; {@link Outcome#CORRECTED} is the only outcome that changes the bytes. */
    public enum Outcome {
        CORRECTED,
        ALREADY_STRAIGHT,
        NO_SHEET,
        NOT_CONFIDENT,
        UNREADABLE,
        TOO_SMALL
    }

    /** A point of the sheet's outline, in pixels of the picture the caller handed in. */
    public record Corner(double x, double y) {
    }

    /**
     * The outcome of one attempt. {@code bytes} is a PNG of the straightened sheet, or the very array
     * that was handed in when the picture was left alone.
     */
    public record Decision(byte[] bytes, Outcome outcome, String reason, List<Corner> corners,
                           double sheetFraction) {

        public boolean corrected() {
            return outcome == Outcome.CORRECTED;
        }

        /** One line for the OCR diagnostics: what was decided, and why. */
        public String summary() {
            if (corrected()) {
                return "straightened; the sheet covered " + Math.round(sheetFraction * 100) + "% of the frame";
            }
            return "kept as it is; " + reason;
        }
    }

    /**
     * The straightened sheet as PNG bytes, or the original bytes when the sheet was not found
     * confidently, it already lies straight, or anything else about the picture is doubtful.
     */
    public static byte[] correct(byte[] original) {
        return decide(original).bytes();
    }

    /** The same decision as {@link #correct(byte[])}, with the reason, for the diagnostics. */
    public static Decision decide(byte[] original) {
        if (original == null || original.length == 0) {
            return new Decision(original, Outcome.UNREADABLE, "there are no image bytes to look at", List.of(), 0);
        }
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(original));
        }
        catch (IOException | RuntimeException ex) {
            source = null;
        }
        if (source == null) {
            return new Decision(original, Outcome.UNREADABLE, "the picture could not be decoded", List.of(), 0);
        }
        int longSide = Math.max(source.getWidth(), source.getHeight());
        if (longSide < MIN_LONG_SIDE) {
            return new Decision(original, Outcome.TOO_SMALL,
                    "the picture's long side is only " + longSide + " pixels", List.of(), 0);
        }
        try {
            return straighten(source, original);
        }
        catch (RuntimeException ex) {
            // A picture must never fail a document. Whatever went wrong in the analysis, the engine
            // still gets the bytes it would have got before this class existed.
            return new Decision(original, Outcome.NOT_CONFIDENT,
                    "the picture could not be analysed (" + ex.getClass().getSimpleName() + ")", List.of(), 0);
        }
    }

    private static Decision straighten(BufferedImage source, byte[] original) {
        BufferedImage analysis = analyseImage(source);
        int width = analysis.getWidth();
        int height = analysis.getHeight();
        int[] luminance = luminance(analysis);
        int threshold = otsu(luminance);
        if (threshold < 0) {
            return kept(original, Outcome.NO_SHEET, "no brightness separates a sheet from the background");
        }
        boolean[] mask = new boolean[luminance.length];
        int light = 0;
        for (int index = 0; index < luminance.length; index++) {
            mask[index] = luminance[index] > threshold;
            if (mask[index]) {
                light++;
            }
        }
        if (light < MIN_SHEET_FRACTION * mask.length) {
            return kept(original, Outcome.NO_SHEET,
                    "only " + percent(light / (double) mask.length) + " of the frame is brighter than the background");
        }
        Component component = largestComponent(mask, width, height);
        if (component.area() == 0) {
            return kept(original, Outcome.NO_SHEET, "no connected light area was found");
        }
        if (component.area() < MIN_SHEET_FRACTION * mask.length) {
            return kept(original, Outcome.NO_SHEET, "the largest light area covers only "
                    + percent(component.area() / (double) mask.length) + " of the frame");
        }
        if (component.touchesBorder()) {
            // The page continues beyond the picture; where its fourth corner and its edges are is
            // guesswork, and a guess would cut whatever lies outside the guess.
            return kept(original, Outcome.NOT_CONFIDENT,
                    "the light area is cut off by the frame, so its corners are unknown");
        }
        List<Corner> hull = convexHull(component.pixels(), width);
        if (hull.size() < 4) {
            return kept(original, Outcome.NOT_CONFIDENT, "the light area has no four corners");
        }
        List<Corner> outline = simplify(hull, 0.012 * Math.max(width, height));
        if (outline.size() > MAX_QUADRILATERAL_POINTS) {
            outline = subsample(outline, (int) Math.ceil(outline.size() / (double) MAX_QUADRILATERAL_POINTS));
        }
        Corner[] quad = largestQuadrilateral(outline);
        if (quad == null) {
            return kept(original, Outcome.NOT_CONFIDENT, "no quadrilateral fits the light area");
        }
        quad = clockwiseFromTopLeft(quad);
        double sheetFraction = Math.abs(quadrilateralArea(quad[0], quad[1], quad[2], quad[3]))
                / (double) (width * height);
        String rejection = validate(quad, width, height, luminance, threshold);
        if (rejection != null) {
            return kept(original, Outcome.NOT_CONFIDENT, rejection);
        }
        if (alreadyStraight(quad)) {
            // Mapping a rectangle onto itself would only resample the page. Leaving the bytes alone
            // is the one correction that cannot make a straight photograph worse.
            return new Decision(original, Outcome.ALREADY_STRAIGHT,
                    "the sheet already lies straight in the frame", corners(quad, 1.0), sheetFraction);
        }

        double scale = source.getWidth() / (double) width;
        double sideWidth = Math.max(distance(quad[0], quad[1]), distance(quad[3], quad[2]));
        double sideHeight = Math.max(distance(quad[1], quad[2]), distance(quad[0], quad[3]));
        int destinationWidth = (int) Math.round(sideWidth * scale);
        int destinationHeight = (int) Math.round(sideHeight * scale);
        if (Math.min(destinationWidth, destinationHeight) < MIN_OUTPUT_SIDE) {
            return kept(original, Outcome.NOT_CONFIDENT, "the straightened sheet would be only "
                    + Math.min(destinationWidth, destinationHeight) + " pixels wide");
        }
        double cap = MAX_OUTPUT_SIDE / (double) Math.max(destinationWidth, destinationHeight);
        if (cap < 1.0) {
            destinationWidth = Math.max(1, (int) Math.round(destinationWidth * cap));
            destinationHeight = Math.max(1, (int) Math.round(destinationHeight * cap));
        }
        Corner[] sourceQuad = corners(quad, scale).toArray(new Corner[0]);
        BufferedImage straightened = warp(source, sourceQuad, destinationWidth, destinationHeight);
        if (straightened == null) {
            return kept(original, Outcome.NOT_CONFIDENT, "the perspective could not be computed");
        }
        if (lightFraction(straightened, threshold) < MIN_LIGHT_FRACTION) {
            // The quadrilateral was filled with something that is not paper: better to leave the
            // photograph alone than to hand the engine a picture of a table.
            return kept(original, Outcome.NOT_CONFIDENT, "the area inside the quadrilateral is not a light sheet");
        }
        byte[] png = encodePng(straightened);
        if (png == null) {
            return kept(original, Outcome.NOT_CONFIDENT, "the straightened picture could not be encoded");
        }
        return new Decision(png, Outcome.CORRECTED,
                "the sheet's corners were found over " + percent(sheetFraction) + " of the frame",
                corners(quad, scale), sheetFraction);
    }

    private static Decision kept(byte[] original, Outcome outcome, String reason) {
        return new Decision(original, outcome, reason, List.of(), 0);
    }

    /** The picture reduced to the analysis size; grey, because only the sheet's outline is of interest here. */
    private static BufferedImage analyseImage(BufferedImage source) {
        int longSide = Math.max(source.getWidth(), source.getHeight());
        double scale = Math.min(1.0, ANALYSIS_LONG_SIDE / (double) longSide);
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return image;
    }

    private static int[] luminance(BufferedImage image) {
        int[] values = new int[image.getWidth() * image.getHeight()];
        image.getRaster().getSamples(0, 0, image.getWidth(), image.getHeight(), 0, values);
        return values;
    }

    /** Otsu's threshold: the brightness that best separates the sheet from its background, or -1 when there is none. */
    private static int otsu(int[] luminance) {
        int[] histogram = new int[256];
        for (int value : luminance) {
            histogram[value]++;
        }
        long total = luminance.length;
        long sum = 0;
        for (int level = 0; level < 256; level++) {
            sum += (long) level * histogram[level];
        }
        long backgroundWeight = 0;
        long backgroundSum = 0;
        double best = -1;
        int threshold = -1;
        for (int level = 0; level < 256; level++) {
            backgroundWeight += histogram[level];
            if (backgroundWeight == 0) {
                continue;
            }
            long foregroundWeight = total - backgroundWeight;
            if (foregroundWeight == 0) {
                break;
            }
            backgroundSum += (long) level * histogram[level];
            double backgroundMean = backgroundSum / (double) backgroundWeight;
            double foregroundMean = (sum - backgroundSum) / (double) foregroundWeight;
            double between = (double) backgroundWeight * foregroundWeight
                    * (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean);
            if (between > best) {
                best = between;
                threshold = level;
            }
        }
        return best <= 0 ? -1 : threshold;
    }

    /**
     * The largest connected light area. Everything downstream reasons about one sheet, and taking the
     * largest component is what makes the search ignore lamps, text lines and a second page.
     */
    private static Component largestComponent(boolean[] mask, int width, int height) {
        boolean[] seen = new boolean[mask.length];
        int[] queue = new int[mask.length];
        int[] best = new int[0];
        boolean bestTouchesBorder = false;
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) {
                continue;
            }
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            boolean touchesBorder = false;
            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int y = index / width;
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    touchesBorder = true;
                }
                if (x > 0 && mask[index - 1] && !seen[index - 1]) {
                    seen[index - 1] = true;
                    queue[tail++] = index - 1;
                }
                if (x < width - 1 && mask[index + 1] && !seen[index + 1]) {
                    seen[index + 1] = true;
                    queue[tail++] = index + 1;
                }
                if (y > 0 && mask[index - width] && !seen[index - width]) {
                    seen[index - width] = true;
                    queue[tail++] = index - width;
                }
                if (y < height - 1 && mask[index + width] && !seen[index + width]) {
                    seen[index + width] = true;
                    queue[tail++] = index + width;
                }
            }
            if (tail > best.length) {
                best = Arrays.copyOf(queue, tail);
                bestTouchesBorder = touchesBorder;
            }
        }
        return new Component(best, bestTouchesBorder);
    }

    private record Component(int[] pixels, boolean touchesBorder) {
        int area() {
            return pixels.length;
        }
    }

    /** Andrew's monotone chain: the outer boundary of the component, without the hollows made by its text. */
    private static List<Corner> convexHull(int[] pixels, int width) {
        long[] keys = new long[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            int x = pixels[index] % width;
            int y = pixels[index] / width;
            keys[index] = ((long) x << 32) | (y & 0xFFFFFFFFL);
        }
        Arrays.sort(keys);
        List<Corner> points = new ArrayList<>(keys.length);
        long previous = Long.MIN_VALUE;
        for (long key : keys) {
            if (key == previous) {
                continue;
            }
            previous = key;
            points.add(new Corner((int) (key >>> 32), (int) key));
        }
        if (points.size() < 3) {
            return points;
        }
        int count = points.size();
        Corner[] lower = new Corner[count * 2];
        int lowerSize = 0;
        for (Corner point : points) {
            while (lowerSize >= 2 && cross(lower[lowerSize - 2], lower[lowerSize - 1], point) <= 0) {
                lowerSize--;
            }
            lower[lowerSize++] = point;
        }
        Corner[] upper = new Corner[count * 2];
        int upperSize = 0;
        for (int index = count - 1; index >= 0; index--) {
            Corner point = points.get(index);
            while (upperSize >= 2 && cross(upper[upperSize - 2], upper[upperSize - 1], point) <= 0) {
                upperSize--;
            }
            upper[upperSize++] = point;
        }
        List<Corner> hull = new ArrayList<>(lowerSize + upperSize - 2);
        for (int index = 0; index < lowerSize - 1; index++) {
            hull.add(lower[index]);
        }
        for (int index = 0; index < upperSize - 1; index++) {
            hull.add(upper[index]);
        }
        return hull;
    }

    private static double cross(Corner origin, Corner first, Corner second) {
        return (first.x() - origin.x()) * (second.y() - origin.y())
                - (first.y() - origin.y()) * (second.x() - origin.x());
    }

    /** Douglas-Peucker on a closed outline: keeps the corners and drops the stair-steps of the mask boundary. */
    private static List<Corner> simplify(List<Corner> polygon, double tolerance) {
        int count = polygon.size();
        if (count <= 4) {
            return polygon;
        }
        int far = 0;
        double farthest = -1;
        for (int index = 1; index < count; index++) {
            double distance = squaredDistance(polygon.get(0), polygon.get(index));
            if (distance > farthest) {
                farthest = distance;
                far = index;
            }
        }
        List<Corner> first = new ArrayList<>(polygon.subList(0, far + 1));
        List<Corner> second = new ArrayList<>(polygon.subList(far, count));
        second.add(polygon.get(0));
        List<Corner> simplified = new ArrayList<>(douglasPeucker(first, tolerance));
        simplified.remove(simplified.size() - 1);
        List<Corner> tail = douglasPeucker(second, tolerance);
        tail.remove(tail.size() - 1);
        simplified.addAll(tail);
        return simplified;
    }

    private static List<Corner> douglasPeucker(List<Corner> points, double tolerance) {
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        douglasPeucker(points, 0, points.size() - 1, tolerance, keep);
        List<Corner> result = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            if (keep[index]) {
                result.add(points.get(index));
            }
        }
        return result;
    }

    private static void douglasPeucker(List<Corner> points, int from, int to, double tolerance, boolean[] keep) {
        if (to <= from + 1) {
            return;
        }
        Corner start = points.get(from);
        Corner end = points.get(to);
        double farthest = -1;
        int at = -1;
        for (int index = from + 1; index < to; index++) {
            double distance = distanceToLine(points.get(index), start, end);
            if (distance > farthest) {
                farthest = distance;
                at = index;
            }
        }
        if (farthest > tolerance) {
            keep[at] = true;
            douglasPeucker(points, from, at, tolerance, keep);
            douglasPeucker(points, at, to, tolerance, keep);
        }
    }

    private static List<Corner> subsample(List<Corner> polygon, int step) {
        List<Corner> result = new ArrayList<>(polygon.size() / Math.max(1, step) + 4);
        for (int index = 0; index < polygon.size(); index += Math.max(1, step)) {
            result.add(polygon.get(index));
        }
        return result.size() >= 4 ? result : polygon;
    }

    /**
     * The convex quadrilateral of largest area among the outline's points. A photograph makes the
     * sheet a convex quadrilateral, and the mask boundary carries noise at the corners; picking the
     * four points that enclose the most area is what recovers the corners instead of an arbitrary
     * four of the boundary's pixels.
     */
    private static Corner[] largestQuadrilateral(List<Corner> outline) {
        int count = outline.size();
        if (count < 4) {
            return null;
        }
        double best = -1;
        Corner[] bestQuad = null;
        for (int first = 0; first < count - 3; first++) {
            Corner a = outline.get(first);
            for (int second = first + 1; second < count - 2; second++) {
                Corner b = outline.get(second);
                for (int third = second + 1; third < count - 1; third++) {
                    Corner c = outline.get(third);
                    for (int fourth = third + 1; fourth < count; fourth++) {
                        Corner d = outline.get(fourth);
                        double area = Math.abs(quadrilateralArea(a, b, c, d));
                        if (area > best) {
                            best = area;
                            bestQuad = new Corner[] { a, b, c, d };
                        }
                    }
                }
            }
        }
        return bestQuad;
    }

    /** Orders the corners clockwise (as the picture shows them) and starts at the one nearest the top left. */
    private static Corner[] clockwiseFromTopLeft(Corner[] quad) {
        double centerX = 0;
        double centerY = 0;
        for (Corner corner : quad) {
            centerX += corner.x();
            centerY += corner.y();
        }
        final double centerOfX = centerX / quad.length;
        final double centerOfY = centerY / quad.length;
        List<Corner> points = new ArrayList<>(List.of(quad));
        points.sort(Comparator.comparingDouble(corner ->
                Math.atan2(corner.y() - centerOfY, corner.x() - centerOfX)));
        Corner[] ordered = points.toArray(new Corner[0]);
        if (signedArea(ordered) < 0) {
            ordered = new Corner[] { ordered[0], ordered[3], ordered[2], ordered[1] };
        }
        int start = 0;
        double smallest = Double.MAX_VALUE;
        for (int index = 0; index < 4; index++) {
            double sum = ordered[index].x() + ordered[index].y();
            if (sum < smallest) {
                smallest = sum;
                start = index;
            }
        }
        Corner[] result = new Corner[4];
        for (int index = 0; index < 4; index++) {
            result[index] = ordered[(start + index) % 4];
        }
        return result;
    }

    /** The checks that decide whether the quadrilateral is a sheet of paper rather than a bright object. */
    private static String validate(Corner[] quad, int width, int height, int[] luminance, int threshold) {
        double area = Math.abs(quadrilateralArea(quad[0], quad[1], quad[2], quad[3]));
        double fraction = area / (double) (width * height);
        if (fraction < MIN_SHEET_FRACTION) {
            return "the quadrilateral covers only " + percent(fraction) + " of the frame";
        }
        for (int index = 0; index < 4; index++) {
            double angle = cornerAngle(quad[(index + 3) % 4], quad[index], quad[(index + 1) % 4]);
            if (angle < MIN_CORNER_ANGLE_DEGREES || angle > MAX_CORNER_ANGLE_DEGREES) {
                return "a corner of the quadrilateral is " + Math.round(angle)
                        + " degrees, which a photographed sheet does not have";
            }
        }
        double sideWidth = Math.max(distance(quad[0], quad[1]), distance(quad[3], quad[2]));
        double sideHeight = Math.max(distance(quad[1], quad[2]), distance(quad[0], quad[3]));
        if (sideHeight < 1e-6) {
            return "the quadrilateral has no height";
        }
        double aspect = sideWidth / sideHeight;
        if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) {
            return "the quadrilateral's sides have the ratio " + Math.round(aspect * 100) / 100.0
                    + ", which no sheet of paper has";
        }
        double[] support = edgeSupport(quad, luminance, width, height, threshold);
        if (support[0] < MIN_INSIDE_SUPPORT) {
            return "the quadrilateral is not filled by a light sheet";
        }
        if (support[1] < MIN_OUTSIDE_SUPPORT) {
            return "the quadrilateral's edges are not the sheet's edges";
        }
        return null;
    }

    /** The share of the two sides of a found edge that carries the expectation: light inside, background outside. */
    private static double[] edgeSupport(Corner[] quad, int[] luminance, int width, int height, int threshold) {
        double centerX = 0;
        double centerY = 0;
        for (Corner corner : quad) {
            centerX += corner.x();
            centerY += corner.y();
        }
        centerX /= quad.length;
        centerY /= quad.length;
        int samples = 48;
        int inside = 0;
        int outside = 0;
        int total = 0;
        for (int edge = 0; edge < 4; edge++) {
            Corner from = quad[edge];
            Corner to = quad[(edge + 1) % 4];
            for (int sample = 1; sample <= samples; sample++) {
                double t = sample / (double) (samples + 1);
                double x = from.x() + (to.x() - from.x()) * t;
                double y = from.y() + (to.y() - from.y()) * t;
                double dx = x - centerX;
                double dy = y - centerY;
                double length = Math.hypot(dx, dy);
                if (length < 1e-6) {
                    continue;
                }
                double outwardX = dx / length;
                double outwardY = dy / length;
                int inner = sample(luminance, width, height,
                        x - outwardX * EDGE_PROBE_PIXELS, y - outwardY * EDGE_PROBE_PIXELS);
                int outer = sample(luminance, width, height,
                        x + outwardX * EDGE_PROBE_PIXELS, y + outwardY * EDGE_PROBE_PIXELS);
                if (inner >= 0 && inner >= threshold - 12) {
                    inside++;
                }
                if (outer >= 0 && outer <= threshold) {
                    outside++;
                }
                total++;
            }
        }
        if (total == 0) {
            return new double[] { 0, 0 };
        }
        return new double[] { inside / (double) total, outside / (double) total };
    }

    private static int sample(int[] luminance, int width, int height, double x, double y) {
        int column = (int) Math.round(x);
        int row = (int) Math.round(y);
        if (column < 0 || row < 0 || column >= width || row >= height) {
            return -1;
        }
        return luminance[row * width + column];
    }

    /** True when the quadrilateral is an axis-aligned rectangle, so mapping it onto a rectangle is a no-op. */
    private static boolean alreadyStraight(Corner[] quad) {
        double deviation = 0;
        for (int index = 0; index < 4; index++) {
            Corner from = quad[index];
            Corner to = quad[(index + 1) % 4];
            double angle = Math.toDegrees(Math.atan2(to.y() - from.y(), to.x() - from.x()));
            double toAxis = Math.abs(angle % 90);
            deviation = Math.max(deviation, Math.min(toAxis, 90 - toAxis));
        }
        double widthTop = distance(quad[0], quad[1]);
        double widthBottom = distance(quad[3], quad[2]);
        double heightLeft = distance(quad[0], quad[3]);
        double heightRight = distance(quad[1], quad[2]);
        if (widthTop < 1e-6 || widthBottom < 1e-6 || heightLeft < 1e-6 || heightRight < 1e-6) {
            return false;
        }
        double widthRatio = Math.abs(widthTop / widthBottom - 1);
        double heightRatio = Math.abs(heightLeft / heightRight - 1);
        return deviation <= STRAIGHT_ANGLE_DEGREES
                && widthRatio <= STRAIGHT_SIDE_RATIO
                && heightRatio <= STRAIGHT_SIDE_RATIO;
    }

    private static double cornerAngle(Corner previous, Corner corner, Corner next) {
        double ax = previous.x() - corner.x();
        double ay = previous.y() - corner.y();
        double bx = next.x() - corner.x();
        double by = next.y() - corner.y();
        double lengths = Math.hypot(ax, ay) * Math.hypot(bx, by);
        if (lengths < 1e-9) {
            return 0;
        }
        double cosine = (ax * bx + ay * by) / lengths;
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosine))));
    }

    /**
     * Maps the rectangle onto the quadrilateral (destination to source). Sampling the destination
     * needs exactly this direction; the inverse would have to be computed and is not needed.
     */
    private static double[] homography(int destinationWidth, int destinationHeight, Corner[] quad) {
        double[][] matrix = new double[8][9];
        double[][] destinations = {
                { 0, 0 }, { destinationWidth, 0 },
                { destinationWidth, destinationHeight }, { 0, destinationHeight }
        };
        for (int index = 0; index < 4; index++) {
            double u = destinations[index][0];
            double v = destinations[index][1];
            double x = quad[index].x();
            double y = quad[index].y();
            matrix[2 * index] = new double[] { u, v, 1, 0, 0, 0, -u * x, -v * x, x };
            matrix[2 * index + 1] = new double[] { 0, 0, 0, u, v, 1, -u * y, -v * y, y };
        }
        double[] solution = solve(matrix);
        if (solution == null) {
            return null;
        }
        // A negative denominator means the projective map folds over inside the rectangle; such a
        // solution would smear the page and is refused.
        double[][] probes = {
                { 0, 0 }, { destinationWidth, 0 },
                { destinationWidth, destinationHeight }, { 0, destinationHeight },
                { destinationWidth / 2.0, destinationHeight / 2.0 }
        };
        for (double[] probe : probes) {
            double denominator = solution[6] * probe[0] + solution[7] * probe[1] + 1.0;
            if (denominator <= 1e-6) {
                return null;
            }
        }
        return solution;
    }

    /** Gauss-Jordan with partial pivoting; {@code null} when the four points do not determine a homography. */
    private static double[] solve(double[][] matrix) {
        int size = matrix.length;
        for (int column = 0; column < size; column++) {
            int pivot = column;
            for (int row = column + 1; row < size; row++) {
                if (Math.abs(matrix[row][column]) > Math.abs(matrix[pivot][column])) {
                    pivot = row;
                }
            }
            if (Math.abs(matrix[pivot][column]) < 1e-12) {
                return null;
            }
            double[] swap = matrix[column];
            matrix[column] = matrix[pivot];
            matrix[pivot] = swap;
            double divisor = matrix[column][column];
            for (int entry = column; entry <= size; entry++) {
                matrix[column][entry] /= divisor;
            }
            for (int row = 0; row < size; row++) {
                if (row == column) {
                    continue;
                }
                double factor = matrix[row][column];
                if (factor == 0) {
                    continue;
                }
                for (int entry = column; entry <= size; entry++) {
                    matrix[row][entry] -= factor * matrix[column][entry];
                }
            }
        }
        double[] solution = new double[size];
        for (int index = 0; index < size; index++) {
            solution[index] = matrix[index][size];
        }
        return solution;
    }

    /** Bilinear resampling of the quadrilateral into a rectangle; {@code null} when the map is unusable. */
    private static BufferedImage warp(BufferedImage source, Corner[] quad, int destinationWidth,
                                      int destinationHeight) {
        double[] transform = homography(destinationWidth, destinationHeight, quad);
        if (transform == null) {
            return null;
        }
        int sourceWidth = source.getWidth();
        int sourceHeight = source.getHeight();
        int[] pixels = source.getRGB(0, 0, sourceWidth, sourceHeight, null, 0, sourceWidth);
        int[] result = new int[destinationWidth * destinationHeight];
        for (int y = 0; y < destinationHeight; y++) {
            int row = y * destinationWidth;
            for (int x = 0; x < destinationWidth; x++) {
                double denominator = transform[6] * x + transform[7] * y + 1.0;
                if (Math.abs(denominator) < 1e-9) {
                    result[row + x] = 0xFFFFFFFF;
                    continue;
                }
                double sourceX = (transform[0] * x + transform[1] * y + transform[2]) / denominator;
                double sourceY = (transform[3] * x + transform[4] * y + transform[5]) / denominator;
                result[row + x] = bilinear(pixels, sourceWidth, sourceHeight, sourceX, sourceY);
            }
        }
        BufferedImage image = new BufferedImage(destinationWidth, destinationHeight, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, destinationWidth, destinationHeight, result, 0, destinationWidth);
        return image;
    }

    private static int bilinear(int[] pixels, int width, int height, double x, double y) {
        double column = Math.max(0, Math.min(width - 1.0, x));
        double row = Math.max(0, Math.min(height - 1.0, y));
        int left = (int) Math.floor(column);
        int top = (int) Math.floor(row);
        int right = Math.min(left + 1, width - 1);
        int bottom = Math.min(top + 1, height - 1);
        double weightX = column - left;
        double weightY = row - top;
        int topLeft = pixels[top * width + left];
        int topRight = pixels[top * width + right];
        int bottomLeft = pixels[bottom * width + left];
        int bottomRight = pixels[bottom * width + right];
        int red = interpolate((topLeft >> 16) & 0xFF, (topRight >> 16) & 0xFF,
                (bottomLeft >> 16) & 0xFF, (bottomRight >> 16) & 0xFF, weightX, weightY);
        int green = interpolate((topLeft >> 8) & 0xFF, (topRight >> 8) & 0xFF,
                (bottomLeft >> 8) & 0xFF, (bottomRight >> 8) & 0xFF, weightX, weightY);
        int blue = interpolate(topLeft & 0xFF, topRight & 0xFF,
                bottomLeft & 0xFF, bottomRight & 0xFF, weightX, weightY);
        return (red << 16) | (green << 8) | blue;
    }

    private static int interpolate(int topLeft, int topRight, int bottomLeft, int bottomRight,
                                   double weightX, double weightY) {
        double top = topLeft + (topRight - topLeft) * weightX;
        double bottom = bottomLeft + (bottomRight - bottomLeft) * weightX;
        return (int) Math.round(top + (bottom - top) * weightY);
    }

    /** The share of the straightened picture that is light, sampled rather than walked pixel by pixel. */
    private static double lightFraction(BufferedImage image, int threshold) {
        int light = 0;
        int total = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                int pixel = image.getRGB(x, y);
                int value = (306 * ((pixel >> 16) & 0xFF) + 601 * ((pixel >> 8) & 0xFF)
                        + 117 * (pixel & 0xFF)) >> 10;
                if (value > threshold) {
                    light++;
                }
                total++;
            }
        }
        return total == 0 ? 0 : light / (double) total;
    }

    private static byte[] encodePng(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            return null;
        }
    }

    private static List<Corner> corners(Corner[] quad, double scale) {
        List<Corner> result = new ArrayList<>(4);
        for (Corner corner : quad) {
            result.add(new Corner(corner.x() * scale, corner.y() * scale));
        }
        return List.copyOf(result);
    }

    private static double quadrilateralArea(Corner a, Corner b, Corner c, Corner d) {
        return 0.5 * (a.x() * b.y() - b.x() * a.y()
                + b.x() * c.y() - c.x() * b.y()
                + c.x() * d.y() - d.x() * c.y()
                + d.x() * a.y() - a.x() * d.y());
    }

    private static double signedArea(Corner[] points) {
        double sum = 0;
        for (int index = 0; index < points.length; index++) {
            Corner current = points[index];
            Corner next = points[(index + 1) % points.length];
            sum += current.x() * next.y() - next.x() * current.y();
        }
        return sum / 2;
    }

    private static double distance(Corner first, Corner second) {
        return Math.hypot(first.x() - second.x(), first.y() - second.y());
    }

    private static double squaredDistance(Corner first, Corner second) {
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        return dx * dx + dy * dy;
    }

    private static double distanceToLine(Corner point, Corner start, Corner end) {
        double dx = end.x() - start.x();
        double dy = end.y() - start.y();
        double length = Math.hypot(dx, dy);
        if (length < 1e-9) {
            return Math.hypot(point.x() - start.x(), point.y() - start.y());
        }
        return Math.abs((point.x() - start.x()) * dy - (point.y() - start.y()) * dx) / length;
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }
}
