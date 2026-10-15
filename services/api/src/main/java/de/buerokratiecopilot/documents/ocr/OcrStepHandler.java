package de.buerokratiecopilot.documents.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.pipeline.ProcessingStepHandler;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Recognises the pages that carry no text layer (spec §9.1 step 6, §9.2).
 *
 * <p>Only the pages without text are rendered and recognised — in a digital letter with one scanned
 * annex that is one page instead of fifty. Every page keeps its own engine, language and confidence,
 * because a value taken from a mediocre scan must be distinguishable from one read from a text
 * layer.</p>
 *
 * <p>When no engine is configured the step is reported as skipped with the reason and the document
 * goes to human review. Claiming an empty result would turn an unread scan into a blank document,
 * and the following classification stage would then classify it as "unknown" instead of "not read".</p>
 */
@Component
public class OcrStepHandler implements ProcessingStepHandler {

    private static final Logger log = LoggerFactory.getLogger(OcrStepHandler.class);

    private final OcrEngine engine;
    private final PdfPageRenderer renderer;
    private final DocumentPageRepository pages;
    private final DocumentRepository documents;
    private final BlobStore blobStore;
    private final BurokratieProperties properties;

    public OcrStepHandler(OcrEngine engine, PdfPageRenderer renderer, DocumentPageRepository pages,
                          DocumentRepository documents, BlobStore blobStore,
                          BurokratieProperties properties) {
        this.engine = engine;
        this.renderer = renderer;
        this.pages = pages;
        this.documents = documents;
        this.blobStore = blobStore;
        this.properties = properties;
    }

    @Override
    public DocumentProcessingStep.Step step() {
        return DocumentProcessingStep.Step.OCR;
    }

    @Override
    public StepResult handle(ProcessingContext context) {
        Document document = context.document();
        boolean imageDocument = isImage(document.getMimeType());

        List<DocumentPage> stored = pages.findByVersionIdOrderByPageNumberAsc(context.version().getId());
        List<DocumentPage> needingOcr = stored.stream().filter(DocumentPage::isEmpty).toList();

        if (!imageDocument && needingOcr.isEmpty()) {
            // Nothing to recognise. Checking this BEFORE the engine availability matters: a digital
            // letter must not be sent to human review just because the deployment has no OCR engine.
            return StepResult.succeeded(Map.of("ocrPages", 0, "engine", engine.name(),
                    "reason", "Every page carries a text layer"), Document.ProcessingStatus.EXTRACTING);
        }

        if (!engine.isAvailable()) {
            return StepResult.skipped(
                    "No OCR engine is available in this deployment; " + needingOcr.size()
                            + " page(s) without a text layer were not examined",
                    Document.ProcessingStatus.REVIEW_REQUIRED);
        }

        if (imageDocument && stored.isEmpty()) {
            // An image document has no pages yet; it is recognised as a single page.
            stored = List.of(new DocumentPage(java.util.UUID.randomUUID(), document.getId(),
                    context.version().getId(), 1));
            needingOcr = stored;
        }

        List<String> languages = languages();
        byte[] content;
        try (InputStream in = blobStore.retrieve(context.blob().getStorageKey())) {
            content = in.readAllBytes();
        }
        catch (IOException ex) {
            return StepResult.failed("The stored file could not be read for OCR: " + ex.getMessage());
        }

        int recognised = 0;
        int empty = 0;
        int failed = 0;
        List<Double> confidences = new ArrayList<>();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("engine", engine.name());
        diagnostics.put("languages", String.join("+", languages));

        for (DocumentPage page : needingOcr) {
            byte[] image;
            try {
                image = isImage(document.getMimeType())
                        ? content
                        : renderer.renderPage(content, page.getPageNumber());
            }
            catch (IOException ex) {
                page.setErrorMessage("The page could not be rendered: " + ex.getMessage());
                pages.save(page);
                failed++;
                continue;
            }

            OcrEngine.Result result = recogniseBest(image, languages);
            page.setOcrUsed(true);
            page.setOcrEngine(result.engine());
            page.setOcrLanguage(result.language());

            if (!result.recognised()) {
                page.setErrorMessage(result.error());
                pages.save(page);
                failed++;
                continue;
            }

            page.setTextContent(result.text() == null ? "" : result.text());
            page.setOcrConfidence(result.confidence() == null
                    ? null
                    : java.math.BigDecimal.valueOf(result.confidence()));
            page.setErrorMessage(null);
            pages.save(page);

            if (result.hasText()) {
                recognised++;
                if (result.confidence() != null) {
                    confidences.add(result.confidence());
                }
            }
            else {
                empty++;
            }
        }

        mergeIntoExtractedText(document);
        diagnostics.put("pagesExamined", needingOcr.size());
        diagnostics.put("pagesRecognised", recognised);
        diagnostics.put("pagesWithoutText", empty);
        diagnostics.put("pagesFailed", failed);
        diagnostics.put("averageConfidence", confidences.isEmpty() ? null
                : confidences.stream().mapToDouble(Double::doubleValue).average().orElse(0));

        if (failed > 0 && recognised == 0 && empty == 0) {
            return StepResult.failed("OCR failed on every page that needed it; see the page diagnostics");
        }
        if (recognised == 0 && empty > 0) {
            // The engine ran and found nothing: that is a result, not a failure, but it means the
            // document cannot be processed automatically.
            return StepResult.succeeded(diagnostics, Document.ProcessingStatus.REVIEW_REQUIRED);
        }
        log.info("OCR recognised {} of {} page(s) for document {}", recognised, needingOcr.size(),
                document.getId());
        return StepResult.succeeded(diagnostics, Document.ProcessingStatus.EXTRACTING);
    }

