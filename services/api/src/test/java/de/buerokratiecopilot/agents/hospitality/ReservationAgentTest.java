package de.buerokratiecopilot.agents.hospitality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
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

class ReservationAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final ReservationAgent agent = new ReservationAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void recordsARequestWithACalendarEntryButNoGuarantee() {
        when(jdbc.query(any(String.class), any(RowMapper.class), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of());
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        when(workspace.ensureEvent(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentWorkspace.EventRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("guestName", "Marta", "dateTime", "2026-12-01T19:00",
                "partySize", "4", "guestEmail", "marta@example.de")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("reservedSeats", "0")
                .containsEntry("status", "ANGEFRAGT");
        assertThat(result.summary()).contains("Keine Tischgarantie");
    }

    @Test
    void proposesAlternativesWhenCapacityIsExceeded() {
        UUID guest = UUID.randomUUID();
        when(jdbc.query(any(String.class), any(RowMapper.class), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of(18));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(guest, true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(guest, true));
        when(workspace.ensureEvent(any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentWorkspace.EventRef(guest, true));

        AgentResult result = agent.run(ctx(Map.of("guestName", "Marta", "dateTime", "2026-12-01T19:00",
                "partySize", "5", "capacity", "20")));

        assertThat(result.meta()).containsEntry("status", "ALTERNATIVE")
                .containsEntry("alternatives", "2");
        assertThat(result.summary()).contains("Keine Tischgarantie");
    }

    @Test
    void skipsAnAppointmentInThePast() {
        AgentResult result = agent.run(ctx(Map.of("guestName", "Marta", "dateTime", "2020-01-01T19:00",
                "partySize", "2")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Vergangenheit");
    }
}
