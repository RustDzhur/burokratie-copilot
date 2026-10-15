package de.buerokratiecopilot.documents.classification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentVersionRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentReview;
import de.buerokratiecopilot.documents.review.ReviewService;

/**
 * Puts the documents already stored into the drawers the current rules choose.
 *
 * <p>The rules improve; documents filed earlier stay where the old rules put them. This runs the classification again for
 * every document of one tenant and:</p>
 * <ul>
 *   <li>leaves alone a document whose type a person has confirmed or chosen (a person's decision is not undone by a rule),</li>
 *   <li>moves a document to the new type when the rules are now sure,</li>
 *   <li>takes a document out of its drawer and puts it in front of a person when the rules are no longer sure or have no
 *       text to go by, because a wrong drawer is worse than none.</li>
 * </ul>
 * <p>Nothing is deleted. Each document is handled in its own transaction, so one failure never undoes the others.</p>
 */
@Service
public class DocumentResortService {

    private static final Logger log = LoggerFactory.getLogger(DocumentResortService.class);

    private final JdbcTemplate jdbc;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final ClassificationService classification;
    private final ReviewService reviews;
    private final TransactionTemplate transaction;
    private final de.buerokratiecopilot.documents.DocumentBlobRepository blobs;
    private final Map<de.buerokratiecopilot.documents.domain.DocumentProcessingStep.Step,
            de.buerokratiecopilot.documents.pipeline.ProcessingStepHandler> handlers = new java.util.EnumMap<>(
            de.buerokratiecopilot.documents.domain.DocumentProcessingStep.Step.class);

