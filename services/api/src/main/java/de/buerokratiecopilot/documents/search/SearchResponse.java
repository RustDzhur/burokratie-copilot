package de.buerokratiecopilot.documents.search;

import java.util.List;
import java.util.Map;

/**
 * The answer to a search, including what the platform could not do (spec §10.5, Q-06).
 *
 * <p>The semantic status travels with every response instead of being configured once in a manual: a
 * user who searches semantically and gets nothing must be able to see that the feature is unavailable
 * in this deployment, otherwise they conclude the document is missing.</p>
 */
public record SearchResponse(List<SearchResult> results, long total, int limit, int offset,
                             String searchMode, SemanticStatus semantic) {

    /** Whether semantic search ran, and if not, why. */
    public record SemanticStatus(boolean available, String status, String reason) {

        static SemanticStatus from(SemanticSearchPort port) {
            return new SemanticStatus(port.status() == SemanticSearchPort.Status.AVAILABLE,
                    port.status().name(), port.statusReason());
        }
    }

    public static SearchResponse of(List<SearchResult> results, long total, SearchRequest request,
                                    SemanticSearchPort semantic, String mode) {
        return new SearchResponse(results, total, request.limit(), request.offset(), mode,
                SemanticStatus.from(semantic));
    }

    public Map<String, Object> asDiagnostics() {
        return Map.of("mode", searchMode, "total", total, "semantic", semantic.status());
    }
}
