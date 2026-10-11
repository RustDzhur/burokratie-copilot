package de.buerokratiecopilot.documents.search;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What a user is looking for (spec §10.5).
 *
 * <p>Every filter is optional and every one is applied in the database rather than in memory, so a
 * result page never contains a document the caller may not see. Tenant isolation is not a parameter
 * here on purpose: it comes from the request context and from row level security, so it cannot be
 * forgotten by a caller.</p>
 */
public record SearchRequest(String query, String documentTypeCode, String processingStatus,
                            LocalDate receivedFrom, LocalDate receivedTo, UUID caseId, String contactEmail,
                            boolean includeSemantic, int limit, int offset) {

    public static final int DEFAULT_LIMIT = 25;
    public static final int MAX_LIMIT = 100;

    public SearchRequest {
        limit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        offset = Math.max(0, offset);
    }

    public boolean hasTextQuery() {
        return query != null && !query.isBlank();
    }

    public static SearchRequest of(String query) {
        return new SearchRequest(query, null, null, null, null, null, null, false, DEFAULT_LIMIT, 0);
    }
}
