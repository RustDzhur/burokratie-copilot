package de.buerokratiecopilot.agents.hospitality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.agents.core.AgentTestFixtures;

/** {@code hospitality-event-plan}: staffing, checklist and unconfirmed capacity. */
class EventPlanAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private EventPlanAgent agent;

    @BeforeEach
    void setUp() {
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new EventPlanAgent(tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void plansTheEventAndCreatesOneTaskPerChecklistItem() {
        when(tasks.createIfAbsent(any(), any()))
                .thenReturn(Optional.of(AgentTestFixtures.task(TENANT, "Veranstaltung")));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("event-plan"));

        AgentResult result = agent.run(context(Map.of(
                "eventName", "Jahresempfang",
                "date", "2026-12-10",
                "expectedGuests", "30",
                "roomId", "RAUM-1",
                "cateringNeeds", "Buffet, vegetarisch",
                "budget", "4500.00")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("guests", "30")
                .containsEntry("staffTotal", "5")
                .containsEntry("tasksCreated", "7")
                .containsEntry("capacityConfirmed", "false");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("event-plan", "task");
        assertThat(result.summary()).contains("nicht bestätigt");
    }

    @Test
    void refusesWithoutAGuestCount() {
        AgentResult result = agent.run(context(Map.of("eventName", "Jahresempfang",
                "date", "2026-12-10", "expectedGuests", "0")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "MISSING_REQUIRED");
        verify(tasks, never()).createIfAbsent(any(), any());
    }

    @Test
    void refusesWithoutAnEventName() {
        AgentResult result = agent.run(context(Map.of("date", "2026-12-10",
                "expectedGuests", "30")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_INPUT");
    }

    @Test
    void thePlanningRuleCoversStaffingTimelineAndGaps() {
        EventPlanAgent.Plan plan = EventPlanAgent.plan(30, null, null, null);

        assertThat(plan.serviceStaff()).isEqualTo(3);
        assertThat(plan.kitchenStaff()).isEqualTo(2);
        assertThat(plan.staffTotal()).isEqualTo(5);
        assertThat(plan.timeline()).hasSize(7);
        assertThat(plan.checklist()).hasSize(7);
        assertThat(plan.gaps()).anyMatch(gap -> gap.toLowerCase().contains("kapazität"))
                .anyMatch(gap -> gap.contains("Budget"))
                .anyMatch(gap -> gap.contains("Catering"));
        assertThat(plan.budgetPerGuest()).isNull();

        EventPlanAgent.Plan budgeted = EventPlanAgent.plan(10, "RAUM-1", "Menü",
                new BigDecimal("500.00"));
        assertThat(budgeted.budgetPerGuest()).isEqualByComparingTo("50.00");
    }
}
