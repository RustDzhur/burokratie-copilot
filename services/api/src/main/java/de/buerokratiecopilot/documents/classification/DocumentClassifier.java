package de.buerokratiecopilot.documents.classification;

import java.util.List;
import java.util.UUID;

/**
 * Assigns a document type (spec §9.3).
 *
 * <p>The result always carries the evidence: which rules fired and with what weight. A classification
 * a user cannot understand is a classification they cannot correct, and the specification requires
 * the basis of an automated decision to be visible.</p>
 *
 * <p>Whether it is a rule engine, a language model or both is a deployment decision — the interface
 * exists so the AI implementation (task E-005) can be added next to this one without the document
 * module noticing.</p>
 */
public interface DocumentClassifier {

    /** Name of the method, stored with the classification for traceability. */
    String method();

    Classification classify(Input input);

    /**
     * Everything a classifier may look at. Passing it explicitly keeps a classifier free of hidden
     * database access, which is what makes the AI variant substitutable.
     */
    record Input(UUID tenantId, String text, String filename, String senderEmail, String senderName) {
    }

    /**
     * @param typeId      recognised type, or {@code null} when nothing matched
     * @param code        code of that type, for logs and the API
     * @param confidence  0..1, or {@code null} when the classifier cannot quantify it — a made-up
     *                    number would silently drive the automation threshold
     * @param matchedRules human readable list of the evidence that fired
     * @param rationale   one sentence a user can read
     */
    record Classification(UUID typeId, String code, Double confidence, List<String> matchedRules,
                          String rationale) {

        public static Classification none(String rationale) {
            return new Classification(null, null, null, List.of(), rationale);
        }

        public boolean isEmpty() {
            return typeId == null;
        }
    }
}
