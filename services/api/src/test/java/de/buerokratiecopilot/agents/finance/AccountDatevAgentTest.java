package de.buerokratiecopilot.agents.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.shared.settings.SystemSettingRepository;

class AccountDatevAgentTest {

    private JdbcTemplate jdbc;
    private SystemSettingRepository settings;
    private AccountDatevAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        settings = mock(SystemSettingRepository.class);
        agent = new AccountDatevAgent(jdbc, settings);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    private static Map<String, Object> invoiceRow(UUID id, String net, String tax, String gross) {
        return Map.of("id", id, "invoice_number", "RE-2026-0412", "net_amount", new BigDecimal(net),
                "tax_amount", new BigDecimal(tax), "gross_amount", new BigDecimal(gross));
    }

    private static Map<String, Object> lineRow(String net, String tax, String gross, String category) {
        return Map.of("line_number", 1, "description", "Softwarelizenz", "net_amount",
                new BigDecimal(net), "tax_amount", new BigDecimal(tax), "gross_amount",
                new BigDecimal(gross), "cost_category", category);
    }

    @Test
    void preparesPostingsAndMarksExported() {
        UUID invoiceId = UUID.randomUUID();
        when(jdbc.queryForList(contains("from invoices"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(invoiceRow(invoiceId, "100.00", "19.00", "119.00")));
        when(jdbc.queryForList(contains("invoice_lines"), any(Object.class)))
                .thenReturn(List.of(lineRow("100.00", "19.00", "119.00", "it")));
        when(jdbc.update(contains("metadata || jsonb_build_object"), any(), any(), any(), any()))
                .thenReturn(1);
        when(settings.findByTenantIdAndSettingKey(tenantId, "finance.datev.account.it"))
                .thenReturn(Optional.empty());
        when(settings.findByTenantIdIsNullAndSettingKey("finance.datev.account.it"))
                .thenReturn(Optional.empty());

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("exported"));
        assertEquals("3", result.meta().get("postings")); // 4920 Soll, 1576 Soll, 1600 Haben
        assertEquals("true", result.meta().get("controlOk"));
        assertEquals("119.00", result.meta().get("totalDebit"));
        assertEquals(1, result.produced().size());
        assertEquals("datev-posting", result.produced().get(0).kind());
        verify(jdbc).update(contains("metadata || jsonb_build_object"), any(), any(), any(), any());
    }

    @Test
    void holdsBackInvoiceWhoseSumsDoNotAddUp() {
        UUID invoiceId = UUID.randomUUID();
        when(jdbc.queryForList(contains("from invoices"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(invoiceRow(invoiceId, "100.00", "19.00", "119.00")));
        // Header says 100 net, the line only 90 — a control-sum mismatch must never be exported.
        when(jdbc.queryForList(contains("invoice_lines"), any(Object.class)))
                .thenReturn(List.of(lineRow("90.00", "19.00", "119.00", "it")));

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertEquals("0", result.meta().get("exported"));
        assertEquals("1", result.meta().get("failed"));
        verify(jdbc, never()).update(contains("metadata || jsonb_build_object"), any(), any(), any(),
                any());
    }

    @Test
    void noEligibleInvoicesIsSkipped() {
        when(jdbc.queryForList(contains("from invoices"), any(Object.class), any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine geprüften"));
    }
}
