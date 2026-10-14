package de.buerokratiecopilot.documents.reading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** What the platform makes of a model's answer: only what is valid, never a repaired guess. */
class AiDocumentReaderTest {

    private final AiDocumentReader reader = new AiDocumentReader(null, null, null, null, null, null, null,
            new ObjectMapper(), new de.buerokratiecopilot.shared.config.BurokratieProperties());

    private static final java.util.Map<String, java.util.List<String>> LABELS = java.util.Map.of(
            "Zeitraum", java.util.List.of("Bewilligungszeitraum", "Zeitraum"),
            "Frist", java.util.List.of("Frist", "bis spätestens"));

    @Test
    @DisplayName("reads the JSON object even when the model wraps it in text or a code fence")
    void readsWrappedJson() throws IOException {
        var parsed = reader.parse("""
                Hier das Ergebnis:
                ```json
                {"documentDate": "2025-10-22", "periodFrom": "2025-11-01", "periodTo": "2025-12-31",
                 "deadline": null, "amount": 280.5, "reference": "RE-2025-0412", "sender": "Stadtwerke",
                 "handwritten": true, "summary": "Rechnung"}
                ```
                """);

        assertThat(parsed.documentDate()).isEqualTo(LocalDate.of(2025, 10, 22));
        assertThat(parsed.periodFrom()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(parsed.periodTo()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(parsed.deadline()).isNull();
        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("280.5"));
        assertThat(parsed.reference()).isEqualTo("RE-2025-0412");
        assertThat(parsed.handwritten()).isTrue();
    }

    @Test
    @DisplayName("a date in the wrong form or the word null is no value, and is not guessed at")
    void invalidValuesAreNull() throws IOException {
        var parsed = reader.parse("{\"documentDate\": \"22.10.2025\", \"deadline\": \"null\", \"amount\": \"zweihundert\","
                + " \"reference\": \"  \"}");

        assertThat(parsed.documentDate()).isNull();
        assertThat(parsed.deadline()).isNull();
        assertThat(parsed.amount()).isNull();
        assertThat(parsed.reference()).isNull();
    }

    @Test
    @DisplayName("an amount written as text with a decimal comma is read")
    void textAmount() throws IOException {
        assertThat(reader.parse("{\"amount\": \"280,00 EUR\"}").amount()).isEqualByComparingTo("280.00");
    }

    @Test
    @DisplayName("an answer without a JSON object is refused")
    void noJson() {
        assertThatThrownBy(() -> reader.parse("Ich kann das Dokument nicht lesen.")).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a label in the text without a readable value after it is the sign of handwriting: the AI is needed")
    void labelWithoutValueNeedsReading() {
        String reason = AiDocumentReader.evidenceOfUnreadValue(java.util.Set.of("Zeitraum"), LABELS,
                java.util.Set.of(), "EKS Jobcenter\nBewilligungszeitraum: ____ bis ____\n", 0.9);

        assertThat(reason).contains("Bewilligungszeitraum").contains("Zeitraum");
    }

    @Test
    @DisplayName("a document that does not mention a needed date at all does not call the AI: absence is an answer")
    void noLabelNoReading() {
        assertThat(AiDocumentReader.evidenceOfUnreadValue(java.util.Set.of("Frist", "Zeitraum"), LABELS,
                java.util.Set.of(), "Rechnung über Büromaterial, vielen Dank für Ihren Auftrag.", 0.95)).isNull();
    }

    @Test
    @DisplayName("a value the platform already read is not read again, and a label inside a longer word is not a label")
    void alreadyReadAndWordBoundaries() {
        assertThat(AiDocumentReader.evidenceOfUnreadValue(java.util.Set.of("Frist"), LABELS,
                java.util.Set.of("Frist"), "Frist: 28.10.2026", 0.3)).isNull();
        assertThat(AiDocumentReader.evidenceOfUnreadValue(java.util.Set.of("Frist"), LABELS,
                java.util.Set.of(), "Die Fristverlängerung wurde gewährt.", 0.95)).isNull();
    }

    @Test
    @DisplayName("a poorly readable scan with a needed value missing calls the AI; a good scan does not")
    void poorScan() {
        assertThat(AiDocumentReader.evidenceOfUnreadValue(java.util.Set.of("Frist"), LABELS, java.util.Set.of(),
                "unleserlicher Text", 0.4)).contains("poorly readable");
        assertThat(AiDocumentReader.evidenceOfUnreadValue(java.util.Set.of("Frist"), LABELS, java.util.Set.of(),
                "ein gut gelesener Text ohne Datum", 0.9)).isNull();
    }
}
