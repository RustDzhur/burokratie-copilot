package de.buerokratiecopilot.documents.web;

import java.time.LocalDate;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.documents.search.SearchRequest;
import de.buerokratiecopilot.documents.search.SearchResponse;
import de.buerokratiecopilot.documents.search.SearchService;

/**
 * Document search API (spec §10.5, §20).
 *
 * <p>The response says which mode ran and whether semantic search was available. A user who searched
 * semantically and found nothing must be able to see that this deployment cannot search that way —
 * otherwise they conclude the document is missing.</p>
 */
@RestController
@RequestMapping("/api/v1/documents/search")
@Tag(name = "Search", description = "Full text search over documents and their pages")
public class SearchController {

    private final SearchService search;

    public SearchController(SearchService search) {
        this.search = search;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "Search documents, with filters and page level hits")
    public ResponseEntity<SearchResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID caseId,
            @RequestParam(required = false) String contactEmail,
            @RequestParam(defaultValue = "false") boolean semantic,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        SearchRequest request = new SearchRequest(q, type, status, from, to, caseId, contactEmail,
                semantic, limit, offset);
        return ResponseEntity.ok(search.search(request));
    }
}
