package de.buerokratiecopilot.agents.processes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.CaseFacts;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.DeadlineFacts;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.DocumentFacts;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.EventFacts;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.RunFacts;
import de.buerokratiecopilot.agents.processes.CaseFileAssembler.TaskFacts;
import de.buerokratiecopilot.cases.CaseDocumentRepository;
import de.buerokratiecopilot.cases.CaseEventRepository;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.deadlines.TaskDeadlineRepository;
import de.buerokratiecopilot.deadlines.domain.TaskDeadline;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * {@code case-file} — Vorgangsakte.
 *
 * <p>Reads everything the platform knows about one case — documents, open tasks, events, deadlines,
 * workflow runs — and assembles it into one overview. Purely read-only: the report is the product,
 * and it is written into the run journal ({@code meta.overview}) and the produced list, so the akte
 * survives the run it was built in.</p>
 */
@Component
public class CaseFileAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(CaseFileAgent.class);

    private static final int MAX_EVENTS = 20;
    private static final int MAX_OPEN_TASKS = 20;

    private final CaseRepository cases;
    private final CaseDocumentRepository caseDocuments;
    private final CaseEventRepository caseEvents;
    private final TaskDeadlineRepository deadlines;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CaseFileAgent(CaseRepository cases, CaseDocumentRepository caseDocuments,
                         CaseEventRepository caseEvents, TaskDeadlineRepository deadlines,
                         JdbcTemplate jdbc, ObjectMapper mapper) {
        this.cases = cases;
        this.caseDocuments = caseDocuments;
        this.caseEvents = caseEvents;
        this.deadlines = deadlines;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "case-file";
    }

    @Override
    public AgentResult run(AgentContext context) {
        return RequestContext.runAs(context.tenantId(), () -> doRun(context));
    }

    private AgentResult doRun(AgentContext context) {
        UUID tenantId = context.tenantId();
        String caseIdRaw = context.input("caseId");
        if (caseIdRaw == null || caseIdRaw.isBlank()) {
            return AgentResult.skipped("caseId fehlt — ohne Vorgang gibt es keine Akte");
        }
        UUID caseId;
        try {
            caseId = UUID.fromString(caseIdRaw.trim());
        }
        catch (IllegalArgumentException e) {
            return AgentResult.failed("caseId '" + caseIdRaw + "' ist keine UUID");
        }

        Case caseRecord = cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
                .orElse(null);
        if (caseRecord == null) {
            return AgentResult.skipped("Vorgang " + caseId + " wurde nicht gefunden");
        }

        CaseFacts facts = new CaseFacts(caseRecord.getReference(), caseRecord.getTitle(),
                caseRecord.getStatus() == null ? "" : caseRecord.getStatus().name(),
                caseRecord.getPriority() == null ? "" : caseRecord.getPriority().name(),
                caseTypeCode(caseRecord.getCaseTypeId()),
                caseRecord.getDueAt() == null ? "" : caseRecord.getDueAt().toString());

        List<DocumentFacts> documents = caseDocuments.findByCaseId(caseId).stream()
                .map(document -> new DocumentFacts(document.documentId().toString(),
                        document.filename(), document.documentNumber(), document.processingStatus(),
                        document.primary()))
                .toList();

        List<TaskFacts> openTasks = jdbc.query("""
                select id::text as id, title, status, priority, due_at, assignment_pending
                from tasks
                where case_id = ? and tenant_id = ? and deleted_at is null
                  and status not in ('DONE', 'CANCELLED')
                order by due_at asc nulls last, created_at asc
                """, (rs, row) -> new TaskFacts(rs.getString("id"), rs.getString("title"),
                rs.getString("status"), rs.getString("priority"),
                rs.getTimestamp("due_at") == null ? "" : rs.getTimestamp("due_at").toInstant().toString(),
                rs.getBoolean("assignment_pending")), caseId, tenantId);

        List<EventFacts> events = caseEvents.findTop20ByCaseIdOrderByOccurredAtDesc(caseId).stream()
                .map(event -> new EventFacts(
                        event.getEventType() == null ? "" : event.getEventType().name(),
                        event.getTitle(),
                        event.getOccurredAt() == null ? "" : event.getOccurredAt().toString()))
                .toList();

        List<DeadlineFacts> deadlineRows = deadlines.findForCase(caseId).stream()
                .map(deadline -> new DeadlineFacts(deadline.getId().toString(),
                        deadline.getDueDate() == null
                                ? deadline.getDueAt() == null ? "" : deadline.getDueAt().toString()
                                : deadline.getDueDate().toString(),
                        deadline.getDeadlineType() == null ? "" : deadline.getDeadlineType().name(),
                        !deadline.isRequiresVerification()))
                .toList();

        List<RunFacts> runs = jdbc.query("""
                select id::text as id, status, current_step_key
                from workflow_runs
                where case_id = ? and tenant_id = ?
                  and status not in ('CANCELLED', 'FAILED')
                order by started_at desc
                """, (rs, row) -> new RunFacts(rs.getString("id"), rs.getString("status"),
                rs.getString("current_step_key")), caseId, tenantId);

        Map<String, Object> overview = CaseFileAssembler.assemble(facts, documents, openTasks,
                events, deadlineRows, runs, MAX_EVENTS, MAX_OPEN_TASKS);

        Map<String, String> meta = new java.util.LinkedHashMap<>();
        meta.put("caseId", caseId.toString());
        meta.put("reference", caseRecord.getReference() == null ? "" : caseRecord.getReference());
        meta.put("overview", toJson(overview));

        String label = documents.size() + " Dokument(e), " + openTasks.size()
                + " offene Aufgabe(n), " + deadlineRows.size() + " Frist(en)";
        return AgentResult.done("Vorgangsakte für " + caseRecord.getReference() + " erstellt: "
                + label,
                List.of(new AgentResult.Produced("case_overview", caseId.toString(), label)), meta);
    }

    private String caseTypeCode(UUID caseTypeId) {
        if (caseTypeId == null) {
            return "";
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select code from case_types where id = ?", caseTypeId);
        return rows.isEmpty() ? "" : String.valueOf(rows.getFirst().get("code"));
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
