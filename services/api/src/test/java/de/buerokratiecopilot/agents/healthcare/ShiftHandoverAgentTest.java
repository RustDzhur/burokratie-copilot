package de.buerokratiecopilot.agents.healthcare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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

/** {@code healthcare-shift-handover}: organisational protocol only, medical items rejected. */
class ShiftHandoverAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private ShiftHandoverAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new ShiftHandoverAgent(jdbc, tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void handsOverTheOrganisationalItemsAndRejectsTheDiagnosis() {
        when(tasks.createIfAbsent(any(), any()))
                .thenReturn(Optional.of(AgentTestFixtures.task(TENANT, "Übergabe")));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("shift-handover"));
        String items = """
                [
                  {"patientRef": "Zi. 12", "category": "ORGANISATORISCH",
                   "item": "Blutentnahme angemeldet", "responsible": "Pflege A"},
                  {"patientRef": "Zi. 13", "diagnose": "Verdacht", "item": "Kontrolle"}
                ]
                """;

        AgentResult result = agent.run(context(Map.of("wardId", "STATION-1",
                "shiftDate", "2026-10-27", "openItems", items)));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("items", "1")
                .containsEntry("rejected", "1")
                .containsEntry("groups", "1")
                .containsEntry("tasksCreated", "1")
                .containsEntry("noDiagnosis", "true");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("shift-handover", "task");
        assertThat(result.summary()).contains("nicht übernommen");
    }

    @Test
    void buildsAnExplicitlyEmptyProtocolWhenNoItemsArePassed() {
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("shift-handover"));

        AgentResult result = agent.run(context(Map.of("wardId", "STATION-1",
                "shiftDate", "2026-10-27")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("items", "0")
                .containsEntry("tasksCreated", "0");
        assertThat(result.summary()).contains("0 Punkt");
        verify(tasks, never()).createIfAbsent(any(), any());
    }

    @Test
    void refusesAProtocolThatWouldConsistOnlyOfDiagnoses() {
        AgentResult result = agent.run(context(Map.of("wardId", "STATION-1",
                "shiftDate", "2026-10-27",
                "openItems", "[{\"diagnose\": \"X\", \"item\": \"Kontrolle\"}]")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "MEDICAL_DATA_REJECTED");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesMalformedOpenItems() {
        AgentResult result = agent.run(context(Map.of("wardId", "STATION-1",
                "shiftDate", "2026-10-27", "openItems", "kein json")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "INVALID_INPUT");
    }

    @Test
    void refusesWithoutAWard() {
        AgentResult result = agent.run(context(Map.of("shiftDate", "2026-10-27")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_INPUT");
    }

    @Test
    void parserAcceptsTextItemsAndNormalisesUnknownCategories() {
        ShiftHandoverAgent.Parsed parsed = ShiftHandoverAgent.parseItems(
                "[\"Zimmer 3 lüften\", {\"item\": \"Essen anmelden\", \"category\": \"Küche\"}]");

        assertThat(parsed).isNotNull();
        assertThat(parsed.items()).hasSize(2);
        assertThat(parsed.items().getFirst().category()).isEqualTo("ORGANISATORISCH");
        assertThat(parsed.items().get(1).category()).isEqualTo("SONSTIGES");
        assertThat(parsed.notes()).anyMatch(note -> note.contains("Küche"));
        assertThat(ShiftHandoverAgent.parseItems("[]")).isNotNull();
        assertThat(ShiftHandoverAgent.parseItems("nope")).isNull();
    }
}
