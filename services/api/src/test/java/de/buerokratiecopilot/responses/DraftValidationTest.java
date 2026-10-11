package de.buerokratiecopilot.responses;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.responses.DraftValidation.DocumentType;
import de.buerokratiecopilot.responses.DraftValidation.Draft;
import de.buerokratiecopilot.responses.DraftValidation.Fact;
import de.buerokratiecopilot.responses.DraftValidation.Requisite;
import de.buerokratiecopilot.responses.DraftValidation.Result;

/**
 * Verifies that a document with unconfirmed facts needs review before it is sent (spec §24, E-009 acceptance).
 *
 * <p>A letter written from a model's summary reads exactly like a letter written from the file, and only
 * the platform can tell the two apart — because only the platform knows which sentences came from a
 * document. The tests check that it uses that knowledge to stop the letter rather than to add a note to it.</p>
 */
class DraftValidationTest {

    private static final DraftValidation EMPTY_MINIMUM = DraftValidation.withStructuralMinimumOnly();

    /** A termination where the office has declared what it wants to see. */
    private static final DraftValidation CONFIGURED = new DraftValidation(
            Map.of(DocumentType.TERMINATION, List.of("reference", "date", "signature")));

    private static Requisite requisite(String key, String label, String value) {
        return new Requisite(key, label, value, true);
    }

    private static Draft draft(DocumentType type, List<Requisite> requisites, List<Fact> facts) {
        return new Draft(type, "Kündigung des Vertrages", "Sehr geehrte Damen und Herren, ...",
                requisites, facts);
    }

    @Test
    @DisplayName("a draft whose facts are all traceable is ready to send")
    void traceableDraftIsReady() {
        Draft complete = draft(DocumentType.TERMINATION,
                List.of(requisite("recipient", "Empfänger", "Beispiel GmbH"),
                        requisite("subject", "Betreff", "Kündigung"),
                        requisite("reference", "Aktenzeichen", "2026-4711"),
                        requisite("date", "Datum", "14.03.2026"),
                        requisite("signature", "Unterschrift", "M. Muster")),
                List.of(Fact.fromDocument("Vertrag läuft seit 2020", "vertrag.pdf", 1),
                        Fact.fromPerson("Kündigung zum 30.06.2026", "M. Muster")));

        Result result = CONFIGURED.validate(complete);

        assertThat(result.isReadyToSend()).isTrue();
        assertThat(result.unconfirmed()).isEmpty();
        assertThat(result.missing()).isEmpty();
        assertThat(result.summary()).contains("belegte Angaben");
    }

    @Test
    @DisplayName("a draft with a statement from a model is marked and needs review")
    void modelStatementNeedsReview() {
        Draft withModel = draft(DocumentType.RESPONSE,
                List.of(requisite("recipient", "Empfänger", "Behörde"),
                        requisite("subject", "Betreff", "Widerspruch")),
                List.of(Fact.fromDocument("Der Bescheid erging am 14.03.2026", "bescheid.pdf", 2),
                        Fact.fromModel("Die Frist läuft am 14.04.2026 ab", "modell-1")));

        Result result = EMPTY_MINIMUM.validate(withModel);

        assertThat(result.needsReview()).isTrue();
        assertThat(result.isReadyToSend()).isFalse();
        assertThat(result.unconfirmed()).hasSize(1);
        assertThat(result.unconfirmed().getFirst().text()).contains("Frist");
        assertThat(result.summary())
                .as("the summary has to say what has to be checked, not merely that something has to be")
                .contains("nicht belegt");
    }