    /** Below this a first reading is not trusted and the page is tried turned. */
    private static final int ENOUGH_WORDS = 15;
    private static final double GOOD_CONFIDENCE = 0.6;

    /**
     * Reads a page after preparing the picture; when that finds little (a photo lying on its side or upside down), the page
     * is also read turned by 90, 180 and 270 degrees and the reading with the most trustworthy words wins.
     */
    OcrEngine.Result recogniseBest(byte[] original, List<String> languages) {
        byte[] prepared = ImagePreparer.prepare(original);
        OcrEngine.Result best = engine.recognise(prepared, languages);
        if (!poor(best)) {
            return best;
        }
        for (int degrees : new int[] { 90, 180, 270 }) {
            OcrEngine.Result candidate = engine.recognise(ImagePreparer.rotated(prepared, degrees), languages);
            if (score(candidate) > score(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean poor(OcrEngine.Result result) {
        return !result.recognised() || words(result) < ENOUGH_WORDS
                || (result.confidence() != null && result.confidence() < GOOD_CONFIDENCE);
    }

    private static int words(OcrEngine.Result result) {
        return result.text() == null || result.text().isBlank() ? 0 : result.text().strip().split("\\s+").length;
    }

    private static double score(OcrEngine.Result result) {
        if (!result.recognised()) {
            return -1;
        }
        return Math.min(words(result), 400) * (result.confidence() == null ? 0.5 : result.confidence());
    }

    /** Rebuilds the aggregated text from all pages of the document. */
    private void mergeIntoExtractedText(Document document) {
        List<DocumentPage> all = pages.findByDocumentIdOrderByPageNumberAsc(document.getId());
        StringBuilder builder = new StringBuilder();
        for (DocumentPage page : all) {
            if (page.isEmpty()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(page.getTextContent().strip());
        }
        document.setExtractedText(builder.toString());
        documents.save(document);
    }

    private List<String> languages() {
        String configured = properties.getOcr().getLanguages();
        if (configured == null || configured.isBlank()) {
            return List.of("deu", "eng");
        }
        return List.of(configured.split("[+,]")).stream().map(String::strip)
                .filter(value -> !value.isEmpty()).toList();
    }

    private static boolean isImage(String contentType) {
        return "image/png".equals(contentType) || "image/jpeg".equals(contentType)
                || "image/tiff".equals(contentType) || "image/webp".equals(contentType)
                || "image/bmp".equals(contentType) || "image/gif".equals(contentType);
    }
}
