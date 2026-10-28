package de.buerokratiecopilot.documents.search;

import java.util.List;
import java.util.Optional;

/**
 * The provider used when no embedding model is configured.
 *
 * <p>It answers that it is not configured, and produces nothing. Returning zero vectors instead would
 * store meaningless data that later looks like a working index.</p>
 */
public class NoEmbeddingProvider implements EmbeddingProvider {

    @Override
    public Optional<String> model() {
        return Optional.empty();
    }

    @Override
    public Optional<Integer> dimensions() {
        return Optional.empty();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return List.of();
    }
}
