package de.buerokratiecopilot.agents.realestate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
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
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class TenantAdminAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final TenantAdminAgent agent = new TenantAdminAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void preparesAMoveInChecklistWithoutBookingMoney() {
        when(jdbc.query(contains("from contacts"), any(RowMapper.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of("Marta Beispiel"));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("propertyId", UUID.randomUUID().toString(),
                "tenantContactId", UUID.randomUUID().toString(),
                "changeType", "MOVE_IN", "effectiveDate", "2026-12-01",
                "depositAmount", "1500.00")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("changeType", "MOVE_IN")
                .containsEntry("steps", "5")
                .containsEntry("depositBooked", "false");
    }

    @Test
    void skipsAnUnknownTenantContact() {
        when(jdbc.query(contains("from contacts"), any(RowMapper.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of("propertyId", UUID.randomUUID().toString(),
                "tenantContactId", UUID.randomUUID().toString(),
                "changeType", "MOVE_IN", "effectiveDate", "2026-12-01")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht vorhanden");
    }

    @Test
    void moveOutChecklistMentionsDepositSettlement() {
        assertThat(TenantAdminAgent.checklist("MOVE_OUT", java.time.LocalDate.of(2026, 12, 1), null))
                .anyMatch(check -> check.title().contains("Kaution"));
    }
}
