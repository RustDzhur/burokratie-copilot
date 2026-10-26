package de.buerokratiecopilot.agents.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.banking.PaymentMatchingService;

class PaymentMatchAgentTest {

    private JdbcTemplate jdbc;
    private PaymentMatchingService matching;
    private PaymentMatchAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        matching = mock(PaymentMatchingService.class);
        agent = new PaymentMatchAgent(jdbc, matching);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    private static Map<String, Object> creditRow(UUID id, String amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("booking_date", java.sql.Date.valueOf(LocalDate.now().minusDays(1)));
        row.put("value_date", null);
        row.put("amount", new BigDecimal(amount));
        row.put("currency", "EUR");
        row.put("counterparty_name", "Beispiel Handels GmbH");
        row.put("counterparty_iban", null);
        row.put("purpose", "Rechnung A-2026-027");
        row.put("reference", "A-2026-027");
        row.put("end_to_end_id", null);
        row.put("transaction_type", null);
        row.put("external_id", null);
        return row;
    }

    @Test
    void proposesCandidatesForUnmatchedCredits() {
        UUID transactionId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        when(jdbc.queryForList(contains("bank_transactions"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(creditRow(transactionId, "119.00")));
        when(matching.proposeMatches(eq(tenantId), any())).thenReturn(List.of(
                new PaymentMatchingService.Proposal(transactionId, invoiceId, new BigDecimal("119.00"),
                        "Die Zahlung nennt die Rechnungsnummer A-2026-027", 0.95)));
        when(jdbc.update(contains("insert into payment_matches"), any(), any(), any(), any(), any(),
                any(), any())).thenReturn(1);
        when(jdbc.update(contains("update bank_transactions"), any(Object.class), any(Object.class))).thenReturn(1);

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("proposals"));
        assertEquals("1", result.meta().get("withCandidates"));
        assertEquals(1, result.produced().size());
        assertEquals("payment-proposal", result.produced().get(0).kind());
        assertEquals(invoiceId.toString(), result.produced().get(0).ref());
        verify(jdbc).update(contains("insert into payment_matches"), any(), any(), any(), any(), any(),
                any(), any());
        verify(jdbc).update(contains("update bank_transactions"), any(Object.class), any(Object.class));
    }

    @Test
    void noCandidatesIsSkipped() {
        when(jdbc.queryForList(contains("bank_transactions"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(creditRow(UUID.randomUUID(), "500.00")));
        when(matching.proposeMatches(eq(tenantId), any())).thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine Kandidaten"));
        verify(jdbc, never()).update(contains("insert into payment_matches"), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void noUnmatchedTransactionsIsSkipped() {
        when(jdbc.queryForList(contains("bank_transactions"), any(Object.class), any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine unzugeordneten Bankeingänge"));
    }
}
