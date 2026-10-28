package de.buerokratiecopilot.documents.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentBlob;
import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Extracts the text of a stored document and persists it page by page (spec §9.1 step 5, §9.2).
 *
 * <p>What this service refuses to do is as important as what it does:</p>
 * <ul>
 *   <li>a format without an extractor is reported as {@code unsupported} with the reason — the
 *       document is not silently left without text and it is not pretended to be processed;</li>
 *   <li>a file that is still quarantined is never parsed (spec §21.2: nothing reaches a parser
 *       before the security check);</li>
 *   <li>a failed extraction stores no partial result, so the pipeline records a failure instead of
 *       an incomplete page set that later stages would treat as complete;</li>
 *   <li>the pages of the current version are replaced atomically, so re-running the step after a fix
 *       cannot leave a mixture of old and new text.</li>
 * </ul>
 */
@Service
public class TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TextExtractionService.class);

    /** Upper bound on the text stored per document; the limit is a configuration decision, not magic. */
    private static final int MAX_TEXT_CHARACTERS = 5_000_000;

    private final List<TextExtractor> extractors;
    private final DocumentPageRepository pages;
    private final DocumentRepository documents;
    private final BlobStore blobStore;

    public TextExtractionService(List<TextExtractor> extractors, DocumentPageRepository pages,
                                 DocumentRepository documents, BlobStore blobStore) {
        this.extractors = extractors;
        this.pages = pages;
        this.documents = documents;
        this.blobStore = blobStore;
    }

    /** Outcome of an extraction attempt. */
    public record Result(boolean supported, ExtractedText text, String reason, String format) {

        public boolean needsOcr() {
            return supported && text != null && text.needsOcr();
        }

        public static Result unsupported(String contentType) {
            return new Result(false, null,
                    "No text extractor handles the content type " + contentType
                            + "; the file is kept and can be read, but its text is not available", null);
        }
    }

    /** Content types this deployment can read; used by the API and the diagnostics of the step. */
    public List<String> supportedFormats() {
        return extractors.stream().map(TextExtractor::format).sorted().toList();
    }

    @Transactional
    public Result extract(Document document, DocumentVersion version, DocumentBlob blob) {
        if (!blob.isProcessable()) {
            throw new IllegalStateException(
                    "The stored file is not released for processing (scan status " + blob.getScanStatus() + ")");
        }

        String contentType = document.getMimeType();
        Optional<TextExtractor> extractor = extractors.stream()
                .filter(candidate -> candidate.supports(contentType))
                .findFirst();
        if (extractor.isEmpty()) {
            log.info("No extractor for content type {} (document {})", contentType, document.getId());
            return Result.unsupported(contentType);
        }

        ExtractedText extracted;
        try (InputStream content = blobStore.retrieve(blob.getStorageKey())) {
            extracted = extractor.get().extract(content, blob.getSizeBytes());
        }
        catch (IOException ex) {
            // No partial result is written: the caller records the step as failed and can retry it.
            throw new IllegalStateException("The document text could not be extracted: " + ex.getMessage(), ex);
        }

        store(document, version, extracted);
        return new Result(true, extracted, null, extractor.get().format());
    }

    /** Replaces the pages of this version and updates the aggregated text of the document. */
    private void store(Document document, DocumentVersion version, ExtractedText extracted) {
        pages.deleteByVersionId(version.getId());

        for (ExtractedText.Page page : extracted.pages()) {
            DocumentPage entity = new DocumentPage(java.util.UUID.randomUUID(), document.getId(),
                    version.getId(), page.number());
            String text = page.text() == null ? "" : page.text();
            entity.setTextContent(text.length() > MAX_TEXT_CHARACTERS
                    ? text.substring(0, MAX_TEXT_CHARACTERS)
                    : text);
            entity.setOcrUsed(extracted.method() == ExtractedText.Method.OCR);
            entity.setOcrConfidence(page.confidence() == null
                    ? null
                    : java.math.BigDecimal.valueOf(page.confidence()));
            pages.save(entity);
        }

        String fullText = extracted.fullText();
        document.setExtractedText(fullText.length() > MAX_TEXT_CHARACTERS
                ? fullText.substring(0, MAX_TEXT_CHARACTERS)
                : fullText);
        documents.save(document);
    }

    /** Text of a document as stored, used by the review centre and by search. */
    @Transactional(readOnly = true)
    public List<DocumentPage> pagesOf(java.util.UUID documentId) {
        return pages.findByDocumentIdOrderByPageNumberAsc(documentId);
    }

    /** Diagnostics for the pipeline step. */
    public Map<String, Object> diagnosticsOf(ExtractedText extracted, String format) {
        return Map.of(
                "format", format,
                "method", extracted.method().name(),
                "pages", extracted.pages().size(),
                "emptyPages", extracted.emptyPageCount(),
                "characters", extracted.totalCharacters(),
                "needsOcr", extracted.needsOcr());
    }
}
