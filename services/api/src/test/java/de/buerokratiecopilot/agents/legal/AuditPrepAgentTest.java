package de.buerokratiecopilot.agents.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentTestFixtures;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/** {@code legal-audit-prep}: checklist from counts, gaps with tasks, no audit-readiness verdict. */
class AuditPrepAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private AuditPrepAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new AuditPrepAgent(jdbc, tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void reportsMissingEvidenceAndCreatesAnOwnedTask() {
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(counts(2, 0, 5, 0, 0, 0));
        Task task = new Task(UUID.randomUUID(), TENANT, "Audit-Nachweis fehlt: Freigaben");
        when(tasks.createIfAbsent(any(), any())).thenReturn(Optional.of(task));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("audit-folder"));

        AgentResult result = agent.run(context(Map.of("periodFrom", "2026-01-01",
                "periodTo", "2026-03-31", "auditType", "internal", "auditorName", "KPMG")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("auditType", "INTERNAL")
                .containsEntry("checklistItems", "6")
                .containsEntry("gaps", "1")
                .containsEntry("tasksCreated", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("audit-folder", "task");
        verify(tasks).createIfAbsent(any(), any());
    }

    @Test
    void refusesWithoutAPeriod() {
        AgentResult result = agent.run(context(Map.of("auditType", "INTERNAL")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_INPUT");
        verify(jdbc, never()).queryForMap(anyString(), any(Object[].class));
    }

    @Test
    void refusesAnInvalidPeriod() {
        AgentResult result = agent.run(context(Map.of("periodFrom", "01.13.2026",
                "periodTo", "2026-03-31")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "INVALID_PERIOD");
    }

    @Test
    void checklistWithoutEvidenceListsEveryMissingProof() {
        List<AuditPrepAgent.Evidence> checklist = AuditPrepAgent.checklist(
                Map.of("policies", 0L, "approvals", 0L, "audit_events", 0L, "open_tasks", 4L,
                        "pending_reviews", 2L, "legal_hold", 1L));

        assertThat(checklist).hasSize(6);
        assertThat(checklist.stream().filter(item -> !item.present() && !item.informational()))
                .extracting(AuditPrepAgent.Evidence::key)
                .containsExactly("POLICIES", "APPROVALS", "AUDIT_LOG", "OPEN_MEASURES",
                        "PENDING_REVIEWS");
        assertThat(checklist.getLast().informational()).isTrue();
        assertThat(checklist.getLast().present()).isTrue();
    }

    private static Map<String, Object> counts(long policies, long approvals, long auditEvents,
                                              long openTasks, long pending, long legalHold) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("policies", policies);
        counts.put("approvals", approvals);
        counts.put("audit_events", auditEvents);
        counts.put("open_tasks", openTasks);
        counts.put("pending_reviews", pending);
        counts.put("legal_hold", legalHold);
        return counts;
    }
}
