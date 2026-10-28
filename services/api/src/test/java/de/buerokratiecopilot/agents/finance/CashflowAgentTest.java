package de.buerokratiecopilot.agents.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

class CashflowAgentTest {

    private JdbcTemplate jdbc;
    private CashflowAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        agent = new CashflowAgent(jdbc);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    private static Map<String, Object> invoice(String gross, String paid, int dueInDays) {
        return Map.of("due_date", java.sql.Date.valueOf(LocalDate.now().plusDays(dueInDays)),
                "gross_amount", new BigDecimal(gross), "paid_amount", new BigDecimal(paid));
    }

    @Test
    void buildsRollingForecast() {
        when(jdbc.queryForList(contains("bank_statements"), any(Object.class))).thenReturn(List.of(
                Map.of("closing_balance", new BigDecimal("1000.00"), "currency", "EUR")));
        when(jdbc.queryForList(anyString(), eq(tenantId), eq("OUTGOING"), any(), any(), any(), any(),
                any())).thenReturn(List.of(invoice("2000.00", "0.00", 5)));
        when(jdbc.queryForList(anyString(), eq(tenantId), eq("INCOMING"), any(), any(), any(), any(),
                any(), any())).thenReturn(List.of(invoice("1500.00", "500.00", 10)));

        AgentResult result = agent.run(ctx(Map.of("horizonDays", "30")));

        assertTrue(result.ok());
        assertEquals("1000.00", result.meta().get("baseLiquidity"));
        assertEquals("2000.00", result.meta().get("inflowTotal"));
        assertEquals("1000.00", result.meta().get("outflowTotal"));
        assertEquals("2000.00", result.meta().get("endBalance")); // 1000 + 2000 - 1000
        assertEquals("1000.00", result.meta().get("minBalance"));
        assertEquals(1, result.produced().size());
        assertEquals("cashflow-report", result.produced().get(0).kind());
        assertTrue(result.meta().get("buckets").startsWith("[{\"start\":"));
    }

    @Test
    void noOpenInvoicesIsSkipped() {
        when(jdbc.queryForList(contains("bank_statements"), any(Object.class))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(tenantId), eq("OUTGOING"), any(), any(), any(), any(),
                any())).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(tenantId), eq("INCOMING"), any(), any(), any(), any(),
                any(), any())).thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine offenen Rechnungen"));
    }
}