    @Test
    @DisplayName("a model statement cannot declare itself confirmed")
    void aModelStatementCannotBeConfirmed() {
        // Even if a caller claims it, the source decides: letting the caller say otherwise would defeat
        // the rule the whole class exists for.
        Fact claimed = new Fact("Eine erfundene Frist", DraftValidation.FactSource.MODEL, "modell-1", true);

        Result result = EMPTY_MINIMUM.validate(draft(DocumentType.RESPONSE,
                List.of(requisite("recipient", "Empfänger", "Behörde"),
                        requisite("subject", "Betreff", "Antwort")),
                List.of(claimed)));

        assertThat(result.unconfirmed())
                .as("a model's statement is confirmed by a document or a person, and by nothing else")
                .hasSize(1);
    }

    @Test
    @DisplayName("one unconfirmed fact is enough to stop the letter")
    void oneUnconfirmedFactStopsTheLetter() {
        Draft mostlyGood = draft(DocumentType.RESPONSE,
                List.of(requisite("recipient", "Empfänger", "Behörde"),
                        requisite("subject", "Betreff", "Antwort")),
                List.of(Fact.fromDocument("Belegt", "akte.pdf", 1),
                        Fact.fromPerson("Auch belegt", "M. Muster"),
                        Fact.computed("Berechnet", "invoices.gross_amount"),
                        Fact.fromModel("Nicht belegt", "modell-1")));

        assertThat(EMPTY_MINIMUM.mayBeSent(mostlyGood)).isFalse();
    }

    @Test
    @DisplayName("a missing mandatory requisite is named")
    void missingRequisiteIsNamed() {
        Draft withoutRecipient = draft(DocumentType.RESPONSE,
                List.of(requisite("subject", "Betreff", "Antwort")),
                List.of(Fact.fromDocument("Belegt", "akte.pdf", 1)));

        Result result = EMPTY_MINIMUM.validate(withoutRecipient);

        assertThat(result.needsReview()).isTrue();
        assertThat(result.missing()).extracting(Requisite::key).containsExactly("recipient");
        assertThat(result.summary()).contains("Empfänger");
    }

    @Test
    @DisplayName("an office's own checklist is applied, and only that")
    void declaredRequisitesAreApplied() {
        Draft withoutSignature = draft(DocumentType.TERMINATION,
                List.of(requisite("recipient", "Empfänger", "Beispiel GmbH"),
                        requisite("subject", "Betreff", "Kündigung"),
                        requisite("reference", "Aktenzeichen", "2026-4711"),
                        requisite("date", "Datum", "14.03.2026")),
                List.of(Fact.fromPerson("Kündigung", "M. Muster")));

        Result result = CONFIGURED.validate(withoutSignature);

        assertThat(result.missing()).extracting(Requisite::key).containsExactly("signature");
    }

    @Test
    @DisplayName("the platform does not invent requisites beyond the structural minimum")
    void thePlatformInventsNoRequisites() {
        // A response with a recipient and a subject, and nothing else. An office that has configured
        // nothing gets no requirement it did not ask for.
        Draft minimal = draft(DocumentType.RESPONSE,
                List.of(requisite("recipient", "Empfänger", "Behörde"),
                        requisite("subject", "Betreff", "Antwort")),
                List.of(Fact.fromDocument("Belegt", "akte.pdf", 1)));

        Result result = EMPTY_MINIMUM.validate(minimal);

        assertThat(result.isReadyToSend()).isTrue();
        assertThat(DraftValidation.structuralRequisites())
                .as("only what is true of correspondence in general, never of law")
                .containsExactly("recipient", "subject");
    }

    @Test
    @DisplayName("requisites declared for another type are not applied to this one")
    void requisitesArePerType() {
        // The termination checklist mentions a reference, a date and a signature; an application is not
        // a termination and must not be checked against it.
        Draft application = draft(DocumentType.APPLICATION,
                List.of(requisite("recipient", "Empfänger", "Behörde"),
                        requisite("subject", "Betreff", "Antrag")),
                List.of(Fact.fromPerson("Antrag", "M. Muster")));

        assertThat(CONFIGURED.validate(application).isReadyToSend()).isTrue();
    }

