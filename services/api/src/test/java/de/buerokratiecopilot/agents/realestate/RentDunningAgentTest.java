package de.buerokratiecopilot.agents.realestate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
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
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.agents.core.AgentTestFixtures;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService.DraftRequest;

/** {@code realestate-rent-dunning}: staged draft, task and no sending. */
class RentDunningAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private JdbcTemplate jdbc;
    private OutboundMessageService outbound;
    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private RentDunningAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        outbound = mock(OutboundMessageService.class);
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new RentDunningAgent(jdbc, outbound, tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void draftsTheNextStageForTheOverdueRentContract() {
        UUID contractId = UUID.randomUUID();
        when(jdbc.queryForList(contains("from invoices i"), any(Object[].class)))
                .thenReturn(List.of(invoice(contractId, "M-1", "1200.00", TODAY.minusDays(10), "erika@example.de")));
        doReturn(List.of()).when(jdbc).query(anyString(), any(RowMapper.class), any(), any());
        when(outbound.draft(any(DraftRequest.class))).thenReturn(UUID.randomUUID());
        when(tasks.createIfAbsent(any(), any()))
                .thenReturn(Optional.of(AgentTestFixtures.task(TENANT, "Mietrückstand")));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("rent-dunning"));

        AgentResult result = agent.run(context(Map.of("periodFrom", "2026-01-01",
                "periodTo", "2026-10-31")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("contracts", "1")
                .containsEntry("drafts", "1")
                .containsEntry("tasksCreated", "1")
                .containsEntry("sendsNothing", "true")
                .containsEntry("requiresApproval", "true");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("rent-dunning", "rent-dunning-draft", "task");

        ArgumentCaptor<DraftRequest> captor = ArgumentCaptor.forClass(DraftRequest.class);
        verify(outbound).draft(captor.capture());
        assertThat(captor.getValue().idempotencyKey())
                .isEqualTo("rent-dunning-v1:" + contractId + ":L1");
        assertThat(captor.getValue().toAddresses()).containsExactly("erika@example.de");
        assertThat(captor.getValue().bodyText()).contains("M-1").contains("1.200,00");
    }

    @Test
    void createsATaskEvenWithoutAnEmailButNoLetter() {
        UUID contractId = UUID.randomUUID();
        when(jdbc.queryForList(contains("from invoices i"), any(Object[].class)))
                .thenReturn(List.of(invoice(contractId, "M-2", "900.00", TODAY.minusDays(9), null)));
        when(tasks.createIfAbsent(any(), any()))
                .thenReturn(Optional.of(AgentTestFixtures.task(TENANT, "Mietrückstand")));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("rent-dunning"));

        AgentResult result = agent.run(context(Map.of("periodFrom", "2026-01-01",
                "periodTo", "2026-10-31")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("drafts", "0")
                .containsEntry("noEmail", "1");
        verify(outbound, never()).draft(any());
    }

    @Test
    void refusesWhenNoRentPaymentIsOverdue() {
        when(jdbc.queryForList(contains("from invoices i"), any(Object[].class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(context(Map.of("periodFrom", "2026-01-01",
                "periodTo", "2026-10-31")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_ARREARS");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesWithoutAPeriod() {
        AgentResult result = agent.run(context(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_INPUT");
    }

    @Test
    void stagesGrowWithTheDaysPastDueAndStopAtTheConfiguredLevel() {
        assertThat(RentDunningAgent.levelFor(4, 5, 3)).isZero();
        assertThat(RentDunningAgent.levelFor(5, 5, 3)).isEqualTo(1);
        assertThat(RentDunningAgent.levelFor(18, 5, 3)).isEqualTo(1);
        assertThat(RentDunningAgent.levelFor(19, 5, 3)).isEqualTo(2);
        assertThat(RentDunningAgent.levelFor(60, 5, 3)).isEqualTo(3);
    }

    private static Map<String, Object> invoice(UUID contractId, String number, String open,
                                               LocalDate dueDate, String email) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("invoice_number", number);
        row.put("gross_amount", new BigDecimal(open));
        row.put("paid_amount", BigDecimal.ZERO);
        row.put("due_date", java.sql.Date.valueOf(dueDate));
        row.put("contract_id", contractId);
        row.put("contract_title", "Miete Wohnung");
        row.put("contract_number", "MV-100");
        row.put("contact_name", "Erika Muster");
        row.put("email", email);
        return row;
    }
}
