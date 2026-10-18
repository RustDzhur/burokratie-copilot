package de.buerokratiecopilot.responses;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a prepared letter before it can be sent (spec §24, E-009).
 *
 * <p>The acceptance criterion is that a document containing unconfirmed facts is marked and needs review
 * before it goes out. That is the whole point of the class: a letter written from a model's summary reads
 * exactly like a letter written from the file, and only the platform can tell the two apart — because
 * only the platform knows which sentences came from a document and which did not.</p>
 *
 * <p><strong>The platform does not invent requisites.</strong> What a letter must contain is partly law
 * and partly the practice of the office, and neither is something this code can know. A document type
 * therefore <em>declares</em> its required requisites, and the checklist checks those. What the platform
 * does supply is the mechanism and one structural minimum — a letter needs somebody to write to and
 * something to be about — which is a statement about correspondence rather than about law.</p>
 *
 * <p>A fact is confirmed when it can be traced to a document or to a person. A fact the model produced,
 * however plausible, is unconfirmed, and one unconfirmed fact is enough to stop the letter.</p>
 */
public final class DraftValidation {

    /** The kinds of correspondence the library covers. */
    public enum DocumentType {
        /** A reply to an authority or a counterparty. */
        RESPONSE,
        /** A request for documents or information. */
        DOCUMENT_REQUEST,
        /** A formal objection against an administrative act. */
        OBJECTION,
        /** A claim, for example for a refund. */
        CLAIM,
        /** An application, for example for a permit. */
        APPLICATION,
        /** Notice of termination. */
        TERMINATION,
        /** A reminder. */
        REMINDER,
        /** Anything else; the office declares its own requisites. */
        OTHER
    }

    /** Where a statement came from. */
    public enum FactSource {
        /** From a document the tenant holds, with the page it was read on. */
        DOCUMENT,
        /** Entered by a person. */
        PERSON,
        /** Computed by the platform from stored values. */
        COMPUTED,
        /** Written by a model and traceable to nothing. */
        MODEL
    }

    /**
     * One statement in the letter.
     *
     * @param confirmed      whether somebody stands behind it; a model's statement never is
     * @param reference      where it came from — a document and page, or a person's name
     */
    public record Fact(String text, FactSource source, String reference, boolean confirmed) {

        /**
         * Enforces what the source means.
         *
         * <p>A model's statement is never confirmed, whatever a caller passes: the rule is the reason
         * this class exists, and a public constructor that could be talked out of it would leave the
         * rule to the caller's discipline.</p>
         */
        public Fact {
            if (source == FactSource.MODEL) {
                confirmed = false;
            }
        }

        /** A statement from a document, confirmed by its source. */
        public static Fact fromDocument(String text, String documentName, Integer page) {
            return new Fact(text, FactSource.DOCUMENT,
                    documentName + (page == null ? "" : ", Seite " + page), true);
        }

        /** A statement a person put in. */
        public static Fact fromPerson(String text, String person) {
            return new Fact(text, FactSource.PERSON, person, true);
        }

        /** A statement the platform computed from stored values. */
        public static Fact computed(String text, String basis) {
            return new Fact(text, FactSource.COMPUTED, basis, true);
        }

        /**
         * A statement that came from nowhere in particular.
         *
         * <p>Explicitly unconfirmed, whatever the caller passes: a model's statement cannot be confirmed
         * by the model, and letting a caller say otherwise would defeat the rule.</p>
         */
        public static Fact fromModel(String text, String model) {
            return new Fact(text, FactSource.MODEL, model, false);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("text", text);
            map.put("source", source.name());
            map.put("reference", reference);
            map.put("confirmed", confirmed);
            return map;
        }
    }

    /** Something the letter has to contain. */
    public record Requisite(String key, String label, String value, boolean required) {

        public boolean isPresent() {
            return value != null && !value.isBlank();
        }
    }

    /** A prepared letter. */
    public record Draft(DocumentType type, String subject, String body, List<Requisite> requisites,
                        List<Fact> facts) {
    }

