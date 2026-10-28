package de.buerokratiecopilot.agents.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;

class HrPayrollDataAgentTest {

    private JdbcTemplate jdbc;
    private AgentArtifactStore artifacts;
    private AgentTaskFactory tasks;
    private HrPayrollDataAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        artifacts = mock(AgentArtifactStore.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new HrPayrollDataAgent(jdbc, artifacts, tasks);
    }

    private static Map<String, Object> employee(String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("email", name.toLowerCase().replace(' ', '.') + "@example.de");
        row.put("display_name", name);
        row.put("status", "ACTIVE");
        row.put("membership_status", "ACTIVE");
        row.put("roles", "EMPLOYEE");
        return row;
    }

    @Test
    void collectsEmployeesAndHrDocumentsWithoutCalculatingPay() {
        when(jdbc.queryForList(contains("from memberships"), any(Object.class),
                any(Object.class))).thenReturn(List.of(employee("Anna Muster"),
                employee("Bernd Beispiel")));
        when(jdbc.queryForList(contains("from documents"), any(Object.class), any(Object.class),
                any(Object.class))).thenReturn(List.of(Map.of("code", "krankmeldung",
                "document_count", 2)));
        when(artifacts.store(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentArtifactStore.Artifact(UUID.randomUUID(),
                        "tenants/key/payroll.csv", "lohndaten.csv", "text/csv", 80, true));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("month", "2026-10", "exportFormat", "CSV")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("employees", "2")
                .containsEntry("absenceDocuments", "2")
                .containsEntry("exportFormat", "CSV");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("payroll-data", "task");
        assertThat(result.summary()).contains("Keine Beträge berechnet");
    }

    @Test
    void withoutEmployeesTheAgentSkips() {
        when(jdbc.queryForList(contains("from memberships"), any(Object.class),
                any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"), Map.of("month", "2026-10")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine aktiven Mitarbeiter");
        verifyNoInteractions(artifacts, tasks);
    }

    @Test
    void datevLodasIsNotPretended() {
        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("month", "2026-10", "exportFormat", "DATEV_LODAS")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht angebunden");
        verifyNoInteractions(jdbc, artifacts, tasks);
    }
}
