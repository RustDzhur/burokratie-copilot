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
import de.buerokratiecopilot.tasks.domain.Task;

/** {@code knowledge-base}: index, stale review tasks and honest gaps. */
class KnowledgeBaseAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private JdbcTemplate jdbc;
    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private KnowledgeBaseAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new KnowledgeBaseAgent(jdbc, tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void buildsTheIndexAndCreatesAReviewTaskForTheStaleArticle() {
        UUID staleId = UUID.randomUUID();
        UUID gapId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                row(staleId, "richtlinie.pdf", "RL-1", LocalDate.of(2025, 1, 1), USER,
                        "vertrag", "CONTRACTS", LocalDate.of(2025, 1, 2)),
                row(gapId, "notiz.pdf", null, LocalDate.of(2026, 9, 1), null, null, null,
                        LocalDate.of(2026, 9, 2))));
        Task task = new Task(UUID.randomUUID(), TENANT, "Wissensartikel prüfen: RL-1");
        when(tasks.createIfAbsent(any(), any())).thenReturn(Optional.of(task));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(artifact("knowledge-index"));

        AgentResult result = agent.run(context(Map.of("reviewDueDays", "180")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("articles", "2")
                .containsEntry("stale", "1")
                .containsEntry("gaps", "1")
                .containsEntry("reviewTasks", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("knowledge-index", "task");
        verify(tasks).createIfAbsent(any(), any());
        verify(artifacts).put(any(), any(), anyString(),
                org.mockito.ArgumentMatchers.startsWith("knowledge-index:"), any());
    }

    @Test
    void refusesWhenNoArticleMatches() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        AgentResult result = agent.run(context(Map.of("query", "nichts")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_DATA");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
        verify(tasks, never()).createIfAbsent(any(), any());
    }

    @Test
    void refusesAnInvalidOwnerInsteadOfIgnoringTheFilter() {
        AgentResult result = agent.run(context(Map.of("ownerUserId", "keine-uuid")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "INVALID_INPUT");
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    private static Map<String, Object> row(UUID id, String filename, String number,
                                           LocalDate documentDate, UUID createdBy, String typeCode,
                                           String category, LocalDate receivedOn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("original_filename", filename);
        row.put("document_number", number);
        row.put("document_date", documentDate);
        row.put("received_at", java.sql.Timestamp.valueOf(receivedOn.atStartOfDay()));
        row.put("created_by", createdBy);
        row.put("type_code", typeCode);
        row.put("category", category);
        return row;
    }

    static AgentArtifactStore.Artifact artifact(String kind) {
        return new AgentArtifactStore.Artifact(UUID.randomUUID(), kind, "key", "storage-key",
                "hash", 10, false);
    }
}
