package de.buerokratiecopilot.documents.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.documents.ocr.SyntheticPhotos.InkProfile;
import de.buerokratiecopilot.documents.ocr.SyntheticPhotos.Point;

/**
 * Verifies the perspective correction of a photographed page (spec §7.3).
 *
 * <p>The pictures are drawn: a page of text is placed on a dark canvas at known corners, either
 * turned in the plane (affine) or squeezed into a trapezoid (projective), which is what a phone
 * does to it. Two things are measured. The ink's row projection must become sharper — text lines on
 * a few rows instead of a smear — and the number of ink pixels must not fall, which is what catches
 * a crop of the page's text. The engine itself is asked in the tests at the end, which are skipped
 * where Tesseract is not installed.</p>
 *
 * <p>Equally important are the tests that expect <em>no</em> change: a picture without a sheet, a
 * page that already lies straight, a page cut off by the frame, broken bytes. Stretching those
 * would be the harm this class exists to avoid.</p>
 */
class PagePerspectiveTest {

    private static final String[] LINES = {
            "Rechnung Nr. 2026-0815",
            "Rechnungsbetrag 119,00 EUR",
            "Umsatzsteuer 19,00 EUR",
            "Zahlbar bis 20.10.2026",
            "Stadtwerke Musterstadt GmbH",
            "IBAN DE89 3704 0044 0532 0130 00",
            "Kundennummer 4711 Vertragskonto 12345",
            "Vielen Dank fuer Ihr Vertrauen"
    };

    private static final String EXPECTED = String.join(" ", LINES);
    private static final Color DARK = new Color(38, 36, 34);
    private static final Path TESSERACT = Path.of("/opt/homebrew/bin/tesseract");

    @Test
    @DisplayName("a page photographed at an angle is straightened, its ink becomes sharper and no text is cut")
    void straightensAPhotographedPage() {
        var page = SyntheticPhotos.page(1500, 2000, LINES, 46);
        List<Point> corners = List.of(
                new Point(450, 210), new Point(1270, 160),
                new Point(1470, 1900), new Point(120, 1960));
        byte[] photo = SyntheticPhotos.photo(page, corners, 1600, 2100, DARK, "png");

        InkProfile before = SyntheticPhotos.inkProfile(photo, corners);
        PagePerspective.Decision decision = PagePerspective.decide(photo);
        InkProfile after = SyntheticPhotos.inkProfile(decision.bytes(), null);

        assertThat(decision.corrected()).as(decision.summary()).isTrue();
        assertThat(decision.corners()).hasSize(4);
        assertThat(worstCornerError(decision.corners(), corners))
                .as("the corners of the sheet are found where the page was drawn")
                .isLessThan(30.0);
        assertThat(after.sharpness())
                .as("the text is straighter after the correction: %s -> %s", before, after)
                .isGreaterThan(before.sharpness() * 1.25);
        assertThat(after.inkPixels())
                .as("no text was cut off: %s -> %s", before, after)
                .isGreaterThan((int) (before.inkPixels() * 0.9));
        assertThat(decision.summary()).isNotBlank();
    }

    @Test
    @DisplayName("a page lying crooked in the frame is turned straight")
    void deskewsARotatedPage() {
        var page = SyntheticPhotos.page(1500, 2000, LINES, 46);
        byte[] photo = SyntheticPhotos.rotatedPhoto(page, 8.0, 2000, 2400, DARK, "png");
        List<Point> corners = SyntheticPhotos.rotatedCorners(page, 8.0, 2000, 2400);

        InkProfile before = SyntheticPhotos.inkProfile(photo, corners);
        PagePerspective.Decision decision = PagePerspective.decide(photo);
        InkProfile after = SyntheticPhotos.inkProfile(decision.bytes(), null);

        assertThat(decision.corrected()).as(decision.summary()).isTrue();
        assertThat(after.sharpness())
                .as("the text is straighter after the correction: %s -> %s", before, after)
                .isGreaterThan(before.sharpness() * 1.25);
        assertThat(after.inkPixels())
                .as("no text was cut off: %s -> %s", before, after)
                .isGreaterThan((int) (before.inkPixels() * 0.9));
    }

