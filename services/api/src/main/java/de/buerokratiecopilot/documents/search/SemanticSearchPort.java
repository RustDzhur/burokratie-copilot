package de.buerokratiecopilot.documents.search;

import java.util.List;
import java.util.UUID;

/**
 * Semantic search over document text (spec §10.5, Q-06).
 *
 * <p>The specification allows pgvector or a separate vector store, and requires the platform to work
 * without either. That makes the availability flag part of the interface rather than a detail: a
 * caller must be able to tell "nothing matched" from "this deployment cannot search that way", and
 * the interface must therefore be answerable without throwing.</p>
 */
public interface SemanticSearchPort {

    /** Why semantic search can or cannot run here, in a form the API can report verbatim. */
    enum Status {
        /** Vectors are stored and can be compared. */
        AVAILABLE,
        /** pgvector is missing, so there is nowhere to store or compare vectors. */
        UNAVAILABLE_NO_VECTOR_STORE,
        /** Vectors could be stored, but no embedding model is configured to produce them. */
        UNAVAILABLE_NO_EMBEDDING_MODEL
    }

    Status status();

    /** A sentence a user can read, explaining the status. */
    String statusReason();

    /**
     * Finds the closest chunks.
     *
     * @return hits ordered by similarity, empty when the port is unavailable
     */
    List<Hit> search(UUID tenantId, String query, int limit);

    /** One matching chunk, with the document and page it came from. */
    record Hit(UUID documentId, UUID versionId, Integer pageNumber, String chunkText, double similarity) {
    }
}
