package de.buerokratiecopilot.documents.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Allowed transitions of the document processing pipeline (spec §6.3).
 *
 * <p>The specification requires that transitions are controlled by the backend and that an
 * arbitrary status change through the API is impossible. Encoding the graph here — and unit testing
 * it — is what turns that requirement into an enforced rule rather than a convention.</p>
 */
public final class DocumentStatusMachine {

    private static final Map<Document.ProcessingStatus, Set<Document.ProcessingStatus>> ALLOWED =
            new EnumMap<>(Document.ProcessingStatus.class);

    static {
        allow(Document.ProcessingStatus.RECEIVED, Document.ProcessingStatus.QUARANTINED,
                Document.ProcessingStatus.UNSUPPORTED, Document.ProcessingStatus.DUPLICATE,
                Document.ProcessingStatus.CANCELLED, Document.ProcessingStatus.FAILED);
        allow(Document.ProcessingStatus.QUARANTINED, Document.ProcessingStatus.OCR_PENDING,
                Document.ProcessingStatus.EXTRACTING, Document.ProcessingStatus.FAILED,
                Document.ProcessingStatus.CANCELLED);
        allow(Document.ProcessingStatus.OCR_PENDING, Document.ProcessingStatus.EXTRACTING,
                Document.ProcessingStatus.REVIEW_REQUIRED, Document.ProcessingStatus.FAILED);
        allow(Document.ProcessingStatus.EXTRACTING, Document.ProcessingStatus.CLASSIFYING,
                Document.ProcessingStatus.REVIEW_REQUIRED, Document.ProcessingStatus.FAILED);
        allow(Document.ProcessingStatus.CLASSIFYING, Document.ProcessingStatus.MATCHING,
                Document.ProcessingStatus.REVIEW_REQUIRED, Document.ProcessingStatus.READY,
                Document.ProcessingStatus.FAILED);
        allow(Document.ProcessingStatus.MATCHING, Document.ProcessingStatus.READY,
                Document.ProcessingStatus.REVIEW_REQUIRED, Document.ProcessingStatus.FAILED);
        allow(Document.ProcessingStatus.REVIEW_REQUIRED, Document.ProcessingStatus.READY,
                Document.ProcessingStatus.MATCHING, Document.ProcessingStatus.FAILED,
                Document.ProcessingStatus.CANCELLED);
        allow(Document.ProcessingStatus.READY, Document.ProcessingStatus.ACTION_PENDING,
                Document.ProcessingStatus.COMPLETED, Document.ProcessingStatus.REVIEW_REQUIRED,
                Document.ProcessingStatus.ARCHIVED, Document.ProcessingStatus.LEGAL_HOLD,
                Document.ProcessingStatus.FAILED);
        allow(Document.ProcessingStatus.ACTION_PENDING, Document.ProcessingStatus.COMPLETED,
                Document.ProcessingStatus.READY, Document.ProcessingStatus.FAILED);
        allow(Document.ProcessingStatus.COMPLETED, Document.ProcessingStatus.ARCHIVED,
                Document.ProcessingStatus.LEGAL_HOLD, Document.ProcessingStatus.READY);
        allow(Document.ProcessingStatus.FAILED, Document.ProcessingStatus.RECEIVED,
                Document.ProcessingStatus.OCR_PENDING, Document.ProcessingStatus.EXTRACTING,
                Document.ProcessingStatus.CLASSIFYING, Document.ProcessingStatus.CANCELLED);
        allow(Document.ProcessingStatus.DUPLICATE, Document.ProcessingStatus.ARCHIVED,
                Document.ProcessingStatus.CANCELLED);
        allow(Document.ProcessingStatus.ARCHIVED, Document.ProcessingStatus.LEGAL_HOLD);
        allow(Document.ProcessingStatus.LEGAL_HOLD, Document.ProcessingStatus.ARCHIVED,
                Document.ProcessingStatus.READY);
    }

    private DocumentStatusMachine() {
    }

    private static void allow(Document.ProcessingStatus from, Document.ProcessingStatus... to) {
        ALLOWED.put(from, EnumSet.copyOf(Set.of(to)));
    }

    public static boolean canTransition(Document.ProcessingStatus from, Document.ProcessingStatus to) {
        if (from == to) {
            return true;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Validates a transition.
     *
     * @throws InvalidDocumentStatusTransition when the change is not part of the pipeline
     */
    public static void requireTransition(Document.ProcessingStatus from, Document.ProcessingStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidDocumentStatusTransition(from, to);
        }
    }

    public static Set<Document.ProcessingStatus> nextStates(Document.ProcessingStatus from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, Set.of()));
    }
}
