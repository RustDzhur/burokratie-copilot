package de.buerokratiecopilot.agents.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class EcommercePaymentCheckAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final EcommercePaymentCheckAgent agent = new EcommercePaymentCheckAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx() {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), Map.of());
    }

    private Map<String, Object> row(int failed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("invoice_number", "RE-2026-001");
        row.put("due_date", java.sql.Date.valueOf(LocalDate.now().minusDays(10)));
        row.put("gross_amount", new BigDecimal("119.00"));
        row.put("paid_amount", BigDecimal.ZERO);
        row.put("currency", "EUR");
        row.put("status", "OVERDUE");
        row.put("payment_status", "UNPAID");
        row.put("failed_attempts", failed);
        row.put("failure_reason", "Karte abgelehnt");
        return row;
    }

    @Test
    void preparesAReminderForAnOrdinaryOverdueInvoice() {
        when(jdbc.queryForList(contains("from invoices"), any(Object[].class)))
                .thenReturn(List.of(row(0)));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx());

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("reminders", "1")
                .containsEntry("cancellationReviews", "0")
                .containsEntry("autoCancel", "false");
    }

    @Test
    void proposesACancellationReviewOnlyAfterTheRetryLimit() {
        when(jdbc.queryForList(contains("from invoices"), any(Object[].class)))
                .thenReturn(List.of(row(3)));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx());

        assertThat(result.meta()).containsEntry("reminders", "0")
                .containsEntry("cancellationReviews", "1");
        assertThat(result.summary()).contains("kein Storno ausgeführt");
    }

    @Test
    void skipsWhenNothingIsOverdue() {
        when(jdbc.queryForList(contains("from invoices"), any(Object[].class))).thenReturn(List.of());

        AgentResult result = agent.run(ctx());

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine überfälligen");
    }
}
