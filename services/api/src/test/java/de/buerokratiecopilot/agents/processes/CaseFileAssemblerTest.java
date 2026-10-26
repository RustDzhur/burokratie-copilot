package de.buerokratiecopilot.agents.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.processes.CaseFileAssembler.CaseFacts;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.DeadlineFacts;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.DocumentFacts;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.EventFacts;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.RunFacts;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.TaskFacts;

/**
 * The report rules of {@code case-file}: the overview contains every section, counts are complete
 * even when lists are capped, and the caps keep the report bounded.
 */
class CaseFileAssemblerTest {

    @Test
    @DisplayName("the agent id matches the manifest id")
    void idMatchesManifest() {
        assertThat(new CaseFileAgent(null, null, null, null, null, null).id())
                .isEqualTo("case-file");
    }

    @Test
    @DisplayName("the overview carries the case facts and every section")
    void assemblesFullOverview() {
        Map<String, Object> overview = CaseFileAssembler.assemble(
                new CaseFacts("BU-2026-42", "Mietvertrag prüfen", "IN_PROGRESS", "HIGH",
                        "mietvertrag", "2026-11-30T12:00:00Z"),
                List.of(new DocumentFacts("doc-1", "Mietvertrag.pdf", "D-100", "PROCESSED", true)),
                List.of(new TaskFacts("task-1", "Frist prüfen", "NEW", "HIGH", "2026-11-01",
                        true)),
                List.of(new EventFacts("STATUS_CHANGED", "In Bearbeitung", "2026-10-20T10:00:00Z")),
                List.of(new DeadlineFacts("deadline-1", "2026-11-15", "INTERNAL", false)),
                List.of(new RunFacts("run-1", "RUNNING", "check")),
                20, 20);

        assertThat(overview).containsEntry("reference", "BU-2026-42");
        assertThat(overview).containsEntry("status", "IN_PROGRESS");

        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) overview.get("counts");
        assertThat(counts).containsEntry("documents", 1)
                .containsEntry("openTasks", 1)
                .containsEntry("events", 1)
                .containsEntry("deadlines", 1)
                .containsEntry("workflowRuns", 1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> documents = (List<Map<String, Object>>) overview.get("documents");
        assertThat(documents).hasSize(1);
        assertThat(documents.getFirst()).containsEntry("filename", "Mietvertrag.pdf")
                .containsEntry("primary", true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deadlines = (List<Map<String, Object>>) overview.get("deadlines");
        assertThat(deadlines.getFirst()).containsEntry("dueDate", "2026-11-15")
                .containsEntry("verified", false);
    }

    @Test
    @DisplayName("open tasks are capped but the count stays complete")
    void capsOpenTasks() {
        List<TaskFacts> tasks = IntStream.range(0, 42)
                .mapToObj(i -> new TaskFacts("task-" + i, "Aufgabe " + i, "NEW", "NORMAL", null, true))
                .toList();
        Map<String, Object> overview = CaseFileAssembler.assemble(
                new CaseFacts("BU-1", "Titel", "NEW", "NORMAL", null, null),
                List.of(), tasks, List.of(), List.of(), List.of(), 20, 5);

        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) overview.get("counts");
        assertThat(counts).containsEntry("openTasks", 42);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shown = (List<Map<String, Object>>) overview.get("openTasks");
        assertThat(shown).hasSize(5);
    }

    @Test
    @DisplayName("empty sections are empty lists, not null")
    void emptySections() {
        Map<String, Object> overview = CaseFileAssembler.assemble(
                new CaseFacts(null, null, null, null, null, null),
                List.of(), List.of(), List.of(), List.of(), List.of(), 20, 20);

        assertThat(overview.get("documents")).isEqualTo(List.of());
        assertThat(overview.get("openTasks")).isEqualTo(List.of());
        assertThat(overview.get("events")).isEqualTo(List.of());
        assertThat(overview.get("deadlines")).isEqualTo(List.of());
        assertThat(overview.get("workflowRuns")).isEqualTo(List.of());
    }
}
