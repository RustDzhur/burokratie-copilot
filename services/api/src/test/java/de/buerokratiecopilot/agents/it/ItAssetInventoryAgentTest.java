package de.buerokratiecopilot.agents.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class ItAssetInventoryAgentTest {

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private ItAssetInventoryAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new ItAssetInventoryAgent(jdbc, tasks);
    }

    @Test
    void findsDevicesWithoutOwnerAndWaitingTooLong() {
        Instant registered = Instant.parse("2026-01-01T09:00:00Z");
        ItAssetInventoryAgent.DeviceRow device = new ItAssetInventoryAgent.DeviceRow(
                UUID.randomUUID(), "SCAN-1", "Scanner Empfang", "SCANNER", "PENDING", null,
                registered, null, null);
        when(jdbc.query(contains("from devices"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(device));
        when(jdbc.query(contains("from contracts"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of());
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("devices", "1")
                .containsEntry("findings", "2")
                .containsEntry("noOwner", "1")
                .containsEntry("pendingTooLong", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("asset-finding", "task");
    }

    @Test
    void withoutAssetsTheAgentSkips() {
        when(jdbc.query(contains("from devices"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of());
        when(jdbc.query(contains("from contracts"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine IT-Assets");
        verifyNoInteractions(tasks);
    }
}
