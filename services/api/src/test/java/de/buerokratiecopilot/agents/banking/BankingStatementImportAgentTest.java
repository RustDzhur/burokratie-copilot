package de.buerokratiecopilot.agents.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.banking.PaymentMatchingService;

class BankingStatementImportAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PaymentMatchingService matching = mock(PaymentMatchingService.class);
    private final BankingStatementImportAgent agent = new BankingStatementImportAgent(jdbc, matching);
    private final UUID tenant = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void importsAValidCamtStatementThroughTheBankingModule() throws Exception {
        when(jdbc.query(contains("financial_accounts"), any(RowMapper.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of(account));
        UUID statementId = UUID.randomUUID();
        when(matching.importCamt(eq(tenant), eq(account), any(byte[].class), any()))
                .thenReturn(new PaymentMatchingService.ImportResult(statementId, 3, 1, 2, 0));

        AgentResult result = agent.run(ctx(Map.of("accountId", account.toString(),
                "format", "CAMT053", "content", "<Document><camt.053/></Document>")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("transactions", "3")
                .containsEntry("duplicatesSkipped", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("bank_statement");
        verify(matching).importCamt(eq(tenant), eq(account), any(byte[].class), any());
    }

    @Test
    void skipsHonestlyWithoutStatementContent() throws Exception {
        when(jdbc.query(contains("financial_accounts"), any(RowMapper.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of(account));

        AgentResult result = agent.run(ctx(Map.of("accountId", account.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Auszugsdaten");
        verify(matching, never()).importCamt(any(), any(), any(byte[].class), any());
    }

    @Test
    void readsACsvRowWithGermanHeaders() {
        List<Map<String, String>> rows = BankingStatementImportAgent.parseCsv(
                "Buchungstag;Betrag;Auftraggeber;Verwendungszweck;Referenz\n"
                        + "01.11.2026;-1.234,56;Stadtwerke;Abschlag Strom;VERTRAG-1\n");

        assertThat(rows).hasSize(1);
        BankingStatementImportAgent.CsvTransaction transaction =
                BankingStatementImportAgent.CsvTransaction.from(rows.getFirst());
        assertThat(transaction).isNotNull();
        assertThat(transaction.amount().toPlainString()).isEqualTo("-1234.56");
        assertThat(transaction.counterparty()).isEqualTo("Stadtwerke");
    }
}
