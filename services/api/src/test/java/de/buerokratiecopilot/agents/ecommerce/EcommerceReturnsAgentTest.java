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

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class EcommerceReturnsAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final EcommerceReturnsAgent agent = new EcommerceReturnsAgent(jdbc, new ObjectMapper(),
            workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    private Map<String, Object> invoice() {
        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("id", UUID.randomUUID());
        invoice.put("invoice_number", "RE-2026-001");
        invoice.put("issue_date", java.sql.Date.valueOf(LocalDate.now().minusDays(3)));
        invoice.put("gross_amount", new BigDecimal("119.00"));
        invoice.put("currency", "EUR");
        return invoice;
    }

    @Test
    void preparesARefundWithoutExecutingIt() {
        when(jdbc.queryForList(contains("from invoices"), any(Object.class), any(Object.class))).thenReturn(List.of(invoice()));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String items = "[{\"description\":\"Schuh\",\"quantity\":1,\"unitPrice\":79.90}]";

        AgentResult result = agent.run(ctx(Map.of("orderId", "RE-2026-001",
                "returnItems", items, "reason", "Passt nicht")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("refund", "79.90")
                .containsEntry("refundExecuted", "false");
        assertThat(result.summary()).contains("keine Erstattung ausgeführt");
    }

    @Test
    void reportsAnExceededReturnWindowInsteadOfRefunding() {
        Map<String, Object> oldInvoice = invoice();
        oldInvoice.put("issue_date", java.sql.Date.valueOf(LocalDate.now().minusDays(40)));
        when(jdbc.queryForList(contains("from invoices"), any(Object.class), any(Object.class))).thenReturn(List.of(oldInvoice));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String items = "[{\"description\":\"Schuh\",\"quantity\":1,\"unitPrice\":79.90}]";

        AgentResult result = agent.run(ctx(Map.of("orderId", "RE-2026-001", "returnItems", items)));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "RETURN_WINDOW_EXCEEDED");
    }

    @Test
    void skipsWithoutReturnItems() {
        AgentResult result = agent.run(ctx(Map.of("orderId", "RE-2026-001")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("returnItems");
    }
}
