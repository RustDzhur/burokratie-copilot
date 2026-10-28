package de.buerokratiecopilot.documents.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One hit of a search (spec §10.5).
 *
 * <p>A hit names the pages the query matched, not only the document: the specification requires a
 * result to lead the user to the place in the file, and "it is somewhere in these 40 pages" is not a
 * place.</p>
 */
public record SearchResult(UUID documentId, String documentNumber, String filename, String typeCode,
                           String typeName, String processingStatus, Instant receivedAt, double rank,
                           String snippet, List<PageHit> pages) {

    /** A page whose text matches the query, with a short excerpt. */
    public record PageHit(int pageNumber, String excerpt) {
    }
}
