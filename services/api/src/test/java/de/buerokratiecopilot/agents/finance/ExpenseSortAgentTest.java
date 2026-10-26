package de.buerokratiecopilot.agents.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.invoices.InvoiceLineRepository;
import de.buerokratiecopilot.invoices.domain.InvoiceLine;

class ExpenseSortAgentTest {

    private JdbcTemplate jdbc;
    private InvoiceLineRepository lines;
    private ExpenseSortAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        lines = mock(InvoiceLineRepository.class);
        agent = new ExpenseSortAgent(jdbc, lines);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    private static InvoiceLine line(UUID invoiceId, String description) {
        return new InvoiceLine(UUID.randomUUID(), UUID.randomUUID(), invoiceId, 1, description);
    }

    @Test
    void assignsCategoriesByKeywords() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceLine laptop = line(invoiceId, "Laptop Lenovo ThinkPad");
        InvoiceLine hotel = line(invoiceId, "Hotel Berlin Übernachtung 2 Nächte");
        InvoiceLine unknown = line(invoiceId, "Sonstige Ausgabe");
        when(jdbc.queryForList(contains("from invoices"), eq(UUID.class), any(), any()))
                .thenReturn(List.of(invoiceId));
        when(lines.findByInvoiceIdOrderByLineNumberAsc(invoiceId))
                .thenReturn(List.of(laptop, hotel, unknown));
        when(lines.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        assertEquals("it", laptop.getCostCategory());
        assertEquals("travel", hotel.getCostCategory());
        assertNull(unknown.getCostCategory());
        assertEquals("2", result.meta().get("categorized"));
        assertEquals("1", result.meta().get("unmatched"));
        assertEquals(2, result.produced().size());
        assertEquals("expense-categorization", result.produced().get(0).kind());
    }

    @Test
    void keepsExistingCategoryWithoutOverwrite() {
        UUID invoiceId = UUID.randomUUID();
        InvoiceLine rent = line(invoiceId, "Büromiete März");
        rent.setCostCategory("rent");
        when(jdbc.queryForList(contains("from invoices"), eq(UUID.class), any(), any()))
                .thenReturn(List.of(invoiceId));
        when(lines.findByInvoiceIdOrderByLineNumberAsc(invoiceId)).thenReturn(List.of(rent));

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok()); // nothing categorized, honestly reported
        assertEquals("rent", rent.getCostCategory());
        verify(lines, never()).save(any());
    }

    @Test
    void noInvoicesIsSkipped() {
        when(jdbc.queryForList(contains("from invoices"), eq(UUID.class), any(), any()))
                .thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine eingehenden Rechnungen"));
    }
}
