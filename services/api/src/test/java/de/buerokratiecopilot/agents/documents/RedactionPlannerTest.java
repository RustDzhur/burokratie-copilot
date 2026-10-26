package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pattern rules of {@code doc-redact}: real texts, verifiable positions.
 *
 * <p>No Spring, no mocks: the planner is pure, and a wrong rule here means a wrong black box on a
 * person's letter. The IBAN test uses the well-known example IBAN (DE89 3704 0044 0532 0130 00);
 * a transposed digit must not pass the ISO 13616 checksum.</p>
 */
class RedactionPlannerTest {

    @Test
    @DisplayName("a valid IBAN is found with its position; a checksum-wrong one is not")
    void findsValidIbanOnly() {
        String text = "Konto: DE89 3704 0044 0532 0130 00\n"
                + "falsch: DE68 3704 0044 0532 0130 00";

        List<RedactionPlanner.Finding> findings = RedactionPlanner.find(text, 1);

        List<String> ibans = findings.stream().filter(f -> f.type().equals("IBAN"))
                .map(RedactionPlanner.Finding::value).toList();
        assertThat(ibans).containsExactly("DE89 3704 0044 0532 0130 00");
    }

    @Test
    @DisplayName("e-mail, telephone and address are all found on the page they stand on")
    void findsEmailPhoneAndAddress() {
        String text = "Kontakt: anna.mustermann@example.de, Tel. +49 30 12345678\n"
                + "Anschrift: 10115 Berlin, Musterstraße 1";

        List<RedactionPlanner.Finding> findings = RedactionPlanner.find(text, 2);

        assertThat(findings.stream().map(RedactionPlanner.Finding::type))
                .contains("EMAIL", "PHONE", "ADDRESS");
        RedactionPlanner.Finding email = findings.stream().filter(f -> f.type().equals("EMAIL"))
                .findFirst().orElseThrow();
        assertThat(email.page()).isEqualTo(2);
        assertThat(text.substring(email.start(), email.end())).isEqualTo("anna.mustermann@example.de");
        assertThat(email.snippet()).contains("Kontakt:");
    }

    @Test
    @DisplayName("positions do not overlap: an amount next to an address is not a second address")
    void noOverlappingFindings() {
        String text = "Summe 1.190,00 EUR, Rechnung an 10115 Berlin";

        List<RedactionPlanner.Finding> findings = RedactionPlanner.find(text, 1);

        for (RedactionPlanner.Finding left : findings) {
            for (RedactionPlanner.Finding right : findings) {
                if (left == right) {
                    continue;
                }
                assertThat(left.start() < right.end() && right.start() < left.end())
                        .as("overlap between %s and %s", left.value(), right.value())
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("a text without personal data yields an empty plan, not an invented one")
    void emptyTextYieldsEmptyPlan() {
        assertThat(RedactionPlanner.find("Sehr geehrte Damen und Herren, wir bitten um Kenntnisnahme.", 1))
                .isEmpty();
        assertThat(RedactionPlanner.find("", 1)).isEmpty();
        assertThat(RedactionPlanner.find(null, 1)).isEmpty();
    }

    @Test
    @DisplayName("findings are ordered by position")
    void findingsAreSorted() {
        String text = "Tel. 030 1234567\nIBAN DE89 3704 0044 0532 0130 00\n03012 Beispielstadt";

        List<RedactionPlanner.Finding> findings = RedactionPlanner.find(text, 1);

        List<Integer> starts = findings.stream().map(RedactionPlanner.Finding::start).toList();
        assertThat(starts).isSorted();
        assertThat(findings.stream().map(RedactionPlanner.Finding::type)
                .collect(Collectors.joining(",")))
                .contains("PHONE").contains("IBAN").contains("ADDRESS");
    }
}
