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
            new ObjectMapper());

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
}
