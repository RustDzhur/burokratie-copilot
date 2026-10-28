package de.buerokratiecopilot.agents.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import de.buerokratiecopilot.banking.PaymentMatchingService;

class BankingReconcileAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PaymentMatchingService matching = mock(PaymentMatchingService.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final BankingReconcileAgent agent = new BankingReconcileAgent(jdbc, matching, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    private Map<String, Object> row(UUID id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("booking_date", java.sql.Date.valueOf(LocalDate.of(2026, 11, 2)));
        row.put("value_date", null);
        row.put("amount", new BigDecimal("119.00"));
        row.put("currency", "EUR");
        row.put("counterparty_name", "Beispiel GmbH");
        row.put("counterparty_iban", null);
        row.put("purpose", "Rechnung RE-2026-001");
        row.put("reference", "RE-2026-001");
        row.put("end_to_end_id", null);
        row.put("transaction_type", null);
        row.put("external_id", null);
        return row;
    }

    @Test
    void recordsAUniqueCandidateAsProposalAndNeverBooksIt() {
        UUID transaction = UUID.randomUUID();
        UUID invoice = UUID.randomUUID();
        when(jdbc.queryForList(contains("from bank_transactions"), any(Object[].class)))
                .thenReturn(List.of(row(transaction)));
        when(matching.proposeMatches(eq(tenant), any())).thenReturn(List.of(
                new PaymentMatchingService.Proposal(transaction, invoice, new BigDecimal("119.00"),
                        "Die Zahlung nennt die Rechnungsnummer", 0.95)));
        when(jdbc.update(contains("insert into payment_matches"), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(jdbc.update(contains("update bank_transactions"), any(), any())).thenReturn(1);
        UUID taskId = UUID.randomUUID();
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentWorkspace.TaskRef(taskId, true));

        AgentResult result = agent.run(ctx(Map.of("periodFrom", "2026-11-01",
                "periodTo", "2026-11-30")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("uniqueCandidates", "1")
                .containsEntry("ambiguous", "0");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("bank-reconciliation", "task");
        verify(jdbc, never()).update(contains("insert into payments"), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void skipsWhenNoUnmatchedTransactionIsInThePeriod() {
        when(jdbc.queryForList(contains("from bank_transactions"), any(Object[].class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of("periodFrom", "2026-11-01",
                "periodTo", "2026-11-30")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine unzugeordneten");
        verify(matching, never()).proposeMatches(any(), any());
    }
}
