package de.buerokratiecopilot.agents.legal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.legal.ContractControlAgent.Assessment;
import de.buerokratiecopilot.agents.legal.ContractControlAgent.ContractDeadline;
import de.buerokratiecopilot.agents.legal.ContractControlAgent.ContractRow;
import de.buerokratiecopilot.agents.legal.ContractControlAgent.ObligationDue;
import de.buerokratiecopilot.agents.legal.ContractControlAgent.ObligationRow;

/** Verifies the pure assessment of {@code legal-contract-control}. */
class ContractControlAgentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    @Test
    void contractsAreSortedAndCountDaysToTheEndOfTheTerm() {
        List<ContractRow> rows = List.of(
                new ContractRow(UUID.randomUUID(), "V-2", "Wartung", TODAY.plusDays(30), "ACTIVE",
                        new BigDecimal("120.00"), "EUR"),
                new ContractRow(UUID.randomUUID(), "V-1", "Miete", TODAY.plusDays(5), "ACTIVE",
                        null, null),
                new ContractRow(UUID.randomUUID(), "V-3", "Alt", TODAY.minusDays(2), "EXPIRED",
                        null, null));

        Assessment assessment = ContractControlAgent.assess(rows, List.of(), TODAY);

        assertThat(assessment.contracts()).extracting(ContractDeadline::number)
                .containsExactly("V-1", "V-2");
        assertThat(assessment.contracts().get(0).daysLeft()).isEqualTo(5);
        assertThat(assessment.contracts().get(1).daysLeft()).isEqualTo(30);
    }

    @Test
    void obligationsCarryNegativeDaysWhenOverdue() {
        UUID contractId = UUID.randomUUID();
        List<ObligationRow> rows = List.of(
                new ObligationRow(UUID.randomUUID(), contractId, "Zahlung", TODAY.minusDays(4),
                        "DUE"),
                new ObligationRow(UUID.randomUUID(), contractId, "Bericht", TODAY.plusDays(3),
                        "OPEN"));

        Assessment assessment = ContractControlAgent.assess(List.of(), rows, TODAY);

        assertThat(assessment.obligations()).extracting(ObligationDue::title)
                .containsExactly("Zahlung", "Bericht");
        assertThat(assessment.obligations().get(0).daysLeft()).isEqualTo(-4);
        assertThat(assessment.obligations().get(1).daysLeft()).isEqualTo(3);
        assertThat(assessment.overdueObligations()).isEqualTo(1);
    }

    @Test
    void anEmptyWindowProducesAnEmptyAssessment() {
        Assessment assessment = ContractControlAgent.assess(List.of(), List.of(), TODAY);

        assertThat(assessment.contracts()).isEmpty();
        assertThat(assessment.obligations()).isEmpty();
        assertThat(assessment.overdueObligations()).isZero();
    }

    @Test
    void horizonFallsBackToNinetyDaysOnBadInput() {
        assertThat(ContractControlAgent.parseHorizon(null)).isEqualTo(90);
        assertThat(ContractControlAgent.parseHorizon("abc")).isEqualTo(90);
        assertThat(ContractControlAgent.parseHorizon("0")).isEqualTo(90);
        assertThat(ContractControlAgent.parseHorizon("30")).isEqualTo(30);
    }
}
