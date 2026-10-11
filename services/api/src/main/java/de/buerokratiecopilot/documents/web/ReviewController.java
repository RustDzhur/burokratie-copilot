package de.buerokratiecopilot.documents.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.documents.domain.DocumentMatch;
import de.buerokratiecopilot.documents.domain.DocumentReview;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.documents.review.ReviewService;
import de.buerokratiecopilot.shared.context.CurrentUser;

/**
 * Review centre API (spec §13, §20).
 *
 * <p>The queue answers "what needs a person", and each entry says why. Deciding an entry is what
 * releases a document back into the pipeline, which is why the decision endpoint advances the
 * document afterwards: a review that is approved but never resumed would leave the document stuck in
 * exactly the state the reviewer just cleared.</p>
 */
@RestController
@RequestMapping("/api/v1/reviews")
@Tag(name = "Reviews", description = "Documents waiting for a human decision")
public class ReviewController {

    private final ReviewService reviews;
    private final DocumentProcessingPipeline pipeline;

    public ReviewController(ReviewService reviews, DocumentProcessingPipeline pipeline) {
        this.reviews = reviews;
        this.pipeline = pipeline;
    }

    /** One queue entry as the interface shows it. */
    public record QueueEntryResponse(String reviewId, String documentId, String filename, String type,
                                     String reason, String createdAt, String assignedTo) {

        static QueueEntryResponse from(ReviewService.QueueEntry entry) {
            return new QueueEntryResponse(entry.reviewId().toString(), entry.documentId().toString(),
                    entry.filename(), entry.type().name(), entry.reason(),
                    entry.createdAt() == null ? null : entry.createdAt().toString(),
                    entry.assignedTo() == null ? null : entry.assignedTo().toString());
        }
    }

    /** A value with its provenance, so the reviewer can verify it against the document. */
    public record ValueResponse(String fieldKey, String value, String normalizedValue, String valueType,
                                Double confidence, Integer page, String snippet, boolean verified) {

        static ValueResponse from(DocumentExtraction extraction) {
            return new ValueResponse(extraction.getFieldKey(), extraction.getFieldValue(),
                    extraction.getNormalizedValue(), extraction.getValueType(),
                    extraction.getConfidence() == null ? null : extraction.getConfidence().doubleValue(),
                    extraction.getSourcePage(), extraction.getSourceSnippet(), extraction.isVerified());
        }
    }

    /** A proposed link with the reason it was proposed. */
    public record MatchResponse(String id, String matchType, String targetId, double score, String status,
                                String rationale, Object evidence) {

        static MatchResponse from(DocumentMatch match) {
            Object rationale = match.getRationale().getOrDefault("de", match.getRationale());
            return new MatchResponse(match.getId().toString(), match.getMatchType().name(),
                    match.getTargetId().toString(), match.getScore().doubleValue(), match.getStatus().name(),
                    String.valueOf(rationale), match.getEvidence());
        }
    }

    public record ReviewResponse(String id, String type, String status, String reason, String createdAt,
                                 String assignedTo) {

        static ReviewResponse from(DocumentReview review) {
            return new ReviewResponse(review.getId().toString(), review.getReviewType().name(),
                    review.getStatus().name(), review.getReason(),
                    review.getCreatedAt() == null ? null : review.getCreatedAt().toString(),
                    review.getAssignedTo() == null ? null : review.getAssignedTo().toString());
        }
    }

    /** Everything needed to decide about one document. */
    public record DetailResponse(String documentId, String filename, String processingStatus,
                                 String reviewStatus, String documentTypeId, String language,
                                 List<ReviewResponse> openReviews, List<ValueResponse> values,
                                 List<MatchResponse> matches, List<String> missingRequiredFields) {

        static DetailResponse from(ReviewService.Detail detail) {
            Document document = detail.document();
            return new DetailResponse(document.getId().toString(), document.getOriginalFilename(),
                    document.getProcessingStatus().name(), document.getReviewStatus().name(),
                    document.getDocumentTypeId() == null ? null : document.getDocumentTypeId().toString(),
                    document.getLanguage(),
                    detail.openReviews().stream().map(ReviewResponse::from).toList(),
                    detail.values().stream().map(ValueResponse::from).toList(),
                    detail.matches().stream().map(MatchResponse::from).toList(),
                    detail.missingRequiredFields());
        }
    }

    /** The decision a reviewer submits. */
    public record DecisionRequest(String decision, String note, Map<String, Object> changes) {
    }

    @GetMapping
    @PreAuthorize("hasAuthority('documents.review')")
    @Operation(summary = "Documents waiting for a human decision, oldest first")
    public ResponseEntity<List<QueueEntryResponse>> queue() {
        return ResponseEntity.ok(reviews.queue().stream().map(QueueEntryResponse::from).toList());
    }

    @GetMapping("/{documentId}")
    @PreAuthorize("hasAuthority('documents.review')")
    @Operation(summary = "Everything needed to decide about one document, including why it is here")
    public ResponseEntity<DetailResponse> detail(@PathVariable UUID documentId) {
        return ResponseEntity.ok(DetailResponse.from(reviews.detail(documentId)));
    }

    @PostMapping("/{reviewId}/decision")
    @PreAuthorize("hasAuthority('documents.review')")
    @Operation(summary = "Record a reviewer's decision and let the document continue")
    public ResponseEntity<DetailResponse> decide(@PathVariable UUID reviewId,
                                                 @RequestBody DecisionRequest request) {
        DocumentReview.Status decision = parseDecision(request.decision());
        DocumentReview review = reviews.decide(reviewId, decision, request.note(), request.changes(),
                CurrentUser.required());

        // The document continues only once the reviewer has answered. Approving without resuming
        // would leave it in exactly the state that was just cleared.
        if (decision == DocumentReview.Status.APPROVED) {
            pipeline.advance(review.getDocumentId());
        }
        return ResponseEntity.ok(DetailResponse.from(reviews.detail(review.getDocumentId())));
    }

    private static DocumentReview.Status parseDecision(String value) {
        try {
            return DocumentReview.Status.valueOf(String.valueOf(value).strip().toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            // A wrong word in the request is the caller's mistake, not a server failure.
            throw new de.buerokratiecopilot.shared.error.ApiException(
                    de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST,
                    "A decision must be APPROVED, REJECTED or CANCELLED, but was: " + value);
        }
    }
}
