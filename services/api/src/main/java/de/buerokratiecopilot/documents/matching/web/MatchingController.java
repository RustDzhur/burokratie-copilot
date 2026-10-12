package de.buerokratiecopilot.documents.matching.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.documents.domain.DocumentMatch;
import de.buerokratiecopilot.documents.matching.MatchingService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * What a document was matched to, and who confirmed it (spec §13; B-010).
 *
 * <p>{@code MatchingService} was implemented and tested with no endpoint, so the platform could propose a
 * match and nobody could ever agree with it.</p>
 *
 * <p><strong>A suggestion is not a link, and the answer keeps them apart.</strong> {@code suggested} says
 * whether the platform is proposing or whether a person has already decided, because a document filed under
 * a case because somebody confirmed it and one filed because a rule guessed are different facts — and only
 * the first is a statement about the paper.</p>
 *
 * <p><strong>Every proposal carries its score and what it rested on.</strong> A match presented without the
 * reason is one a person can only accept or ignore; the point of the proposal list is that somebody can
 * judge it, and judging needs the grounds.</p>
 *
 * <p><strong>The decider comes from the session and never from the body.</strong> A confirmation that named
 * itself would not be one — the whole purpose of the step is that a person took responsibility for the
 * filing, and a caller who could write that name could write any name.</p>
 *
 * <p>The permissions are the catalogue's own: {@code documents.view} for reading and
 * {@code documents.review} for deciding, checked before being written.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Document matching", description = "What a document was matched to, and by whom")
public class MatchingController {

    private static final String SEE = "hasAnyAuthority('documents.view','documents.review')";
    private static final String REVIEW = "hasAuthority('documents.review')";

    private final MatchingService matching;

    public MatchingController(MatchingService matching) {
        this.matching = matching;
    }

    /** A decision on a proposal. */
    public record DecideBody(String decision) {
    }

    /** The proposals for one document, with what each rested on. */
    @GetMapping("/documents/{documentId}/match-proposals")
    @PreAuthorize(SEE)
    @Operation(summary = "The match proposals for a document, with their scores and grounds")
    public List<Map<String, Object>> proposalsOf(@PathVariable UUID documentId) {
        return matching.proposalsOf(documentId).stream().map(MatchingController::asMap).toList();
    }

    /** Everything waiting for a person, which is the list somebody works through. */
    @GetMapping("/document-matches/pending")
    @PreAuthorize(REVIEW)
    @Operation(summary = "Match proposals waiting for a person")
    public List<Map<String, Object>> pending() {
        return matching.pending().stream().map(MatchingController::asMap).toList();
    }

    /**
     * Confirms or rejects a proposal.
     *
     * <p>The person comes from the session: the step exists so that somebody takes responsibility for the
     * filing, and a name supplied in the body would not be that.</p>
     */
    @PostMapping("/document-matches/{matchId}/decide")
    @PreAuthorize(REVIEW)
    @Operation(summary = "Confirms or rejects a proposal; the person comes from the session")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable UUID matchId,
                                                      @RequestBody DecideBody body) {
        if (body == null || body.decision() == null || body.decision().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es fehlt die Entscheidung");
        }
        DocumentMatch.Status decision;
        try {
            decision = DocumentMatch.Status.valueOf(body.decision().toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Unbekannte Entscheidung: " + body.decision() + ". Möglich sind "
                            + java.util.Arrays.toString(DocumentMatch.Status.values()));
        }

        DocumentMatch decided = matching.decide(matchId, decision, CurrentUser.required());
        Map<String, Object> result = new LinkedHashMap<>(asMap(decided));
        result.put("note", "Die Entscheidung ist vermerkt und einer Person zugeordnet.");
        return ResponseEntity.ok(result);
    }

    /** The decision statuses a proposal can be in, so an interface does not hard-code them. */
    @GetMapping("/document-matches/decisions")
    @PreAuthorize(SEE)
    @Operation(summary = "The decisions a proposal can be given")
    public List<String> decisions() {
        return java.util.Arrays.stream(DocumentMatch.Status.values()).map(Enum::name).toList();
    }

    /** The kinds of match the platform can propose. */
    @GetMapping("/document-matches/types")
    @PreAuthorize(SEE)
    @Operation(summary = "The kinds of match the platform can propose")
    public List<String> types() {
        return java.util.Arrays.stream(DocumentMatch.MatchType.values()).map(Enum::name).toList();
    }

    /** A proposal as it is served: what it is, how sure the platform is, whether a person agreed. */
    private static Map<String, Object> asMap(DocumentMatch match) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("matchId", match.getId() == null ? null : match.getId().toString());
        row.put("documentId", match.getDocumentId() == null ? null : match.getDocumentId().toString());
        row.put("matchType", match.getMatchType() == null ? null : match.getMatchType().name());
        row.put("targetId", match.getTargetId() == null ? null : match.getTargetId().toString());
        row.put("score", match.getScore());
        row.put("status", match.getStatus() == null ? null : match.getStatus().name());
        // The distinction the list exists for: a proposal and a confirmed filing are not the same fact.
        row.put("suggested", match.isSuggested());
        row.put("decidedBy", match.getDecidedBy() == null ? null : match.getDecidedBy().toString());
        row.put("decidedAt", match.getDecidedAt() == null ? null : match.getDecidedAt().toString());
        return row;
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