    @Test
    @DisplayName("an empty requisite counts as missing, not as present")
    void emptyRequisiteIsMissing() {
        Draft blank = draft(DocumentType.RESPONSE,
                List.of(requisite("recipient", "Empfänger", "   "),
                        requisite("subject", "Betreff", "Antwort")),
                List.of());

        assertThat(EMPTY_MINIMUM.validate(blank).missing()).extracting(Requisite::key)
                .containsExactly("recipient");
    }

    @Test
    @DisplayName("both kinds of problem are reported together, so one pass is enough to fix them")
    void bothProblemsAreReportedTogether() {
        Draft broken = draft(DocumentType.TERMINATION,
                List.of(requisite("subject", "Betreff", "Kündigung")),
                List.of(Fact.fromModel("Eine unbelegte Frist", "modell-1")));

        Result result = CONFIGURED.validate(broken);

        assertThat(result.missing()).extracting(Requisite::key)
                .contains("recipient", "reference", "date", "signature");
        assertThat(result.unconfirmed()).hasSize(1);
        assertThat(result.problems()).hasSize(2);
        assertThat(result.asMap()).containsEntry("status", "NEEDS_REVIEW")
                .containsEntry("readyToSend", false);
    }

    @Test
    @DisplayName("a draft with no facts at all is judged only on its requisites")
    void draftWithoutFactsIsJudgedOnRequisites() {
        Draft noFacts = draft(DocumentType.REMINDER,
                List.of(requisite("recipient", "Empfänger", "Kunde"),
                        requisite("subject", "Betreff", "Zahlungserinnerung")),
                List.of());

        assertThat(EMPTY_MINIMUM.validate(noFacts).isReadyToSend()).isTrue();
    }

    @Test
    @DisplayName("a missing draft is reported rather than treated as ready")
    void missingDraftIsReported() {
        Result result = EMPTY_MINIMUM.validate(null);

        assertThat(result.needsReview()).isTrue();
        assertThat(result.problems()).isNotEmpty();
        assertThat(EMPTY_MINIMUM.mayBeSent(null)).isFalse();
    }

    @Test
    @DisplayName("every fact carries where it came from")
    void factsCarryTheirSource() {
        assertThat(Fact.fromDocument("Text", "akte.pdf", 3).reference()).isEqualTo("akte.pdf, Seite 3");
        assertThat(Fact.fromDocument("Text", "akte.pdf", null).reference()).isEqualTo("akte.pdf");
        assertThat(Fact.fromPerson("Text", "M. Muster").confirmed()).isTrue();
        assertThat(Fact.computed("119,00 EUR", "invoices.gross_amount").confirmed()).isTrue();
        assertThat(Fact.fromModel("Text", "modell-1").confirmed()).isFalse();
    }

    @Test
    @DisplayName("the library covers the kinds of correspondence the specification lists")
    void theLibraryCoversTheListedTypes() {
        assertThat(DraftValidation.knownTypes()).containsExactlyInAnyOrder(DocumentType.RESPONSE,
                DocumentType.DOCUMENT_REQUEST, DocumentType.OBJECTION, DocumentType.CLAIM,
                DocumentType.APPLICATION, DocumentType.TERMINATION, DocumentType.REMINDER,
                DocumentType.OTHER);
    }

    @Test
    @DisplayName("the result is usable by an interface without further interpretation")
    void resultIsUsableByAnInterface() {
        Draft withModel = draft(DocumentType.RESPONSE,
                List.of(requisite("recipient", "Empfänger", "Behörde"),
                        requisite("subject", "Betreff", "Antwort")),
                List.of(Fact.fromModel("Unbelegt", "modell-1")));

        var rendered = EMPTY_MINIMUM.validate(withModel).asMap();

        assertThat(rendered).containsKeys("status", "readyToSend", "unconfirmed", "missing", "problems",
                "summary");
        assertThat((List<?>) rendered.get("unconfirmed")).hasSize(1);
    }
}