    public DocumentResortService(JdbcTemplate jdbc, DocumentRepository documents, DocumentVersionRepository versions,
                                 ClassificationService classification, ReviewService reviews,
                                 PlatformTransactionManager transactionManager,
                                 de.buerokratiecopilot.documents.DocumentBlobRepository blobs,
                                 List<de.buerokratiecopilot.documents.pipeline.ProcessingStepHandler> stepHandlers) {
        this.blobs = blobs;
        stepHandlers.forEach(handler -> handlers.put(handler.step(), handler));
        this.jdbc = jdbc;
        this.documents = documents;
        this.versions = versions;
        this.classification = classification;
        this.reviews = reviews;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** What a run did, in numbers a person can check against the document list. */
    public Map<String, Object> resort(UUID tenantId) {
        List<UUID> ids = jdbc.queryForList("""
                select id from documents
                where tenant_id = ? and deleted_at is null and duplicate_of_document_id is null
                  and processing_status in ('READY', 'REVIEW_REQUIRED', 'ACTION_PENDING', 'COMPLETED')
                order by received_at desc
                """, UUID.class, tenantId);
        int unchanged = 0;
        int moved = 0;
        int sentToReview = 0;
        int keptByPerson = 0;
        int withoutText = 0;
        int failed = 0;
        for (UUID id : ids) {
            try {
                String result = transaction.execute(status -> resortOne(id));
                switch (result) {
                    case "UNCHANGED" -> unchanged++;
                    case "MOVED" -> moved++;
                    case "REVIEW" -> sentToReview++;
                    case "PERSON" -> keptByPerson++;
                    case "NO_TEXT" -> withoutText++;
                    default -> unchanged++;
                }
            }
            catch (RuntimeException ex) {
                failed++;
                log.warn("Re-sorting document {} failed: {}", id, ex.getMessage());
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("checked", ids.size());
        summary.put("unchanged", unchanged);
        summary.put("moved", moved);
        summary.put("sentToReview", sentToReview);
        summary.put("keptByPerson", keptByPerson);
        summary.put("withoutText", withoutText);
        summary.put("failed", failed);
        log.info("Re-sorted documents of tenant {}: {}", tenantId, summary);
        return summary;
    }

    /** Files one document under a type a person chose, and records that it is their choice. */
    public Document setByPerson(Document document, de.buerokratiecopilot.documents.domain.DocumentType type, UUID userId) {
        return transaction.execute(status -> {
            var version = versions.findFirstByDocumentIdOrderByVersionDesc(document.getId()).orElse(null);
            classification.correctManually(document, version, type, userId, "Von einer Person gewählt");
            jdbc.update("""
                    insert into document_reviews (tenant_id, document_id, version_id, review_type, status, reason, decided_by,
                                                  decision_note, changes, decided_at)
                    values (?, ?, ?, 'CLASSIFICATION', 'APPROVED', 'Typ von einer Person gewählt', ?, 'Typ gewählt',
                            jsonb_build_object('documentTypeCode', ?::text), now())
                    """, document.getTenantId(), document.getId(), version == null ? null : version.getId(), userId,
                    type.getCode());
            // A pending question about the type is answered by this choice.
            jdbc.update("""
                    update document_reviews set status = 'APPROVED', decided_by = ?, decided_at = now(), decision_note = 'Typ gewählt'
                    where document_id = ? and review_type = 'CLASSIFICATION' and status in ('PENDING', 'IN_REVIEW')
                    """, userId, document.getId());
            Document fresh = documents.findById(document.getId()).orElseThrow();
            if (fresh.getReviewStatus() == Document.ReviewStatus.PENDING) {
                fresh.setReviewStatus(Document.ReviewStatus.APPROVED);
                documents.save(fresh);
            }
            return fresh;
        });
    }

    private static boolean isPicture(Document document) {
        return document.getMimeType() != null && document.getMimeType().startsWith("image/");
    }

    /**
     * A picture whose text came out as noise (older versions did not turn a photo lying on its side) is read again, with
     * the current engine and rotation, and its language is determined again from the new text.
     */
    private void readAgain(Document document, de.buerokratiecopilot.documents.domain.DocumentVersion version) {
        var blob = blobs.findById(version.getBlobId()).orElse(null);
        if (blob == null) {
            return;
        }
        jdbc.update("update document_pages set text_content = null where version_id = ?", version.getId());
        var context = new de.buerokratiecopilot.documents.pipeline.ProcessingStepHandler.ProcessingContext(
                document.getTenantId(), document, version, blob);
        for (var step : List.of(de.buerokratiecopilot.documents.domain.DocumentProcessingStep.Step.OCR,
                de.buerokratiecopilot.documents.domain.DocumentProcessingStep.Step.LANGUAGE_DETECT)) {
            var handler = handlers.get(step);
            if (handler != null) {
                handler.handle(context);
            }
        }
        documents.save(document);
    }

    private String resortOne(UUID documentId) {
        Document document = documents.findById(documentId).orElseThrow();
        // Only a type a person chose themselves is theirs. "Confirm" on a suggestion is not a choice: the old screen
        // pre-selected the guess, and a guess confirmed in a click is exactly what ended up in the wrong drawers.
        Integer decided = jdbc.queryForObject("""
                select count(*) from document_reviews
                where document_id = ? and review_type = 'CLASSIFICATION' and status = 'APPROVED'
                  and jsonb_exists(changes, 'documentTypeCode') and not jsonb_exists(changes, 'acceptedSuggestion')
                """, Integer.class, documentId);
        if (decided != null && decided > 0) {
            return "PERSON";
        }
        var version = versions.findFirstByDocumentIdOrderByVersionDesc(documentId).orElse(null);
        if (version != null && isPicture(document) && TextSanity.looksUnreadable(document.getExtractedText())) {
            readAgain(document, version);
        }
        UUID before = document.getDocumentTypeId();
        ClassificationService.Outcome outcome = classification.classify(document, version, null);
        boolean noText = document.getExtractedText() == null || document.getExtractedText().isBlank();

        if (outcome.accepted()) {
            return outcome.typeId().equals(before) ? "UNCHANGED" : "MOVED";
        }
        if (before == null) {
            return noText ? "NO_TEXT" : "UNCHANGED";
        }
        // It had a drawer, and the rules no longer stand behind it.
        document.setDocumentTypeId(null);
        document.setReviewStatus(Document.ReviewStatus.PENDING);
        document.setProcessingStatus(Document.ProcessingStatus.REVIEW_REQUIRED);
        documents.save(document);
        reviews.raise(document, version, DocumentReview.ReviewType.CLASSIFICATION,
                "Neu eingeordnet: " + outcome.rationale(), null);
        return noText ? "NO_TEXT" : "REVIEW";
    }
}
