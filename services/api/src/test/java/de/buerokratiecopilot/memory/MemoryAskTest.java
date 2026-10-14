package de.buerokratiecopilot.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What the memory accepts of a model's text, and when it shows a contradiction. No database, no model. */
class MemoryAskTest {

    @Test
    @DisplayName("an answer is accepted only when it cites sources that were offered")
    void verification() {
        assertThat(MemoryAskService.verify("Die Miete beträgt 850 EUR [1] und steigt im Mai [2].", 3).accepted()).isTrue();
        assertThat(MemoryAskService.verify("Die Miete beträgt 850 EUR.", 3).accepted()).as("no citation").isFalse();
        var invented = MemoryAskService.verify("Die Miete beträgt 850 EUR [4].", 3);
        assertThat(invented.accepted()).as("a source that was never offered").isFalse();
        assertThat(invented.unknown()).containsExactly(4);
        assertThat(MemoryAskService.verify("Teils belegt [1], teils erfunden [0].", 3).accepted()).isFalse();
        assertThat(MemoryAskService.verify(null, 3).accepted()).isFalse();
    }

    private static MemoryRetrievalService.Hit fact(UUID subject, String key, String normalized, String doc) {
        return new MemoryRetrievalService.Hit(MemoryRetrievalService.Kind.FACT, UUID.randomUUID(), doc, 1,
                key + ": " + normalized, 1.0, "UNVERIFIED", null, null, "CONTACT", subject, key, normalized);
    }

    @Test
    @DisplayName("two facts about the same thing with the same key and different values are shown as a contradiction")
    void contradictions() {
        UUID subject = UUID.randomUUID();
        var found = MemoryRetrievalService.contradictions(List.of(
                fact(subject, "Monatsbetrag", "850.00", "Vertrag.pdf"),
                fact(subject, "Monatsbetrag", "920.00", "Erhöhung.pdf"),
                fact(subject, "Kundennummer", "4711", "Vertrag.pdf"),
                fact(subject, "Kundennummer", "4711", "Erhöhung.pdf"),
                fact(UUID.randomUUID(), "Monatsbetrag", "100.00", "Anderes.pdf")));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).factKey()).isEqualTo("Monatsbetrag");
        assertThat(found.get(0).sources()).hasSize(2);
    }

    @Test
    @DisplayName("facts without a subject are not compared: different documents may legitimately differ")
    void noSubjectNoContradiction() {
        var a = new MemoryRetrievalService.Hit(MemoryRetrievalService.Kind.FACT, UUID.randomUUID(), "a.pdf", 1, "x", 1,
                "UNVERIFIED", null, null, null, null, "Rechnungsnummer", "1");
        var b = new MemoryRetrievalService.Hit(MemoryRetrievalService.Kind.FACT, UUID.randomUUID(), "b.pdf", 1, "x", 1,
                "UNVERIFIED", null, null, null, null, "Rechnungsnummer", "2");
        assertThat(MemoryRetrievalService.contradictions(List.of(a, b))).isEmpty();
    }
}
