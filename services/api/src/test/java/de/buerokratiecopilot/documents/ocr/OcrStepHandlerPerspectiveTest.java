package de.buerokratiecopilot.documents.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentBlob;
import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.documents.pipeline.ProcessingStepHandler.ProcessingContext;
import de.buerokratiecopilot.documents.pipeline.ProcessingStepHandler.StepResult;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Verifies how the OCR step uses {@link PagePerspective}: a photographed page is straightened
 * before the engine sees it, a rendered PDF page is not touched, and the decision is recorded in
 * the step diagnostics.
 *
 * <p>The engine is a stub that keeps the bytes it was asked to read. Comparing them with what
 * {@code ImagePreparer.prepare} makes of the corrected picture shows the order of the two steps:
 * straightening has to happen on the photograph, before scaling and contrast, not on its prepared
 * copy. The PDF case has to stay untouched — a page rendered from a PDF has no perspective, and
 * warping it would resample text that was already flat.</p>
 */
class OcrStepHandlerPerspectiveTest {

    private static final String[] LINES = {
            "Rechnung Nr. 2026-0815", "Rechnungsbetrag 119,00 EUR", "Umsatzsteuer 19,00 EUR",
            "Zahlbar bis 20.10.2026", "Stadtwerke Musterstadt GmbH", "Vielen Dank fuer Ihr Vertrauen"
    };
    private static final Color DARK = new Color(38, 36, 34);

    @Test
    @DisplayName("a photographed page reaches the engine straightened, and the decision is in the diagnostics")
    void photographedPageIsStraightenedBeforeRecognition() throws Exception {
        byte[] photo = photographedPage();
        CapturingEngine engine = new CapturingEngine();

        StepResult result = handle(engine, photo, "image/jpeg", true);

        assertThat(result.diagnostics())
                .containsEntry("perspectiveCorrectedPages", 1)
                .containsEntry("perspectiveKeptPages", 0);
        assertThat(String.valueOf(result.diagnostics().get("perspectiveDecisions")))
                .contains("page 1: straightened");
        assertThat(engine.images).hasSize(1);
        assertThat(engine.images.get(0))
                .as("the engine was handed the prepared straightened page, not the prepared photograph")
                .isEqualTo(ImagePreparer.prepare(PagePerspective.correct(photo)));
    }

    @Test
    @DisplayName("a page rendered from a PDF is not warped, and no perspective decision is claimed")
    void renderedPdfPageIsNotTouched() throws Exception {
        byte[] photo = photographedPage();
        CapturingEngine engine = new CapturingEngine();

        StepResult result = handle(engine, photo, "application/pdf", true);

        assertThat(result.diagnostics()).doesNotContainKeys(
                "perspectiveCorrectedPages", "perspectiveKeptPages", "perspectiveDecisions");
        assertThat(engine.images).hasSize(1);
        assertThat(engine.images.get(0)).isEqualTo(ImagePreparer.prepare(photo));
    }

    @Test
    @DisplayName("with the switch off a photographed page is read exactly as before")
    void switchOffKeepsTheOldBehaviour() throws Exception {
        byte[] photo = photographedPage();
        CapturingEngine engine = new CapturingEngine();

        StepResult result = handle(engine, photo, "image/jpeg", false);

        assertThat(result.diagnostics()).doesNotContainKeys(
                "perspectiveCorrectedPages", "perspectiveKeptPages", "perspectiveDecisions");
        assertThat(engine.images.get(0)).isEqualTo(ImagePreparer.prepare(photo));
    }

    @Test
    @DisplayName("a picture without a sheet is read unchanged, and the diagnostics say why")
    void pictureWithoutASheetIsReadUnchanged() throws Exception {
        byte[] flat = flatPageFillingTheFrame();
        CapturingEngine engine = new CapturingEngine();

        StepResult result = handle(engine, flat, "image/jpeg", true);

        assertThat(result.diagnostics())
                .containsEntry("perspectiveCorrectedPages", 0)
                .containsEntry("perspectiveKeptPages", 1);
        assertThat(String.valueOf(result.diagnostics().get("perspectiveDecisions")))
                .contains("kept as it is");
        assertThat(engine.images.get(0)).isEqualTo(ImagePreparer.prepare(flat));
    }

    /** The same page photograph {@link PagePerspectiveTest} uses: a clear keystone with a dark frame. */
    private static byte[] photographedPage() {
        BufferedImage page = SyntheticPhotos.page(1500, 2000, LINES, 46);
        return SyntheticPhotos.photo(page, List.of(
                new SyntheticPhotos.Point(450, 210), new SyntheticPhotos.Point(1270, 160),
                new SyntheticPhotos.Point(1470, 1900), new SyntheticPhotos.Point(120, 1960)),
                1600, 2100, DARK, "jpg");
    }

    /** A page that fills the frame: there is no boundary to find and nothing to correct. */
    private static byte[] flatPageFillingTheFrame() {
        BufferedImage page = SyntheticPhotos.page(1500, 2000, LINES, 46);
        return SyntheticPhotos.encodePng(page);
    }

    private static StepResult handle(CapturingEngine engine, byte[] content, String mimeType,
                                     boolean perspectiveCorrection) throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();

        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);
        when(document.getMimeType()).thenReturn(mimeType);

        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getId()).thenReturn(versionId);

        DocumentBlob blob = mock(DocumentBlob.class);
        when(blob.getStorageKey()).thenReturn("documents/photo");

        BlobStore blobStore = mock(BlobStore.class);
        when(blobStore.retrieve(anyString())).thenReturn(new ByteArrayInputStream(content));

        DocumentPage page = new DocumentPage(UUID.randomUUID(), documentId, versionId, 1);
        DocumentPageRepository pages = mock(DocumentPageRepository.class);
        when(pages.findByVersionIdOrderByPageNumberAsc(versionId)).thenReturn(List.of(page));
        when(pages.findByDocumentIdOrderByPageNumberAsc(documentId)).thenReturn(List.of(page));
        when(pages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PdfPageRenderer renderer = mock(PdfPageRenderer.class);
        when(renderer.renderPage(any(), anyInt())).thenReturn(content);

        OcrStepHandler handler = new OcrStepHandler(engine, renderer, pages, mock(DocumentRepository.class),
                blobStore, new BurokratieProperties(), perspectiveCorrection);
        return handler.handle(new ProcessingContext(tenantId, document, version, blob));
    }

    /** An engine that keeps every picture it is asked to read and reports good text for all of them. */
    private static final class CapturingEngine implements OcrEngine {

        private final List<byte[]> images = new ArrayList<>();

        @Override
        public String name() {
            return "capturing";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public List<String> availableLanguages() {
            return List.of("deu", "eng");
        }

        @Override
        public Result recognise(byte[] image, List<String> languages) {
            images.add(image);
            return new Result(String.join(" ", LINES), 0.95, List.of(), "capturing", "deu", null);
        }
    }
}
