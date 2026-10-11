package de.buerokratiecopilot.documents.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Tesseract adapter (spec §9.2).
 *
 * <p>The TSV parsing is tested with a recorded engine output, so the logic that turns words into
 * blocks with coordinates and confidence is verified even where no engine is installed. The
 * recognition itself runs against a generated image when Tesseract is available, and is skipped
 * otherwise — the platform is required to work without an OCR component.</p>
 */
class TesseractOcrEngineTest {

    private static final Path TESSERACT = Path.of("/opt/homebrew/bin/tesseract");

    /** A verbatim Tesseract TSV excerpt: header plus two words and one layout artefact. */
    private static final String TSV = """
            level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext
            1\t1\t0\t0\t0\t0\t0\t0\t1000\t400\t-1\t
            2\t1\t1\t0\t0\t0\t80\t60\t840\t60\t-1\t
            3\t1\t1\t1\t0\t0\t80\t60\t840\t60\t-1\t
            4\t1\t1\t1\t1\t0\t80\t60\t840\t30\t-1\t
            5\t1\t1\t1\t1\t1\t80\t60\t160\t30\t96.5\tRechnung
            5\t1\t1\t1\t1\t2\t260\t60\t120\t30\t88.0\t2026-001
            4\t1\t1\t1\t2\t0\t80\t120\t400\t30\t-1\t
            5\t1\t1\t1\t2\t1\t80\t120\t200\t30\t92.0\tBrutto
            5\t1\t1\t1\t2\t2\t300\t120\t180\t30\t-1\t119,00
            """;

    private static TesseractOcrEngine engine() {
        return new TesseractOcrEngine(TESSERACT.toString(), Duration.ofMinutes(2));
    }

    private void requireTesseract() {
        Assumptions.assumeTrue(Files.isExecutable(TESSERACT) && engine().isAvailable(),
                "Tesseract is not installed in this environment");
    }

    @Test
    @DisplayName("TSV output becomes text, blocks with coordinates and a mean confidence")
    void parsesTsvOutput() {
        OcrEngine.Result result = TesseractOcrEngine.parseTsv(TSV, "deu");

        assertThat(result.recognised()).isTrue();
        assertThat(result.text()).contains("Rechnung").contains("2026-001").contains("Brutto");
        assertThat(result.blocks()).isNotEmpty();
        assertThat(result.blocks().get(0).left()).isEqualTo(80);
        assertThat(result.blocks().get(0).top()).isEqualTo(60);
        assertThat(result.blocks().get(0).width()).isGreaterThan(0);

        // Only words with a real confidence are averaged; the -1 of "119,00" is an artefact of the
        // engine and mixing it in would drag a good recognition towards zero.
        assertThat(result.confidence()).isNotNull();
        assertThat(result.confidence()).isBetween(0.8, 1.0);
    }

    @Test
    @DisplayName("a page the engine could not read yields no confidence rather than a magic number")
    void emptyTsvYieldsNoConfidence() {
        OcrEngine.Result result = TesseractOcrEngine.parseTsv(
                "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n",
                "deu");

        assertThat(result.recognised()).isTrue();
        assertThat(result.hasText()).isFalse();
        assertThat(result.confidence()).isNull();
        assertThat(result.blocks()).isEmpty();
    }

    @Test
    @DisplayName("confidence is stored as a fraction, not as percent")
    void convertsConfidenceToFraction() {
        assertThat(TesseractOcrEngine.toFraction(96.5)).isEqualTo(0.965);
        assertThat(TesseractOcrEngine.toFraction(0.0)).isEqualTo(0.0);
        assertThat(TesseractOcrEngine.toFraction(null)).isNull();
        assertThat(TesseractOcrEngine.toFraction(150.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("only languages the engine actually has are requested")
    void filtersRequestedLanguages() {
        requireTesseract();
        TesseractOcrEngine engine = engine();

        assertThat(engine.availableLanguages()).contains("deu");
        assertThat(engine.languageSpec(List.of("deu", "eng"))).isEqualTo("deu+eng");
        // An unavailable language must not be requested: Tesseract fails the whole page then.
        assertThat(engine.languageSpec(List.of("xyz", "deu"))).isEqualTo("deu");
        assertThat(engine.languageSpec(List.of("xyz"))).doesNotContain("xyz");
    }

    @Test
    @DisplayName("a rendered page is recognised with its text and confidence")
    void recognisesAGeneratedPage() throws Exception {
        requireTesseract();
        byte[] image = renderText("Rechnung 2026-001 Brutto 119,00 EUR");

        OcrEngine.Result result = engine().recognise(image, List.of("deu", "eng"));

        assertThat(result.recognised()).isTrue();
        assertThat(result.text()).contains("Rechnung");
        assertThat(result.text()).contains("2026");
        assertThat(result.confidence()).isNotNull().isGreaterThan(0.5);
        assertThat(result.engine()).isEqualTo("tesseract");
        assertThat(result.blocks()).isNotEmpty();
    }

    @Test
    @DisplayName("a missing engine is reported as unavailable instead of failing silently")
    void missingEngineIsUnavailable() {
        TesseractOcrEngine broken = new TesseractOcrEngine("/nonexistent/tesseract", Duration.ofSeconds(2));

        assertThat(broken.isAvailable()).isFalse();
        assertThat(broken.recognise(new byte[] { 1, 2, 3 }, List.of("deu")).recognised()).isFalse();
    }

    @Test
    @DisplayName("without a configured engine every page is reported as not examined")
    void noOpEngineAdmitsItDidNothing() {
        OcrEngine noOp = new NoOpOcrEngine();

        assertThat(noOp.isAvailable()).isFalse();
        OcrEngine.Result result = noOp.recognise(new byte[] { 1 }, List.of("deu"));

        assertThat(result.recognised()).isFalse();
        assertThat(result.error()).contains("No OCR engine");
        assertThat(result.text()).isNull();
    }

    /** Draws text on a white background, which is what a scan of a printed page looks like. */
    private static byte[] renderText(String text) throws Exception {
        BufferedImage image = new BufferedImage(1400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setFont(new Font("SansSerif", Font.PLAIN, 48));
        graphics.drawString(text, 40, 120);
        graphics.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
