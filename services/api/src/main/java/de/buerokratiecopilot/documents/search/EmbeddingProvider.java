package de.buerokratiecopilot.documents.search;

import java.util.List;
import java.util.Optional;

/**
 * Produces embeddings for text (spec §10.5).
 *
 * <p>An interface with an explicit "not configured" answer, because the platform must state whether
 * semantic search is possible before a user relies on it. The implementation backed by a model lives
 * in the AI layer (task E-005); until one is configured, this returns empty and the search reports
 * the reason.</p>
 */
public interface EmbeddingProvider {

    /** Name recorded with every vector, so a model change can be detected. */
    Optional<String> model();

    /** Number of dimensions the model produces, when one is configured. */
    Optional<Integer> dimensions();

    /** Embeddings for the given texts, or empty when no model is configured. */
    List<float[]> embed(List<String> texts);

    default boolean isConfigured() {
        return model().isPresent();
    }
}
