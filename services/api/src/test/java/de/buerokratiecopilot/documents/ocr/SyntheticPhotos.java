package de.buerokratiecopilot.documents.ocr;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Draws the photographs {@link PagePerspectiveTest} reasons about and measures them.
 *
 * <p>The pictures are generated rather than read from disk for two reasons: a test with a binary
 * fixture hides what it contains, and the same page can be placed on a canvas at known corners, so
 * the detected corners can be checked against the truth instead of being eyeballed. The generator
 * owns its own homography; the class under test is not used to build its own input.</p>
 *
 * <p>The metric is the sharpness of the ink's row projection: text lines lie on a few rows with
 * white space between them, which produces a high variance of "ink per row". A page photographed
 * at an angle smears every line over many rows, so the variance collapses. A corrected page must
 * raise it again — and the number of ink pixels must not fall, which is what catches a crop.</p>
 */
final class SyntheticPhotos {

    /** Ink is darker than this; paper and the photographed background are lighter or masked out. */
    private static final int INK_LEVEL = 100;

    /** The projection is measured on this many rows and columns, so pictures of different sizes compare. */
    private static final int PROFILE_BUCKETS = 400;

    private SyntheticPhotos() {
    }

    /** A point of a picture, in its pixels. */
    record Point(double x, double y) {
    }

    /** What the ink of a picture says about how straight its text is. */
    record InkProfile(int inkPixels, double rowSharpness, double columnSharpness) {

        /** The better axis: a corrected page may come out turned by 90 degrees, and that is not a defect. */
        double sharpness() {
            return Math.max(rowSharpness, columnSharpness);
        }

        @Override
        public String toString() {
            return "ink=" + inkPixels + " rows=" + Math.round(rowSharpness * 100) / 100.0
                    + " cols=" + Math.round(columnSharpness * 100) / 100.0;
        }
    }

