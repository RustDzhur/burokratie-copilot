package de.buerokratiecopilot.agents.banking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.banking.BankFeeAuditAgent.FeeGroup;
import de.buerokratiecopilot.agents.banking.BankFeeAuditAgent.FeeRow;

/** Verifies the pure grouping and keyword matching of {@code banking-fee-audit}. */
class BankFeeAuditAgentTest {

    private static final LocalDate D1 = LocalDate.of(2026, 1, 31);
    private static final LocalDate D2 = LocalDate.of(2026, 2, 28);

    private static FeeRow row(String counterparty, String amount, LocalDate date, String purpose) {
        return new FeeRow(UUID.randomUUID(), date, new BigDecimal(amount), counterparty, purpose);
    }

    @Test
    void keywordMatchingIsCaseInsensitiveAndIgnoresNull() {
        assertThat(BankFeeAuditAgent.matches("Kontoführung Q1", List.of("kontoführung"))).isTrue();
        assertThat(BankFeeAuditAgent.matches("GEBÜHR Ausland", List.of("gebühr"))).isTrue();
        assertThat(BankFeeAuditAgent.matches("Miete", List.of("entgelt"))).isFalse();
        assertThat(BankFeeAuditAgent.matches(null, List.of("entgelt"))).isFalse();
        assertThat(BankFeeAuditAgent.matches("Entgelt", List.of())).isFalse();
    }

    @Test
    void debitsAreGroupedByCounterpartyAndShownPositive() {
        List<FeeRow> rows = List.of(
                row("Telekom", "-29.99", D1, "Entgelt"),
                row("Telekom", "-29.99", D2, "Entgelt"),
                row("Sparkasse", "-5.00", D1, "Kontoführung"));

        List<FeeGroup> groups = BankFeeAuditAgent.summarize(rows, new BigDecimal("1.00"));

        assertThat(groups).extracting(FeeGroup::counterparty)
                .containsExactly("Telekom", "Sparkasse");
        assertThat(groups.get(0).bookings()).isEqualTo(2);
        assertThat(groups.get(0).total()).isEqualByComparingTo("59.98");
        assertThat(groups.get(0).firstDate()).isEqualTo(D1);
        assertThat(groups.get(0).lastDate()).isEqualTo(D2);
    }

    @Test
    void theToleranceKeepsSmallPostingsOut() {
        List<FeeRow> rows = List.of(
                row("Telekom", "-29.99", D1, "Entgelt"),
                row("Kiosk", "-0.50", D1, "Gebühr"));

        List<FeeGroup> groups = BankFeeAuditAgent.summarize(rows, new BigDecimal("1.00"));

        assertThat(groups).extracting(FeeGroup::counterparty).containsExactly("Telekom");
    }

    @Test
    void aMissingCounterpartyGetsAReadableGroup() {
        List<FeeGroup> groups = BankFeeAuditAgent.summarize(
                List.of(row(null, "-12.00", D1, "Entgelt")), BigDecimal.ZERO);

        assertThat(groups).extracting(FeeGroup::counterparty)
                .containsExactly("Unbekannter Empfänger");
        assertThat(groups.get(0).total()).isEqualByComparingTo("12.00");
    }
}