    /** What the check found. */
    public record Result(String status, List<Fact> unconfirmed, List<Requisite> missing,
                         List<String> problems) {

        /** Whether the letter may be sent as it stands. */
        public boolean isReadyToSend() {
            return "READY".equals(status);
        }

        public boolean needsReview() {
            return "NEEDS_REVIEW".equals(status);
        }

        /** One sentence a user can act on. */
        public String summary() {
            if (isReadyToSend()) {
                return "Der Entwurf ist vollständig und enthält nur belegte Angaben.";
            }
            StringBuilder text = new StringBuilder();
            if (!unconfirmed.isEmpty()) {
                text.append(unconfirmed.size()).append(" Angabe(n) sind nicht belegt und müssen geprüft "
                        + "werden, bevor das Schreiben hinausgeht.");
            }
            if (!missing.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append(' ');
                }
                text.append("Es fehlen: ")
                        .append(String.join(", ", missing.stream().map(Requisite::label).toList()))
                        .append('.');
            }
            return text.toString();
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            map.put("readyToSend", isReadyToSend());
            map.put("unconfirmed", unconfirmed.stream().map(Fact::asMap).toList());
            map.put("missing", missing.stream().map(Requisite::label).toList());
            map.put("problems", problems);
            map.put("summary", summary());
            return map;
        }
    }

    /**
     * The structural minimum: a letter needs somebody to write to and something to be about.
     *
     * <p>These two are required for every type, and only these two. Everything beyond them comes from the
     * document type's own list, because what a termination must contain is not something this platform is
     * entitled to decide.</p>
     */
    private static final List<String> STRUCTURAL_REQUISITES = List.of("recipient", "subject");

    /** The requisites a type declares for itself, supplied by whoever configures the office. */
    private final Map<DocumentType, List<String>> declaredRequisites;

    public DraftValidation(Map<DocumentType, List<String>> declaredRequisites) {
        this.declaredRequisites = declaredRequisites == null ? Map.of() : Map.copyOf(declaredRequisites);
    }

    /** A validator with only the structural minimum, for an office that has configured nothing. */
    public static DraftValidation withStructuralMinimumOnly() {
        return new DraftValidation(Map.of());
    }

    /** Checks a draft. */
    public Result validate(Draft draft) {
        if (draft == null) {
            return new Result("NEEDS_REVIEW", List.of(), List.of(),
                    List.of("Es liegt kein Entwurf vor"));
        }

        List<Fact> unconfirmed = draft.facts() == null ? List.of()
                : draft.facts().stream().filter(fact -> !fact.confirmed()).toList();
        List<Requisite> missing = missingRequisites(draft);

        List<String> problems = new ArrayList<>();
        if (!missing.isEmpty()) {
            problems.add("Pflichtangaben fehlen: "
                    + String.join(", ", missing.stream().map(Requisite::label).toList()));
        }
        if (!unconfirmed.isEmpty()) {
            // Not "a warning": the letter is stopped, and the reason names the statements.
            problems.add("Nicht belegte Angaben: "
                    + String.join(" | ", unconfirmed.stream().map(Fact::text).toList()));
        }

        String status = problems.isEmpty() ? "READY" : "NEEDS_REVIEW";
        return new Result(status, List.copyOf(unconfirmed), List.copyOf(missing), List.copyOf(problems));
    }

    /**
     * Whether a draft may be handed to the sending path.
     *
     * <p>The sending path asks this rather than checking the status itself, so there is one rule and one
     * place it is applied.</p>
     */
    public boolean mayBeSent(Draft draft) {
        return validate(draft).isReadyToSend();
    }

    private List<Requisite> missingRequisites(Draft draft) {
        List<Requisite> requisites = draft.requisites() == null ? List.of() : draft.requisites();
        Set<String> required = new LinkedHashSet<>(STRUCTURAL_REQUISITES);
        // A draft may have no type: it was produced from a template whose type is not recorded on the letter,
        // and asking for the declared requisites of a null type throws on an immutable map. No type means no
        // extra requisites, which is the honest answer - the structural minimum still applies.
        if (draft.type() != null) {
            required.addAll(declaredRequisites.getOrDefault(draft.type(), List.of()));
        }

        List<Requisite> missing = new ArrayList<>();
        for (String key : required) {
            Requisite requisite = requisites.stream().filter(entry -> entry.key().equals(key)).findFirst()
                    .orElse(null);
            if (requisite == null || !requisite.isPresent()) {
                missing.add(requisite == null
                        ? new Requisite(key, labelOf(key), null, true)
                        : requisite);
            }
        }
        return missing;
    }

    /** A readable label for a structural requisite; a declared one brings its own. */
    private static String labelOf(String key) {
        return switch (key) {
            case "recipient" -> "Empfänger";
            case "subject" -> "Betreff";
            case "date" -> "Datum";
            case "reference" -> "Aktenzeichen";
            case "signature" -> "Unterschrift";
            default -> key;
        };
    }

    /** The types the library covers. */
    public static List<DocumentType> knownTypes() {
        return List.of(DocumentType.values());
    }

    /** The structural requisites, so a caller can document what is always required. */
    public static List<String> structuralRequisites() {
        return STRUCTURAL_REQUISITES;
    }
}
