package de.buerokratiecopilot.agents.healthcare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.agents.core.AgentTestFixtures;

/** {@code healthcare-billing}: documented positions only, no estimated amounts, review task. */
class HealthcareBillingAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private HealthcareBillingAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new HealthcareBillingAgent(jdbc, tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void acceptsOnlyDocumentedPositionsAndPreparesTheReview() {
        when(jdbc.queryForList(contains("from contacts"), any(Object[].class)))
                .thenReturn(List.of(patient()));
        when(jdbc.queryForList(contains("from documents"), any(Object[].class)))
                .thenReturn(List.of(document("GOÄ 5 sowie Position 250 am 20.10.2026")));
        when(tasks.createIfAbsent(any(), any()))
                .thenReturn(Optional.of(AgentTestFixtures.task(TENANT, "Abrechnung prüfen")));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("healthcare-billing"));

        AgentResult result = agent.run(context(Map.of(
                "patientContactId", PATIENT.toString(),
                "treatmentDate", "2026-10-20",
                "serviceCodes", "5,250",
                "feeSchedule", "GOA",
                "insurer", "AOK")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("positions", "2")
                .containsEntry("rejected", "0")
                .containsEntry("documents", "1")
                .containsEntry("amountsAvailable", "false")
                .containsEntry("requiresReview", "true")
                .containsEntry("tasksCreated", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("healthcare-billing", "task");
        assertThat(result.summary()).contains("nicht hinterlegt");
    }

    @Test
    void rejectsUndocumentedPositionsInsteadOfEstimatingThem() {
        when(jdbc.queryForList(contains("from contacts"), any(Object[].class)))
                .thenReturn(List.of(patient()));
        when(jdbc.queryForList(contains("from documents"), any(Object[].class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(context(Map.of(
                "patientContactId", PATIENT.toString(),
                "treatmentDate", "2026-10-20",
                "serviceCodes", "5,250",
                "feeSchedule", "GOÄ")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_DOCUMENTATION");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
        verify(tasks, never()).createIfAbsent(any(), any());
    }

    @Test
    void refusesAnUnknownPatient() {
        when(jdbc.queryForList(contains("from contacts"), any(Object[].class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(context(Map.of(
                "patientContactId", PATIENT.toString(),
                "treatmentDate", "2026-10-20",
                "serviceCodes", "5",
                "feeSchedule", "GOÄ")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NOT_FOUND");
    }

    @Test
    void refusesWithoutAPatient() {
        AgentResult result = agent.run(context(Map.of("treatmentDate", "2026-10-20",
                "serviceCodes", "5")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_INPUT");
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void aSingleDigitCodeNeedsAFeeScheduleMarkerToCountAsEvidence() {
        assertThat(HealthcareBillingAgent.documented("5", "GOÄ 5")).isTrue();
        assertThat(HealthcareBillingAgent.documented("5", "Zimmer 5")).isFalse();
        assertThat(HealthcareBillingAgent.documented("250", "Position 250")).isTrue();
        assertThat(HealthcareBillingAgent.documented("250", "0250")).isTrue();
        assertThat(HealthcareBillingAgent.documented("250", "2500")).isFalse();
    }

    @Test
    void feeSchedulesAreNormalised() {
        assertThat(HealthcareBillingAgent.normaliseFeeSchedule("goa")).isEqualTo("GOÄ");
        assertThat(HealthcareBillingAgent.normaliseFeeSchedule("EBM")).isEqualTo("EBM");
        assertThat(HealthcareBillingAgent.normaliseFeeSchedule("private")).isEqualTo("PRIVATE");
        assertThat(HealthcareBillingAgent.normaliseFeeSchedule(null)).isEqualTo("GOÄ");
        assertThat(HealthcareBillingAgent.normaliseFeeSchedule("quartal")).isNull();
    }

    private static Map<String, Object> patient() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", PATIENT);
        row.put("display_name", "Erika Muster");
        row.put("email", "erika@example.de");
        return row;
    }

    private static Map<String, Object> document(String text) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("original_filename", "rechnung.pdf");
        row.put("extracted_text", text);
        return row;
    }
}
