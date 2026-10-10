package de.buerokratiecopilot.documents.domain;

/**
 * Raised when a caller tries to move a document into a state the pipeline does not allow.
 *
 * <p>Maps to HTTP 409 with the code {@code INVALID_STATE_TRANSITION}, so a client can tell "you asked
 * for something impossible" apart from "you are not allowed to do this".</p>
 */
public class InvalidDocumentStatusTransition extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidDocumentStatusTransition(Document.ProcessingStatus from, Document.ProcessingStatus to) {
        super("A document cannot change from %s to %s".formatted(from, to));
    }
}
