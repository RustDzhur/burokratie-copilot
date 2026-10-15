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

    public DocumentResortService(JdbcTemplate jdbc, DocumentRepository documents, DocumentVersionRepository versions,
                                 ClassificationService classification, ReviewService reviews,
                                 PlatformTransactionManager transactionManager) {
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

    private String resortOne(UUID documentId) {
        Document document = documents.findById(documentId).orElseThrow();
        Integer decided = jdbc.queryForObject("""
                select count(*) from document_reviews
                where document_id = ? and review_type = 'CLASSIFICATION' and status = 'APPROVED'
                """, Integer.class, documentId);
        if (decided != null && decided > 0) {
            return "PERSON";
        }
        var version = versions.findFirstByDocumentIdOrderByVersionDesc(documentId).orElse(null);
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
