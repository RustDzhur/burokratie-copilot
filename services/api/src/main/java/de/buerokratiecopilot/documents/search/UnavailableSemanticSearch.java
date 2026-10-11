package de.buerokratiecopilot.documents.search;

import java.util.List;
import java.util.UUID;

/**
 * The port used when semantic search cannot run.
 *
 * <p>It reports the reason instead of quietly returning nothing: an empty result and an unavailable
 * feature look identical to a user, and only one of them means "your document is not there".</p>
 */
public class UnavailableSemanticSearch implements SemanticSearchPort {

    private final Status status;
    private final String reason;

    public UnavailableSemanticSearch(Status status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    @Override
    public Status status() {
        return status;
    }

    @Override
    public String statusReason() {
        return reason;
    }

    @Override
    public List<Hit> search(UUID tenantId, String query, int limit) {
        return List.of();
    }
}
