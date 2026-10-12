package de.buerokratiecopilot.documents.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.DocumentMatchRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentReviewRepository;
import de.buerokratiecopilot.documents.DocumentTypeFieldRepository;
import de.buerokratiecopilot.documents.DocumentTypeRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.documents.domain.DocumentMatch;
import de.buerokratiecopilot.documents.domain.DocumentReview;
import de.buerokratiecopilot.documents.domain.DocumentType;
import de.buerokratiecopilot.documents.domain.DocumentTypeField;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.documents.fields.FieldExtractionService;

/**
 * The document review centre (spec §9.6, §13).
 *
 * <p>What the platform is not entitled to decide alone ends up here, and the queue is organised by the
 * question rather than by the document: a reviewer works through "these types are unclear" or "these
 * amounts could not be read", not through a list of files. Each entry says why it is there, because a
 * queue a person cannot act on is just a backlog.</p>
 *
 * <p>A decision is a person's statement, recorded with who made it. Approving a classification applies
 * the type a reviewer chose; approving a match creates the link. Nothing is applied that the reviewer
 * did not ask for — the review is the authorisation, not a hint.</p>
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final DocumentReviewRepository reviews;
    private final DocumentRepository documents;
    private final DocumentTypeRepository types;
    private final DocumentTypeFieldRepository fieldDefinitions;
    private final DocumentExtractionRepository extractions;
    private final DocumentMatchRepository matches;
    private final FieldExtractionService fieldExtraction;

    public ReviewService(DocumentReviewRepository reviews, DocumentRepository documents,
                         DocumentTypeRepository types, DocumentTypeFieldRepository fieldDefinitions,
                         DocumentExtractionRepository extractions, DocumentMatchRepository matches,
                         FieldExtractionService fieldExtraction) {
        this.reviews = reviews;
        this.documents = documents;
        this.types = types;
        this.fieldDefinitions = fieldDefinitions;
        this.extractions = extractions;
        this.matches = matches;
        this.fieldExtraction = fieldExtraction;
    }

    /** One entry of the queue. */
    public record QueueEntry(UUID reviewId, UUID documentId, String filename,
                             DocumentReview.ReviewType type, String reason, java.time.Instant createdAt,
                             UUID assignedTo) {
    }

    /** What a reviewer needs to decide one document. */
    public record Detail(Document document, List<DocumentReview> openReviews,
                         List<DocumentExtraction> values, List<DocumentMatch> matches,
                         List<String> missingRequiredFields) {
    }

    /**
     * Raises a review, unless one of the same kind is already open.
     *
     * <p>Re-running a stage must not pile up duplicates: the question a person has to answer is the
     * same, and a queue with the same document five times is a queue nobody trusts.</p>
     */
    @Transactional
    public Optional<DocumentReview> raise(Document document, DocumentVersion version,
                                          DocumentReview.ReviewType type, String reason, UUID requestedBy) {
        Optional<DocumentReview> existing = reviews
                .findFirstByDocumentIdAndStatusIn(document.getId(), OPEN_STATUSES);
        if (existing.isPresent()) {
            // The document is already waiting; the new reason is appended rather than replacing the
            // first one, so a reviewer sees everything that needs attention.
            DocumentReview review = existing.get();
            if (review.getReason() != null && !review.getReason().contains(reason)) {
                review.setReason(review.getReason() + " | " + reason);
                reviews.save(review);
            }
            return Optional.of(review);
        }
        DocumentReview review = new DocumentReview(UUID.randomUUID(), document.getTenantId(),
                document.getId(), version == null ? null : version.getId(), type, reason);
        review.setAssignedTo(null);
        DocumentReview saved = reviews.save(review);
        if (document.getReviewStatus() == Document.ReviewStatus.NOT_REQUIRED) {
            document.setReviewStatus(Document.ReviewStatus.PENDING);
            documents.save(document);
        }
        log.info("Document {} needs review: {}", document.getId(), reason);
        return Optional.of(saved);
    }

    private static final List<DocumentReview.Status> OPEN_STATUSES =
            List.of(DocumentReview.Status.PENDING, DocumentReview.Status.IN_REVIEW);

    /** The queue, oldest first: a person works through it in the order documents arrived. */
    @Transactional(readOnly = true)
    public List<QueueEntry> queue() {
        List<QueueEntry> entries = new ArrayList<>();
        for (DocumentReview review : reviews.findByStatusInOrderByCreatedAtAsc(OPEN_STATUSES)) {
            documents.findById(review.getDocumentId()).ifPresent(document -> entries.add(new QueueEntry(
                    review.getId(), document.getId(), document.getOriginalFilename(),
                    review.getReviewType(), review.getReason(), review.getCreatedAt(),
                    review.getAssignedTo())));
        }
        return entries;
    }

    /** Everything a reviewer needs, including why the document is here. */
    @Transactional(readOnly = true)
    public Detail detail(UUID documentId) {
        Document document = documents.findById(documentId).orElseThrow(
                () -> new IllegalArgumentException("Unknown document " + documentId));

        List<DocumentExtraction> values = extractions.findByDocumentIdOrderByFieldKeyAsc(documentId);
        List<String> missing = missingRequiredFields(document, values);

        return new Detail(document, reviews.findByDocumentIdOrderByCreatedAtDesc(documentId).stream()
                .filter(DocumentReview::isOpen).toList(), values,
                matches.findByDocumentIdOrderByScoreDesc(documentId), missing);
    }

    /**
     * Required fields with no value, which is what a completeness review is about.
     *
     * <p>Only fields the definition marks as required are listed: a field a human is meant to fill in
     * is not "missing", it simply is not filled in yet.</p>
     */
    @Transactional(readOnly = true)
    public List<String> missingRequiredFields(Document document, List<DocumentExtraction> values) {
        if (document.getDocumentTypeId() == null) {
            return List.of();
        }
        List<String> present = values.stream().map(DocumentExtraction::getFieldKey).toList();
        return fieldDefinitions.findByDocumentTypeIdOrderBySortOrderAsc(document.getDocumentTypeId())
                .stream()
                .filter(DocumentTypeField::isRequired)
                .map(DocumentTypeField::getFieldKey)
                .filter(key -> !present.contains(key))
                .toList();
    }

    /**
     * Applies a reviewer's decision.
     *
     * <p>What the decision may carry:</p>
     * <ul>
     *   <li>{@code documentTypeCode} — the type the reviewer chose, which also applies when the
     *       platform proposed nothing;</li>
     *   <li>{@code fieldValues} — a map of field key to corrected value. Each is stored as verified,
     *       so a corrected value is distinguishable from an extracted one;</li>
     *   <li>{@code confirmMatches} / {@code rejectMatches} — lists of match identifiers.</li>
     * </ul>
     *
     * <p>Anything not recognised is ignored rather than guessed at, and the decision is recorded
     * whatever it contained.</p>
     */
    @Transactional
    public DocumentReview decide(UUID reviewId, DocumentReview.Status decision, String note,
                                 Map<String, Object> changes, UUID userId) {
        DocumentReview review = reviews.findById(reviewId).orElseThrow(
                () -> new IllegalArgumentException("Unknown review " + reviewId));
        if (!review.isOpen()) {
            throw de.buerokratiecopilot.shared.error.ApiException.conflict("The review has already been decided");
        }

        Document document = documents.findById(review.getDocumentId()).orElseThrow();
        Map<String, Object> applied = new LinkedHashMap<>();

        if (decision == DocumentReview.Status.APPROVED && changes != null) {
            applyTypeChange(document, changes, applied);
            applyFieldChanges(document, review, changes, userId, applied);
            applyMatchDecisions(review, changes, userId, applied);
        }

        if (decision == DocumentReview.Status.APPROVED) {
            // The reviewer answered, so the document leaves the queue. Whether it may continue
            // automatically is decided by the pipeline when it is advanced again.
            document.setReviewStatus(Document.ReviewStatus.APPROVED);
            documents.save(document);
        }
        else if (decision == DocumentReview.Status.REJECTED) {
            document.setReviewStatus(Document.ReviewStatus.REJECTED);
            documents.save(document);
        }

        review.decide(decision, userId, note, applied);
        log.info("Review {} for document {} decided as {}", reviewId, document.getId(), decision);
        return reviews.save(review);
    }

    private void applyTypeChange(Document document, Map<String, Object> changes,
                                 Map<String, Object> applied) {
        Object code = changes.get("documentTypeCode");
        if (code == null) {
            return;
        }
        DocumentType type = types.findByCode(String.valueOf(code)).orElseThrow(
                () -> new IllegalArgumentException("Unknown document type " + code));
        document.setDocumentTypeId(type.getId());
        documents.save(document);
        applied.put("documentTypeCode", type.getCode());
    }

    private void applyFieldChanges(Document document, DocumentReview review, Map<String, Object> changes,
                                   UUID userId, Map<String, Object> applied) {
        Object raw = changes.get("fieldValues");
        if (!(raw instanceof Map<?, ?> values)) {
            return;
        }
        Map<String, String> corrected = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String value = entry.getValue() == null ? null : String.valueOf(entry.getValue());

            DocumentExtraction extraction = extractions
                    .findByDocumentIdAndFieldKey(document.getId(), key)
                    .orElseGet(() -> new DocumentExtraction(UUID.randomUUID(), document.getId(),
                            review.getVersionId(), key, fieldTypeOf(document, key), "MANUAL", "MANUAL"));
            fieldExtraction.confirm(extraction, userId, value);
            corrected.put(key, value == null ? "" : value);
        }
        applied.put("fieldValues", corrected);
    }

    private void applyMatchDecisions(DocumentReview review, Map<String, Object> changes, UUID userId,
                                     Map<String, Object> applied) {
        List<String> confirmed = new ArrayList<>();
        List<String> rejected = new ArrayList<>();

        for (Object id : asList(changes.get("confirmMatches"))) {
            matches.findById(UUID.fromString(String.valueOf(id))).ifPresent(match -> {
                match.decide(DocumentMatch.Status.CONFIRMED, userId);
                matches.save(match);
                confirmed.add(match.getId().toString());
            });
        }
        for (Object id : asList(changes.get("rejectMatches"))) {
            matches.findById(UUID.fromString(String.valueOf(id))).ifPresent(match -> {
                match.decide(DocumentMatch.Status.REJECTED, userId);
                matches.save(match);
                rejected.add(match.getId().toString());
            });
        }
        if (!confirmed.isEmpty()) {
            applied.put("confirmedMatches", confirmed);
        }
        if (!rejected.isEmpty()) {
            applied.put("rejectedMatches", rejected);
        }
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private String fieldTypeOf(Document document, String fieldKey) {
        if (document.getDocumentTypeId() == null) {
            return "TEXT";
        }
        return fieldDefinitions.findByDocumentTypeIdOrderBySortOrderAsc(document.getDocumentTypeId())
                .stream()
                .filter(field -> field.getFieldKey().equals(fieldKey))
                .map(DocumentTypeField::getValueType)
                .findFirst()
                .orElse("TEXT");
    }

    /** A document whose open reviews are all decided may continue. */
    @Transactional(readOnly = true)
    public boolean hasOpenReviews(UUID documentId) {
        return reviews.findFirstByDocumentIdAndStatusIn(documentId, OPEN_STATUSES).isPresent();
    }

    @Transactional(readOnly = true)
    public long openCount() {
        return reviews.countByStatus(DocumentReview.Status.PENDING)
                + reviews.countByStatus(DocumentReview.Status.IN_REVIEW);
    }
}
