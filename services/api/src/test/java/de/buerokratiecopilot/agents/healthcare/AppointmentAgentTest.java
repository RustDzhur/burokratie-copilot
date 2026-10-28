package de.buerokratiecopilot.agents.healthcare;

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

class AppointmentAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final AppointmentAgent agent = new AppointmentAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void flagsAnEmergencyImmediatelyWithoutConfirmingASlot() {
        when(jdbc.query(contains("from contacts"), any(RowMapper.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of("Marta Beispiel"));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        AgentResult result = agent.run(ctx(Map.of("appointmentType", "Akuttermin",
                "patientContactId", UUID.randomUUID().toString(), "urgency", "EMERGENCY")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("urgency", "EMERGENCY")
                .containsEntry("confirmed", "false")
                .containsEntry("notified", "true");
    }

    @Test
    void createsATentativeCalendarEntryForAConcretePreferredTime() {
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        when(workspace.ensureEvent(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentWorkspace.EventRef(UUID.randomUUID(), true));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("appointmentType", "Kontrolle",
                "preferredPeriod", "2026-12-01T09:30")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("calendarEventCreated", "true");
    }

    @Test
    void rejectsAnUnknownUrgency() {
        AgentResult result = agent.run(ctx(Map.of("appointmentType", "Kontrolle",
                "urgency", "SOFORT")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("unbekannte Dringlichkeit");
    }
}
