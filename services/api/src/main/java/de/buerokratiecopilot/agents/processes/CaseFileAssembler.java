package de.buerokratiecopilot.agents.processes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure assembly of the case file overview for {@code case-file}: given the case, its documents,
 * open tasks, events, deadlines and workflow runs, it builds one JSON-ready overview. No database
 * access — the agent collects the facts, this class writes the report, so the report itself is
 * testable with plain values.
 */
public final class CaseFileAssembler {

    private CaseFileAssembler() {
    }

    /** One case as the agent sees it. */
    public record CaseFacts(String reference, String title, String status, String priority,
                            String caseTypeCode, String dueAt) {
    }

    /** One document row: id, filename, number, processing status, primary flag. */
    public record DocumentFacts(String id, String filename, String number, String processingStatus,
                                boolean primary) {
    }

    /** One open task row: id, title, status, priority, due date. */
    public record TaskFacts(String id, String title, String status, String priority, String dueDate,
                            boolean assignmentPending) {
    }

    /** One case event: type, title, when it happened. */
    public record EventFacts(String type, String title, String occurredAt) {
    }

    /** One deadline: id, due date, type, verified flag. */
    public record DeadlineFacts(String id, String dueDate, String type, boolean verified) {
    }

    /** One workflow run: id, status, current step. */
    public record RunFacts(String id, String status, String currentStep) {
    }

    public static Map<String, Object> assemble(CaseFacts facts, List<DocumentFacts> documents,
                                               List<TaskFacts> openTasks, List<EventFacts> events,
                                               List<DeadlineFacts> deadlines, List<RunFacts> runs,
                                               int maxEvents, int maxOpenTasks) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("reference", facts.reference() == null ? "" : facts.reference());
        overview.put("title", facts.title() == null ? "" : facts.title());
        overview.put("status", facts.status() == null ? "" : facts.status());
        overview.put("priority", facts.priority() == null ? "" : facts.priority());
        overview.put("caseTypeCode", facts.caseTypeCode() == null ? "" : facts.caseTypeCode());
        overview.put("dueAt", facts.dueAt() == null ? "" : facts.dueAt());

        overview.put("documents", documents.stream().limit(64).map(document -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", document.id());
            row.put("filename", document.filename() == null ? "" : document.filename());
            row.put("number", document.number() == null ? "" : document.number());
            row.put("processingStatus", document.processingStatus() == null ? ""
                    : document.processingStatus());
            row.put("primary", document.primary());
            return row;
        }).toList());

        List<TaskFacts> shownTasks = openTasks.size() <= maxOpenTasks
                ? openTasks : new ArrayList<>(openTasks.subList(0, maxOpenTasks));
        overview.put("openTasks", shownTasks.stream().map(task -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.id());
            row.put("title", task.title() == null ? "" : task.title());
            row.put("status", task.status() == null ? "" : task.status());
            row.put("priority", task.priority() == null ? "" : task.priority());
            row.put("dueDate", task.dueDate() == null ? "" : task.dueDate());
            row.put("assignmentPending", task.assignmentPending());
            return row;
        }).toList());

        overview.put("events", events.stream().limit(Math.max(1, maxEvents)).map(event -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", event.type() == null ? "" : event.type());
            row.put("title", event.title() == null ? "" : event.title());
            row.put("occurredAt", event.occurredAt() == null ? "" : event.occurredAt());
            return row;
        }).toList());

        overview.put("deadlines", deadlines.stream().limit(64).map(deadline -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", deadline.id());
            row.put("dueDate", deadline.dueDate() == null ? "" : deadline.dueDate());
            row.put("type", deadline.type() == null ? "" : deadline.type());
            row.put("verified", deadline.verified());
            return row;
        }).toList());

        overview.put("workflowRuns", runs.stream().limit(16).map(run -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", run.id());
            row.put("status", run.status() == null ? "" : run.status());
            row.put("currentStep", run.currentStep() == null ? "" : run.currentStep());
            return row;
        }).toList());

        overview.put("counts", Map.of(
                "documents", documents.size(),
                "openTasks", openTasks.size(),
                "events", events.size(),
                "deadlines", deadlines.size(),
                "workflowRuns", runs.size()));
        return overview;
    }
}