    /** A page of white paper with the given lines in black, as a flat scan of it would look. */
    static BufferedImage page(int width, int height, String[] lines, int fontSize) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(250, 250, 248));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(28, 28, 32));
        graphics.setFont(new Font("SansSerif", Font.PLAIN, fontSize));
        int x = Math.round(width * 0.08f);
        int y = Math.round(height * 0.11f);
        int step = Math.round(height * 0.095f);
        for (String line : lines) {
            graphics.drawString(line, x, y);
            y += step;
        }
        graphics.dispose();
        return image;
    }

    /**
     * The page photographed onto a canvas: its four corners are moved to {@code corners}, which is
     * what a camera at an angle does to a rectangle.
     */
    static byte[] photo(BufferedImage page, List<Point> corners, int canvasWidth, int canvasHeight,
                        Color background, String format) {
        double[] transform = homography(canvasWidth, canvasHeight, corners);
        if (transform == null) {
            throw new IllegalArgumentException("the four corners do not describe a perspective view");
        }
        int pageWidth = page.getWidth();
        int pageHeight = page.getHeight();
        int[] pagePixels = page.getRGB(0, 0, pageWidth, pageHeight, null, 0, pageWidth);
        BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
        int[] canvasPixels = new int[canvasWidth * canvasHeight];
        int backgroundRgb = background.getRGB();
        for (int y = 0; y < canvasHeight; y++) {
            for (int x = 0; x < canvasWidth; x++) {
                double denominator = transform[6] * x + transform[7] * y + 1.0;
                if (Math.abs(denominator) < 1e-9) {
                    canvasPixels[y * canvasWidth + x] = backgroundRgb;
                    continue;
                }
                double sourceX = (transform[0] * x + transform[1] * y + transform[2]) / denominator;
                double sourceY = (transform[3] * x + transform[4] * y + transform[5]) / denominator;
                if (sourceX < 0 || sourceY < 0 || sourceX > pageWidth - 1 || sourceY > pageHeight - 1) {
                    canvasPixels[y * canvasWidth + x] = backgroundRgb;
                }
                else {
                    canvasPixels[y * canvasWidth + x] = bilinear(pagePixels, pageWidth, pageHeight, sourceX, sourceY);
                }
            }
        }
        canvas.setRGB(0, 0, canvasWidth, canvasHeight, canvasPixels, 0, canvasWidth);
        return encode(canvas, format);
    }

    /** The same page turned in the plane by degrees, drawn with an ordinary affine transform. */
    static byte[] rotatedPhoto(BufferedImage page, double degrees, int canvasWidth, int canvasHeight,
                               Color background, String format) {
        BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        graphics.setColor(background);
        graphics.fillRect(0, 0, canvasWidth, canvasHeight);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(page, rotation(page, degrees, canvasWidth, canvasHeight), null);
        graphics.dispose();
        return encode(canvas, format);
    }

    /** Where the page's four corners end up on the canvas after {@link #rotatedPhoto}. */
    static List<Point> rotatedCorners(BufferedImage page, double degrees, int canvasWidth, int canvasHeight) {
        AffineTransform transform = rotation(page, degrees, canvasWidth, canvasHeight);
        List<Point> corners = new ArrayList<>(4);
        for (double[] corner : new double[][] { { 0, 0 }, { page.getWidth(), 0 },
                { page.getWidth(), page.getHeight() }, { 0, page.getHeight() } }) {
            Point2D moved = transform.transform(new Point2D.Double(corner[0], corner[1]), null);
            corners.add(new Point(moved.getX(), moved.getY()));
        }
        return corners;
    }

    private static AffineTransform rotation(BufferedImage page, double degrees, int canvasWidth,
                                            int canvasHeight) {
        AffineTransform transform = new AffineTransform();
        transform.translate(canvasWidth / 2.0, canvasHeight / 2.0);
        transform.rotate(Math.toRadians(degrees));
        transform.translate(-page.getWidth() / 2.0, -page.getHeight() / 2.0);
        return transform;
    }

    /**
     * The ink's row and column profiles. When a page quadrilateral is given, only the ink inside it
     * is counted — the dark background of a photograph is not ink; without it the whole picture is
     * the page, which is true for a straightened one.
     */
    static InkProfile inkProfile(byte[] imageBytes, List<Point> pageQuad) {
        BufferedImage image = decode(imageBytes);
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        int[] rows = new int[PROFILE_BUCKETS];
        int[] columns = new int[PROFILE_BUCKETS];
        int ink = 0;
        double minX = 0;
        double minY = 0;
        double maxX = width - 1;
        double maxY = height - 1;
        if (pageQuad != null) {
            minX = Double.MAX_VALUE;
            minY = Double.MAX_VALUE;
            maxX = -Double.MAX_VALUE;
            maxY = -Double.MAX_VALUE;
            for (Point corner : pageQuad) {
                minX = Math.min(minX, corner.x());
                minY = Math.min(minY, corner.y());
                maxX = Math.max(maxX, corner.x());
                maxY = Math.max(maxY, corner.y());
            }
        }
        double spanX = Math.max(1, maxX - minX);
        double spanY = Math.max(1, maxY - minY);
        for (int y = 0; y < height; y++) {
            if (y < minY || y > maxY) {
                continue;
            }
            for (int x = 0; x < width; x++) {
                if (x < minX || x > maxX) {
                    continue;
                }
                if (pageQuad != null && !inside(x + 0.5, y + 0.5, pageQuad)) {
                    continue;
                }
                int pixel = pixels[y * width + x];
                int luminance = (306 * ((pixel >> 16) & 0xFF) + 601 * ((pixel >> 8) & 0xFF)
                        + 117 * (pixel & 0xFF)) >> 10;
                if (luminance < INK_LEVEL) {
                    ink++;
                    int row = (int) Math.min(PROFILE_BUCKETS - 1, (y - minY) * PROFILE_BUCKETS / spanY);
                    int column = (int) Math.min(PROFILE_BUCKETS - 1, (x - minX) * PROFILE_BUCKETS / spanX);
                    rows[row]++;
                    columns[column]++;
                }
            }
        }
        return new InkProfile(ink, sharpness(rows), sharpness(columns));
    }

    /** Variance over mean squared: lines on a few rows give peaks, a skewed line gives a flat smear. */
    private static double sharpness(int[] profile) {
        double total = 0;
        for (int value : profile) {
            total += value;
        }
        double mean = total / profile.length;
        if (mean <= 0) {
            return 0;
        }
        double variance = 0;
        for (int value : profile) {
            double difference = value - mean;
            variance += difference * difference;
        }
        variance /= profile.length;
        return variance / (mean * mean);
    }

    static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IllegalArgumentException("not an image");
            }
            return image;
        }
        catch (IOException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private static byte[] encode(BufferedImage image, String format) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no image writer for " + format);
            }
            return out.toByteArray();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Maps the canvas rectangle onto the page (destination to source), so every pixel can be sampled. */
    private static double[] homography(int canvasWidth, int canvasHeight, List<Point> corners) {
        double[][] matrix = new double[8][9];
        double[][] destinations = {
                { 0, 0 }, { canvasWidth, 0 }, { canvasWidth, canvasHeight }, { 0, canvasHeight }
        };
        for (int index = 0; index < 4; index++) {
            double u = destinations[index][0];
            double v = destinations[index][1];
            double x = corners.get(index).x();
            double y = corners.get(index).y();
            matrix[2 * index] = new double[] { u, v, 1, 0, 0, 0, -u * x, -v * x, x };
            matrix[2 * index + 1] = new double[] { 0, 0, 0, u, v, 1, -u * y, -v * y, y };
        }
        int size = 8;
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
        int red = channel((topLeft >> 16) & 0xFF, (topRight >> 16) & 0xFF,
                (bottomLeft >> 16) & 0xFF, (bottomRight >> 16) & 0xFF, weightX, weightY);
        int green = channel((topLeft >> 8) & 0xFF, (topRight >> 8) & 0xFF,
                (bottomLeft >> 8) & 0xFF, (bottomRight >> 8) & 0xFF, weightX, weightY);
        int blue = channel(topLeft & 0xFF, topRight & 0xFF, bottomLeft & 0xFF, bottomRight & 0xFF, weightX, weightY);
        return (red << 16) | (green << 8) | blue;
    }

    private static int channel(int topLeft, int topRight, int bottomLeft, int bottomRight,
                               double weightX, double weightY) {
        double top = topLeft + (topRight - topLeft) * weightX;
        double bottom = bottomLeft + (bottomRight - bottomLeft) * weightX;
        return (int) Math.round(top + (bottom - top) * weightY);
    }

    /** Ray casting; the quadrilateral is convex and small, so this is cheap enough per pixel. */
    private static boolean inside(double x, double y, List<Point> polygon) {
        boolean inside = false;
        for (int index = 0, previous = polygon.size() - 1; index < polygon.size(); previous = index++) {
            Point current = polygon.get(index);
            Point before = polygon.get(previous);
            if ((current.y() > y) != (before.y() > y)
                    && x < (before.x() - current.x()) * (y - current.y())
                            / (before.y() - current.y()) + current.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** Normalised Levenshtein similarity; whitespace and punctuation do not decide whether text was read. */
    static double similarity(String expected, String actual) {
        String left = normalise(expected);
        String right = normalise(actual);
        int limit = Math.max(left.length(), right.length());
        if (limit == 0) {
            return 1.0;
        }
        return 1.0 - levenshtein(left, right) / (double) limit;
    }

    private static String normalise(String text) {
        return text == null ? "" : text.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip().replaceAll("\\s+", " ");
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(substitution, Math.min(previous[column] + 1, current[column - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
