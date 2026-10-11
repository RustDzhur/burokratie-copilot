package de.buerokratiecopilot.documents.search;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Semantic search backed by pgvector (spec §2.1, §10.5).
 *
 * <p>The port reports one of three states, and the distinction matters to whoever operates the
 * platform: vectors cannot be stored at all (pgvector missing), or they could be stored but nothing
 * can produce them yet (no embedding model). Saying "semantic search is unavailable" without saying
 * which of the two is true would leave the operator without a next step.</p>
 *
 * <p>The query plan is deliberately an exact scan: migration V14 explains why no approximate index
 * exists until the embedding dimension is fixed by a configured model.</p>
 */
public class PgVectorSemanticSearch implements SemanticSearchPort {

    private static final Logger log = LoggerFactory.getLogger(PgVectorSemanticSearch.class);

    private final JdbcTemplate jdbc;
    private final EmbeddingProvider embeddings;
    private final boolean vectorStorePresent;

    public PgVectorSemanticSearch(JdbcTemplate jdbc, EmbeddingProvider embeddings,
                                  boolean vectorStorePresent) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.vectorStorePresent = vectorStorePresent;
    }

    @Override
    public Status status() {
        if (!vectorStorePresent) {
            return Status.UNAVAILABLE_NO_VECTOR_STORE;
        }
        if (!embeddings.isConfigured()) {
            return Status.UNAVAILABLE_NO_EMBEDDING_MODEL;
        }
        return Status.AVAILABLE;
    }

    @Override
    public String statusReason() {
        return switch (status()) {
            case AVAILABLE -> "Semantic search is available";
            case UNAVAILABLE_NO_VECTOR_STORE ->
                    "pgvector is not installed in this database, so there is nowhere to store or "
                            + "compare embeddings; full text search is used instead";
            case UNAVAILABLE_NO_EMBEDDING_MODEL ->
                    "No embedding model is configured, so document text cannot be turned into vectors; "
                            + "full text search is used instead";
        };
    }

    @Override
    public List<Hit> search(UUID tenantId, String query, int limit) {
        if (status() != Status.AVAILABLE || query == null || query.isBlank()) {
            return List.of();
        }
        List<float[]> vectors = embeddings.embed(List.of(query));
        if (vectors.isEmpty() || vectors.getFirst().length == 0) {
            log.warn("The embedding provider is configured but produced no vector for the query");
            return List.of();
        }

        String literal = toVectorLiteral(vectors.getFirst());
        // <=> is the cosine distance operator; similarity is its complement.
        return jdbc.query("""
                select document_id, version_id, page_number, chunk_text,
                       1 - (embedding <=> ?::vector) as similarity
                from document_embeddings
                where tenant_id = ? and embedding_model = ?
                order by embedding <=> ?::vector
                limit ?
                """, (rs, rowNum) -> new Hit(rs.getObject("document_id", UUID.class),
                        rs.getObject("version_id", UUID.class),
                        rs.getObject("page_number") == null ? null : rs.getInt("page_number"),
                        rs.getString("chunk_text"), rs.getDouble("similarity")),
                literal, tenantId, embeddings.model().orElseThrow(), literal, limit);
    }

    /** pgvector accepts a vector as a string literal like {@code [0.1,0.2]}. */
    static String toVectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }
}