    @Test
    @DisplayName("a page that already lies straight is handed back byte for byte")
    void alreadyStraightPageIsNotResampled() {
        var page = SyntheticPhotos.page(1500, 2000, LINES, 46);
        List<Point> corners = List.of(
                new Point(150, 150), new Point(1650, 150),
                new Point(1650, 2150), new Point(150, 2150));
        byte[] photo = SyntheticPhotos.photo(page, corners, 1800, 2300, DARK, "png");

        PagePerspective.Decision decision = PagePerspective.decide(photo);

        // A perfect rectangle maps onto itself, so the only thing a correction could add is a
        // resampling. The bytes must not change, not even by re-encoding.
        assertThat(decision.corrected()).as(decision.summary()).isFalse();
        assertThat(decision.bytes()).isEqualTo(photo);
    }

    @Test
    @DisplayName("a picture without a sheet is handed back unchanged")
    void pictureWithoutASheetIsLeftAlone() {
        var blank = new java.awt.image.BufferedImage(1400, 1800, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = blank.createGraphics();
        graphics.setColor(new Color(120, 118, 115));
        graphics.fillRect(0, 0, 1400, 1800);
        graphics.dispose();
        byte[] photo = SyntheticPhotos.photo(blank, List.of(
                new Point(0, 0), new Point(1400, 0), new Point(1400, 1800), new Point(0, 1800)),
                1400, 1800, new Color(120, 118, 115), "png");

        PagePerspective.Decision decision = PagePerspective.decide(photo);

        assertThat(decision.corrected()).as(decision.summary()).isFalse();
        assertThat(decision.bytes()).isEqualTo(photo);
    }

    @Test
    @DisplayName("a page whose corners are outside the frame is not guessed at")
    void pageCutOffByTheFrameIsLeftAlone() {
        var page = SyntheticPhotos.page(1500, 2000, LINES, 46);
        byte[] photo = SyntheticPhotos.photo(page, List.of(
                new Point(-120, -80), new Point(1560, -60),
                new Point(1520, 2060), new Point(-90, 2040)),
                1500, 2050, DARK, "png");

        PagePerspective.Decision decision = PagePerspective.decide(photo);

        assertThat(decision.corrected()).as(decision.summary()).isFalse();
        assertThat(decision.bytes()).isEqualTo(photo);
    }

    @Test
    @DisplayName("a picture that fills the frame with a page is not straightened")
    void pageFillingTheWholeFrameIsLeftAlone() {
        var page = SyntheticPhotos.page(1500, 2000, LINES, 46);
        byte[] photo = SyntheticPhotos.photo(page, List.of(
                new Point(0, 0), new Point(1500, 0), new Point(1500, 2000), new Point(0, 2000)),
                1500, 2000, DARK, "png");

        PagePerspective.Decision decision = PagePerspective.decide(photo);

        assertThat(decision.corrected()).as(decision.summary()).isFalse();
        assertThat(decision.bytes()).isEqualTo(photo);
    }

    @Test
    @DisplayName("a bright object that is not a sheet of paper is not straightened")
    void brightBlobThatIsNotASheetIsLeftAlone() {
        // An ellipse covers enough of the frame to pass the area check and its corners are blunt
        // enough to pass the angle check; only the check that its edges really are the sheet's
        // edges — light inside, background outside — can tell it apart from a page.
        var canvas = new java.awt.image.BufferedImage(1400, 1800, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = canvas.createGraphics();
        graphics.setColor(DARK);
        graphics.fillRect(0, 0, 1400, 1800);
        graphics.setColor(new Color(248, 247, 244));
        graphics.fillOval(120, 120, 1160, 1560);
        graphics.dispose();
        byte[] photo = SyntheticPhotos.encodePng(canvas);

        PagePerspective.Decision decision = PagePerspective.decide(photo);

        assertThat(decision.corrected()).as(decision.summary()).isFalse();
        assertThat(decision.bytes()).isEqualTo(photo);
    }

    @Test
    @DisplayName("a tiny picture is left alone")
    void tinyPictureIsLeftAlone() {
        var small = new java.awt.image.BufferedImage(120, 160, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = small.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 120, 160);
        graphics.dispose();
        byte[] photo = SyntheticPhotos.photo(small, List.of(
                new Point(0, 0), new Point(120, 0), new Point(120, 160), new Point(0, 160)),
                120, 160, Color.WHITE, "png");

        PagePerspective.Decision decision = PagePerspective.decide(photo);

        assertThat(decision.outcome()).isEqualTo(PagePerspective.Outcome.TOO_SMALL);
        assertThat(decision.bytes()).isEqualTo(photo);
    }

    @Test
    @DisplayName("broken bytes are handed back without an exception")
    void brokenBytesAreLeftAlone() {
        byte[] broken = { 1, 2, 3, 4 };

        PagePerspective.Decision decision = PagePerspective.decide(broken);

        assertThat(decision.outcome()).isEqualTo(PagePerspective.Outcome.UNREADABLE);
        assertThat(decision.bytes()).isEqualTo(broken);
    }

    @Test
    @DisplayName("the engine reads a page photographed at an angle better after the correction")
    void theEngineReadsAPhotographedPageBetter() {
        requireTesseract();
        var page = SyntheticPhotos.page(1500, 2000, LINES, 46);
        // A phone held close to the sheet: the far edge arrives at about a sixth of the near edge, so
        // the lines at the far end are too small and too squeezed for the engine.
        List<Point> corners = List.of(
                new Point(560, 90), new Point(760, 80),
                new Point(1330, 2330), new Point(70, 2340));
        byte[] photo = SyntheticPhotos.photo(page, corners, 1400, 2400, DARK, "jpg");

        PagePerspective.Decision decision = PagePerspective.decide(photo);
        assertThat(decision.corrected()).as(decision.summary()).isTrue();
        double before = SyntheticPhotos.similarity(EXPECTED, read(photo));
        double after = SyntheticPhotos.similarity(EXPECTED, read(decision.bytes()));

        assertThat(after)
                .as("Tesseract reads a straightened page better: %.2f -> %.2f", before, after)
                .isGreaterThan(before + 0.15);
        assertThat(after).isGreaterThan(0.80);
    }

    @Test
    @DisplayName("the engine reads a crooked page better after the correction")
    void theEngineReadsACrookedPageBetter() {
        requireTesseract();
        var page = SyntheticPhotos.page(1500, 2000, LINES, 46);
        // Twelve degrees is past what any engine tolerates as a baseline; the tiny remaining rotation
        // after the correction is what makes the difference readable.
        byte[] photo = SyntheticPhotos.rotatedPhoto(page, 12.0, 2000, 2400, DARK, "jpg");

        PagePerspective.Decision decision = PagePerspective.decide(photo);
        assertThat(decision.corrected()).as(decision.summary()).isTrue();
        double before = SyntheticPhotos.similarity(EXPECTED, read(photo));
        double after = SyntheticPhotos.similarity(EXPECTED, read(decision.bytes()));

        assertThat(after)
                .as("Tesseract reads a straightened page better: %.2f -> %.2f", before, after)
                .isGreaterThan(before + 0.10);
        assertThat(after).isGreaterThan(0.80);
    }

    private static String read(byte[] image) {
        OcrEngine.Result result = tesseract().recognise(ImagePreparer.prepare(image), List.of("deu", "eng"));
        assertThat(result.recognised()).as("the engine ran: %s", result.error()).isTrue();
        return result.text() == null ? "" : result.text();
    }

    private static TesseractOcrEngine tesseract() {
        return new TesseractOcrEngine(TESSERACT.toString(), Duration.ofMinutes(2));
    }

    private static void requireTesseract() {
        assumeTrue(Files.isExecutable(TESSERACT) && tesseract().isAvailable(),
                "Tesseract is not installed in this environment");
    }

    /** The distance from each true corner to the nearest detected one; order and starting corner do not matter. */
    private static double worstCornerError(List<PagePerspective.Corner> detected, List<Point> expected) {
        double worst = 0;
        for (Point point : expected) {
            double nearest = Double.MAX_VALUE;
            for (PagePerspective.Corner corner : detected) {
                nearest = Math.min(nearest, Math.hypot(corner.x() - point.x(), corner.y() - point.y()));
            }
            worst = Math.max(worst, nearest);
        }
        return worst;
    }
}
