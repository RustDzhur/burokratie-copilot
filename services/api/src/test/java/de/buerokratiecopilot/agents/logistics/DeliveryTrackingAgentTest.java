package de.buerokratiecopilot.agents.logistics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.logistics.DeliveryTrackingAgent.Delivery;
import de.buerokratiecopilot.agents.logistics.DeliveryTrackingAgent.DeliveryRow;
import de.buerokratiecopilot.agents.logistics.DeliveryTrackingAgent.State;

/** Verifies the date arithmetic of {@code logistics-delivery-tracking}. */
class DeliveryTrackingAgentTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private static DeliveryRow row(String number, LocalDate expected) {
        return new DeliveryRow(UUID.randomUUID(), number, "Lieferant", expected, "OPEN",
                new BigDecimal("100.00"));
    }

    @Test
    void overdueTodayAndSoonAreSeparated() {
        List<Delivery> deliveries = DeliveryTrackingAgent.assess(List.of(
                row("PO-1", TODAY.minusDays(6)),
                row("PO-2", TODAY),
                row("PO-3", TODAY.plusDays(4))), TODAY, 1);

        assertThat(deliveries).extracting(Delivery::state)
                .containsExactly(State.OVERDUE, State.DUE_TODAY, State.DUE_SOON);
        assertThat(deliveries.get(0).daysLeft()).isEqualTo(-6);
        assertThat(deliveries.get(2).daysLeft()).isEqualTo(4);
    }

    @Test
    void aSlipBelowTheThresholdIsNotReportedYet() {
        List<Delivery> deliveries = DeliveryTrackingAgent.assess(
                List.of(row("PO-1", TODAY.minusDays(1))), TODAY, 3);

        assertThat(deliveries).isEmpty();
    }

    @Test
    void deliveriesAreSortedByTheExpectedDate() {
        List<Delivery> deliveries = DeliveryTrackingAgent.assess(List.of(
                row("PO-2", TODAY.plusDays(5)),
                row("PO-1", TODAY.minusDays(2)),
                row("PO-3", TODAY)), TODAY, 1);

        assertThat(deliveries).extracting(Delivery::poNumber).containsExactly("PO-1", "PO-3", "PO-2");
    }

    @Test
    void horizonFallsBackOnBadInput() {
        assertThat(DeliveryTrackingAgent.parseHorizon(null)).isEqualTo(14);
        assertThat(DeliveryTrackingAgent.parseHorizon("abc")).isEqualTo(14);
        assertThat(DeliveryTrackingAgent.parseHorizon("999")).isEqualTo(14);
        assertThat(DeliveryTrackingAgent.parseHorizon("30")).isEqualTo(30);
    }
}
