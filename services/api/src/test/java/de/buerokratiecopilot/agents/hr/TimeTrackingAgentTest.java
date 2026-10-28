package de.buerokratiecopilot.agents.hr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class TimeTrackingAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final TimeTrackingAgent agent = new TimeTrackingAgent(new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void reportsDailyOvertimeAndCreatesACorrectionTask() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String entries = """
                [{"employeeId":"%s","employeeName":"Marta","date":"2026-11-02",
                  "start":"08:00","end":"20:00","breakMinutes":30}]
                """.formatted(UUID.randomUUID());

        AgentResult result = agent.run(ctx(Map.of("periodFrom", "2026-11-02",
                "periodTo", "2026-11-08", "timeEntries", entries)));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("employeesWithFlags", "1")
                .containsEntry("tasksCreated", "1")
                .containsEntry("autoCorrected", "false");
    }

    @Test
    void skipsWithoutTimeEntries() {
        AgentResult result = agent.run(ctx(Map.of("periodFrom", "2026-11-02",
                "periodTo", "2026-11-08")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Zeiteinträge");
    }

    @Test
    void flagsShortBreaksAndWeeklyLimit() {
        List<TimeTrackingAgent.EmployeeReport> reports = TimeTrackingAgent.analyse(List.of(
                new TimeTrackingAgent.Entry(null, "Marta", LocalDate.of(2026, 11, 2),
                        LocalTime.of(8, 0), LocalTime.of(17, 0), 10),
                new TimeTrackingAgent.Entry(null, "Marta", LocalDate.of(2026, 11, 3),
                        LocalTime.of(8, 0), LocalTime.of(22, 0), 30),
                new TimeTrackingAgent.Entry(null, "Marta", LocalDate.of(2026, 11, 4),
                        LocalTime.of(8, 0), LocalTime.of(22, 0), 30),
                new TimeTrackingAgent.Entry(null, "Marta", LocalDate.of(2026, 11, 5),
                        LocalTime.of(8, 0), LocalTime.of(22, 0), 30),
                new TimeTrackingAgent.Entry(null, "Marta", LocalDate.of(2026, 11, 6),
                        LocalTime.of(8, 0), LocalTime.of(22, 0), 30)),
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8), 10, 30);

        assertThat(reports.getFirst().flags()).anyMatch(f -> f.contains("Pause"));
        assertThat(reports.getFirst().flags()).anyMatch(f -> f.contains("Tagesgrenze"));
        assertThat(reports.getFirst().flags()).anyMatch(f -> f.contains("Woche"));
    }
}
