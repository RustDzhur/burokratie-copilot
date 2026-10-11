package de.buerokratiecopilot.documents.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that semantic search degrades honestly (spec §10.5, Q-06 acceptance).
 *
 * <p>The acceptance criterion is not that semantic search works — it is that its absence is
 * <em>stated</em>. An unavailable feature that returns an empty list is indistinguishable from a
 * search that genuinely found nothing, and only one of those means the document is missing.</p>
 */
class SemanticSearchFallbackTest {

    private static EmbeddingProvider configuredModel() {
        return new EmbeddingProvider() {
            @Override
            public java.util.Optional<String> model() {
                return java.util.Optional.of("test-model");
            }

            @Override
            public java.util.Optional<Integer> dimensions() {
                return java.util.Optional.of(3);
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return texts.stream().map(text -> new float[] { 0.1f, 0.2f, 0.3f }).toList();
            }
        };
    }

    @Test
    @DisplayName("without pgvector the reason names the vector store, not a generic failure")
    void reportsMissingVectorStore() {
        SemanticSearchPort port = new PgVectorSemanticSearch(new org.springframework.jdbc.core.JdbcTemplate(),
                configuredModel(), false);

        assertThat(port.status()).isEqualTo(SemanticSearchPort.Status.UNAVAILABLE_NO_VECTOR_STORE);
        assertThat(port.statusReason()).contains("pgvector");
        assertThat(port.search(UUID.randomUUID(), "Rechnung", 10)).isEmpty();
    }

    @Test
    @DisplayName("with a vector store but no model the reason names the model")
    void reportsMissingEmbeddingModel() {
        SemanticSearchPort port = new PgVectorSemanticSearch(new org.springframework.jdbc.core.JdbcTemplate(),
                new NoEmbeddingProvider(), true);

        assertThat(port.status()).isEqualTo(SemanticSearchPort.Status.UNAVAILABLE_NO_EMBEDDING_MODEL);
        assertThat(port.statusReason()).contains("embedding model");
    }

    @Test
    @DisplayName("a deployment can state that nothing is configured at all")
    void unavailablePortStatesItsReason() {
        SemanticSearchPort port = new UnavailableSemanticSearch(
                SemanticSearchPort.Status.UNAVAILABLE_NO_VECTOR_STORE,
                "pgvector is not installed in this database");

        assertThat(port.status()).isNotEqualTo(SemanticSearchPort.Status.AVAILABLE);
        assertThat(port.statusReason()).isNotEmpty();
        assertThat(port.search(UUID.randomUUID(), "anything", 5)).isEmpty();
    }

    @Test
    @DisplayName("the provider reports that it is not configured instead of returning zero vectors")
    void noEmbeddingProviderAdmitsItIsNotConfigured() {
        EmbeddingProvider provider = new NoEmbeddingProvider();

        assertThat(provider.isConfigured()).isFalse();
        assertThat(provider.model()).isEmpty();
        assertThat(provider.embed(List.of("Rechnung"))).isEmpty();
    }

    @Test
    @DisplayName("an empty query is not sent to the vector store")
    void emptyQueryReturnsNothingWithoutQuerying() {
        SemanticSearchPort port = new PgVectorSemanticSearch(new org.springframework.jdbc.core.JdbcTemplate(),
                configuredModel(), true);

        assertThat(port.search(UUID.randomUUID(), "  ", 10)).isEmpty();
        assertThat(port.search(UUID.randomUUID(), null, 10)).isEmpty();
    }

    @Test
    @DisplayName("weights are written as the vector literal pgvector expects")
    void buildsVectorLiteral() {
        assertThat(PgVectorSemanticSearch.toVectorLiteral(new float[] { 0.1f, 0.25f })).isEqualTo("[0.1,0.25]");
        assertThat(PgVectorSemanticSearch.toVectorLiteral(new float[] { 1f })).isEqualTo("[1.0]");
    }

    @Test
    @DisplayName("a search request clamps its own limits instead of trusting the caller")
    void searchRequestClampsLimits() {
        assertThat(SearchRequest.of("x").limit()).isEqualTo(SearchRequest.DEFAULT_LIMIT);
        assertThat(new SearchRequest("x", null, null, null, null, null, null, false, 5000, 0).limit())
                .isEqualTo(SearchRequest.MAX_LIMIT);
        assertThat(new SearchRequest("x", null, null, null, null, null, null, false, 10, -5).offset())
                .isZero();
        assertThat(SearchRequest.of("   ").hasTextQuery()).isFalse();
    }
}
