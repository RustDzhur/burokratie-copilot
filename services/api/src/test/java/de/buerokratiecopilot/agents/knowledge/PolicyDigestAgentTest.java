package de.buerokratiecopilot.agents.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentTestFixtures;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.tasks.domain.Task;

/** {@code knowledge-policy-digest}: extract, gaps, acknowledgement task, honest comparison. */
class PolicyDigestAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private DocumentRepository documents;
    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private PolicyDigestAgent agent;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new PolicyDigestAgent(documents, tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void summarisesThePolicyAndCreatesTheAcknowledgementTask() {
        UUID policyId = UUID.randomUUID();
        UUID missingId = UUID.randomUUID();
        Document policy = mock(Document.class);
        when(policy.getId()).thenReturn(policyId);
        when(policy.getDocumentNumber()).thenReturn("RL-1");
        when(policy.getOriginalFilename()).thenReturn("richtlinie.pdf");
        when(policy.getDocumentDate()).thenReturn(LocalDate.of(2026, 9, 1));
        when(policy.getSummary()).thenReturn("Neue Fristen für Freigaben.");
        when(policy.getExtractedText()).thenReturn("Freigaben bis 31.12.2026 einholen.");
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(policyId, TENANT))
                .thenReturn(Optional.of(policy));
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(missingId, TENANT))
                .thenReturn(Optional.empty());
        Task task = new Task(UUID.randomUUID(), TENANT, "Richtlinie zur Kenntnis nehmen");
        when(tasks.createIfAbsent(any(), any())).thenReturn(Optional.of(task));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("policy-digest"));

        AgentResult result = agent.run(context(Map.of("policyIds",
                policyId + "," + missingId, "targetRoles", "MANAGER,ADMIN")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("policiesRequested", "2")
                .containsEntry("policiesFound", "1")
                .containsEntry("tasksCreated", "1");
        assertThat(result.meta().get("gaps")).isNotEqualTo("0");
        assertThat(result.summary()).contains("ersetzt nicht das Originaldokument");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("policy-digest", "task");
    }

    @Test
    void refusesWhenNoneOfThePoliciesExists() {
        UUID missing = UUID.randomUUID();
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(missing, TENANT))
                .thenReturn(Optional.empty());

        AgentResult result = agent.run(context(Map.of("policyIds", missing.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NOT_FOUND");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesWithoutPolicyIds() {
        AgentResult result = agent.run(context(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_INPUT");
        verify(tasks, never()).createIfAbsent(any(), any());
    }

    @Test
    void deadlinesAndWordComparisonAreMechanical() {
        LocalDate today = LocalDate.of(2026, 10, 26);

        assertThat(PolicyDigestAgent.deadlines(today,
                "Frist 31.12.2026, alt 01.01.2025, Termin 2026-11-15"))
                .containsExactly("2026-11-15", "2026-12-31");
        assertThat(PolicyDigestAgent.deadlines(today, null)).isEmpty();

        PolicyDigestAgent.Change change = PolicyDigestAgent.compare(
                "Freigaben werden zentral dokumentiert.",
                "Freigaben werden dezentral dokumentiert.");
        assertThat(change.added()).contains("dezentral");
        assertThat(change.removed()).contains("zentral");
    }

    @Test
    void theOptionalPreviousVersionBecomesAGapWhenItIsMissing() {
        UUID policyId = UUID.randomUUID();
        Document policy = mock(Document.class);
        when(policy.getId()).thenReturn(policyId);
        when(policy.getOriginalFilename()).thenReturn("richtlinie.pdf");
        when(policy.getExtractedText()).thenReturn("Text");
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(policyId, TENANT))
                .thenReturn(Optional.of(policy));
        when(tasks.createIfAbsent(any(), any())).thenReturn(Optional.empty());
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("policy-digest"));

        AgentResult result = agent.run(context(Map.of("policyIds", policyId.toString(),
                "previousVersion", "unbekannte-fassung")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("tasksCreated")).isEqualTo("0");
        assertThat(List.of(result.produced())).isNotNull();
    }
}
