package de.buerokratiecopilot.agents.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService.DraftRequest;

class DunningAgentTest {

    private JdbcTemplate jdbc;
    private OutboundMessageService outbound;
    private DunningAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        outbound = mock(OutboundMessageService.class);
        agent = new DunningAgent(jdbc, outbound);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    private static Map<String, Object> overdueRow(String email, int daysOverdue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("invoice_number", "A-2026-027");
        row.put("gross_amount", new BigDecimal("1785.00"));
        row.put("paid_amount", new BigDecimal("0.00"));
        row.put("due_date", java.sql.Date.valueOf(LocalDate.now().minusDays(daysOverdue)));
        row.put("customer_id", UUID.randomUUID());
        row.put("email", email);
        row.put("customer_name", "Beispiel Handels GmbH");
        return row;
    }

    @Test
    void draftsDunningLetterForOverdueCustomer() {
        when(jdbc.queryForList(contains("left join customers"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(overdueRow("rechnung@kunde.de", 30)));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
        when(outbound.draft(any(DraftRequest.class))).thenReturn(UUID.randomUUID());

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("created"));
        assertEquals("1", result.meta().get("coveredInvoices"));
        assertEquals(1, result.produced().size());
        assertEquals("dunning-draft", result.produced().get(0).kind());

        ArgumentCaptor<DraftRequest> captor = ArgumentCaptor.forClass(DraftRequest.class);
        verify(outbound).draft(captor.capture());
        DraftRequest request = captor.getValue();
        assertEquals("EMAIL", request.channel());
        assertEquals(List.of("rechnung@kunde.de"), request.toAddresses());
        assertTrue(request.idempotencyKey().startsWith("dunning-v1:"));
        assertTrue(request.subject().contains("Mahnung"));
        assertTrue(request.bodyText().contains("A-2026-027"));
    }

    @Test
    void noOverdueInvoicesIsSkipped() {
        when(jdbc.queryForList(contains("left join customers"), any(Object.class), any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine überfälligen"));
    }

    @Test
    void customerWithoutEmailIsSkippedWithoutDraft() {
        when(jdbc.queryForList(contains("left join customers"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(overdueRow(null, 30)));

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertEquals("1", result.meta().get("noEmail"));
        verify(outbound, never()).draft(any());
    }

    @Test
    void alreadyDraftedIsSkipped() {
        when(jdbc.queryForList(contains("left join customers"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(overdueRow("rechnung@kunde.de", 30)));
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of(UUID.randomUUID()));

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine neuen Entwürfe"));
        verify(outbound, never()).draft(any());
    }
}
