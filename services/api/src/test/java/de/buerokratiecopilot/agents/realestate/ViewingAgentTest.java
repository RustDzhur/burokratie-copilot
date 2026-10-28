package de.buerokratiecopilot.agents.realestate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.agents.core.AgentTestFixtures;
import de.buerokratiecopilot.calendar.CalendarEventService;

/** {@code realestate-viewing}: proposed slots, waiting list, calendar entries, confirmation tasks. */
class ViewingAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private JdbcTemplate jdbc;
    private CalendarEventService calendar;
    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private ViewingAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        calendar = mock(CalendarEventService.class);
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new ViewingAgent(jdbc, calendar, tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void proposesOneAppointmentPerSlotContactPair() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(jdbc.queryForList(contains("from contacts"), any(Object[].class)))
                .thenReturn(List.of(contact(first, "Erika Muster", "erika@example.de")),
                        List.of(contact(second, "Max Beispiel", "max@example.de")));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any()))
                .thenReturn(0);
        when(calendar.create(any(), any(), any(), any(), any()))
                .thenReturn(Map.of("id", UUID.randomUUID().toString()));
        when(tasks.createIfAbsent(any(), any()))
                .thenReturn(Optional.of(AgentTestFixtures.task(TENANT, "Bestätigung")));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("viewing-schedule"));

        AgentResult result = agent.run(context(Map.of(
                "propertyId", "OBJ-77",
                "interestedContacts", first + "," + second,
                "availableSlots", "[\"2026-11-03T10:00\", \"2026-11-03T11:30\"]",
                "viewingDurationMinutes", "45")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("assignments", "2")
                .containsEntry("waitingList", "0")
                .containsEntry("calendarEntries", "2")
                .containsEntry("tasksCreated", "2");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("viewing-schedule", "calendar-event", "task");
        verify(calendar, times(2)).create(any(), any(), any(), any(), any());
    }

    @Test
    void putsTheExtraContactOnTheWaitingList() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(jdbc.queryForList(contains("from contacts"), any(Object[].class)))
                .thenReturn(List.of(contact(first, "A", null)), List.of(contact(second, "B", null)),
                        List.of(contact(third, "C", null)));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any()))
                .thenReturn(1);
        when(tasks.createIfAbsent(any(), any()))
                .thenReturn(Optional.of(AgentTestFixtures.task(TENANT, "Warteliste")));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("viewing-schedule"));

        AgentResult result = agent.run(context(Map.of(
                "propertyId", "OBJ-77",
                "interestedContacts", first + "," + second + "," + third,
                "availableSlots", "[\"2026-11-03T10:00\", \"2026-11-03T11:00\"]")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("assignments", "2")
                .containsEntry("waitingList", "1")
                .containsEntry("calendarEntries", "0");
    }

    @Test
    void refusesWithoutUsableSlots() {
        AgentResult result = agent.run(context(Map.of("propertyId", "OBJ-77",
                "interestedContacts", "Erika", "availableSlots", "[]")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_SLOTS");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesMalformedSlotJson() {
        AgentResult result = agent.run(context(Map.of("propertyId", "OBJ-77",
                "interestedContacts", "Erika", "availableSlots", "keine-liste")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "INVALID_INPUT");
    }

    @Test
    void refusesWithoutAProperty() {
        AgentResult result = agent.run(context(Map.of("interestedContacts", "Erika")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_INPUT");
    }

    @Test
    void slotParsingAcceptsStringsAndObjects() {
        assertThat(ViewingAgent.slots("[\"2026-11-03T10:00\", {\"start\": \"2026-11-03T09:00\"}]"))
                .containsExactly(LocalDateTime.of(2026, 11, 3, 9, 0),
                        LocalDateTime.of(2026, 11, 3, 10, 0));
        assertThat(ViewingAgent.slots("[]")).isEmpty();
        assertThat(ViewingAgent.slots("kein json")).isNull();
    }

    private static Map<String, Object> contact(UUID id, String name, String email) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("display_name", name);
        row.put("email", email);
        return row;
    }
}
