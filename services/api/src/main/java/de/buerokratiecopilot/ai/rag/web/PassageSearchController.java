package de.buerokratiecopilot.ai.rag.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.ai.rag.RetrievalService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Search across an account's documents, with the passage that matched (spec §19; E-006).
 *
 * <p>Named apart from the search under /api/v1/documents/search, which is the full-text search over an
 * account's documents: this one is the retrieval the answer path rests on, and it reports what it examined.
 *
 * <p>{@code RetrievalService} was implemented and tested with no endpoint, so the passage a claim rests on
 * could not be found by anything a person uses.</p>
 *
 * <p><strong>What was searched is reported alongside what was found.</strong> A search that examined nothing
 * and returned nothing is not the same statement as one that examined fifty pages and matched none, and a
 * caller who cannot tell them apart cannot tell an empty account from a query that was never run. That is why
 * {@code considered} is in the answer rather than being left to be assumed.</p>
 *
 * <p><strong>Every passage carries the document, the page and the sentence.</strong> The point of the route
 * is that a person can check the passage against the letter it came from; a search that returned text without
 * saying where it came from would be asking to be believed.</p>
 *
 * <p><strong>An empty query is refused rather than answered.</strong> A search for nothing is not a search,
 * and an empty query that returned the account's documents would be a way to list everything while appearing
 * to have asked something.</p>
 *
 * <p>The permission is {@code documents.view}: a passage is the text of a document, so searching is reading.
 * The route hands out no text that the reader could not already open, which is the property that makes it
 * safe to put beside the document list.</p>
 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search", description = "Passages matching a query, with where each came from")
public class PassageSearchController {

    private static final String SEE = "hasAnyAuthority('documents.view','documents.review')";
    private static final int MAX_LIMIT = 50;

    private final RetrievalService retrieval;

    public PassageSearchController(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    /**
     * Searches this account's documents.
     *
     * <p>Reports what was examined as well as what matched, because the two together are what the answer
     * means.</p>
     */
    @GetMapping
    @PreAuthorize(SEE)
    @Operation(summary = "Searches the account's documents and reports what was examined")
    public Map<String, Object> search(@RequestParam String query,
                                      @RequestParam(required = false) List<UUID> documentTypeIds,
                                      @RequestParam(required = false) Integer limit) {
        UUID tenantId = tenant();
        // A search for nothing is not a search, and one that answered with the account's documents would be a
        // way to list everything while appearing to have asked something.
        if (query == null || query.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurde nichts gesucht");
        }
        int effectiveLimit = limit == null || limit <= 0 ? 10 : Math.min(limit, MAX_LIMIT);

        RetrievalService.Result result = retrieval.search(tenantId, query, documentTypeIds, effectiveLimit);

        List<Map<String, Object>> passages = result.passages().stream().map(passage -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("documentId", passage.documentId() == null ? null : passage.documentId().toString());
            row.put("documentName", passage.documentName());
            row.put("pageNumber", passage.pageNumber());
            row.put("excerpt", passage.excerpt());
            // The citation as the service writes it, so that a claim and its source are named the same way
            // everywhere rather than in a format invented here.
            row.put("citation", passage.citation());
            return row;
        }).toList();

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("query", query);
        answer.put("passages", passages);
        answer.put("count", passages.size());
        // What was looked at, not only what was found: an empty result from a search that examined nothing is
        // a different statement from an empty result from one that examined fifty pages.
        answer.put("considered", result.considered());
        answer.put("restrictedToDocumentTypes", documentTypeIds == null || documentTypeIds.isEmpty()
                ? "alle" : documentTypeIds.size());
        answer.put("note", passages.isEmpty()
                ? "Es wurde nichts gefunden. Wie viele Stellen geprüft wurden, steht oben."
                : "Die Fundstellen sind mit Dokument, Seite und Satz angegeben, damit sie nachgeprüft "
                        + "werden können.");
        return answer;
    }

    /**
     * Whether a page exists in a document of this account.
     *
     * <p>Offered so that a citation can be checked before it is shown: a passage pointing at a page that does
     * not exist is a citation nobody can follow.</p>
     */
    @GetMapping("/pages/{documentId}/{pageNumber}")
    @PreAuthorize(SEE)
    @Operation(summary = "Whether a page exists in a document of this account")
    public Map<String, Object> pageExists(@PathVariable UUID documentId, @PathVariable Integer pageNumber) {
        UUID tenantId = tenant();
        boolean exists = retrieval.pageExists(tenantId, documentId, pageNumber);
        return Map.of("documentId", documentId.toString(), "pageNumber", pageNumber, "exists", exists,
                "note", exists
                        ? "Die Seite ist vorhanden."
                        : "Zu dieser Seite gibt es keine Fundstelle; eine Angabe darauf wäre nicht "
                                + "nachprüfbar.");
    }

    /** The tenant the caller is acting in, which is never taken from a parameter. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
