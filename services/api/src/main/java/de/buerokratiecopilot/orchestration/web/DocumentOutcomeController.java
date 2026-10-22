package de.buerokratiecopilot.orchestration.web;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Answers the question the person at the screen asks right after an upload: "what is happening with my
 * document?"
 *
 * <p>The answer is assembled from what the platform really stored (the processing steps, the task, the
 * case and the contract it created and the explanation it recorded), never from a status label guessed
 * on the client. A document that is still waiting says what it waits for; a document that was filed
 * without action says why no action was needed.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Upload, list, inspect and download documents")
public class DocumentOutcomeController {

    private final JdbcTemplate jdbc;
    private final de.buerokratiecopilot.orchestration.OwnerNotifier facts;
    private final de.buerokratiecopilot.orchestration.CaseOrchestrator orchestrator;
    private final de.buerokratiecopilot.documents.reading.AiDocumentReader reader;

    public DocumentOutcomeController(JdbcTemplate jdbc, de.buerokratiecopilot.orchestration.OwnerNotifier facts,
                                     de.buerokratiecopilot.orchestration.CaseOrchestrator orchestrator,
                                     de.buerokratiecopilot.documents.reading.AiDocumentReader reader) {
        this.jdbc = jdbc;
        this.facts = facts;
        this.orchestrator = orchestrator;
        this.reader = reader;
    }

    /**
     * The cases a document belongs to, for the block "related cases" on the document page. A case the reader may not
     * see (not owner, not responsible, not a participant, and no right to see every case) is left out.
     */
    @GetMapping("/{id}/cases")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "Cases this document is linked to, as far as the reader may see them")
    public ResponseEntity<List<Map<String, Object>>> relatedCases(@PathVariable UUID id) {
        UUID user = de.buerokratiecopilot.shared.context.CurrentUser.required();
        boolean seesEvery = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream().anyMatch(a -> "cases.assign".equals(a.getAuthority()));
        // 404 for a document of another tenant (row level security hides it) instead of an empty list.
        Integer exists = jdbc.queryForObject("select count(*) from documents where id = ? and deleted_at is null",
                Integer.class, id);
        if (exists == null || exists == 0) {
            throw de.buerokratiecopilot.shared.error.ApiException.notFound("Document");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.title, c.status, cd.link_origin, cd.is_primary, cd.confirmed_at
                from case_documents cd join cases c on c.id = cd.case_id
                where cd.document_id = ?
                  and (? or c.owner_user_id = ? or c.responsible_user_id = ?
                       or exists (select 1 from case_participants p where p.case_id = c.id and p.user_id = ?
                                  and p.revoked_at is null and (p.expires_at is null or p.expires_at > now())))
                order by cd.created_at desc
                """, id, seesEvery, user, user, user);
        return ResponseEntity.ok(rows);
    }

    /**
     * Reads the pages with a model that looks at them (handwriting, stamps), at the request of a person. The pages go to
     * the configured AI provider, so this is never done by itself; the answer says what was proposed or why nothing was.
     */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/ai-read")
    @PreAuthorize("hasAuthority('documents.review')")
    @Operation(summary = "Read a document with AI vision (handwriting); values are proposals, not facts")
    public ResponseEntity<Map<String, Object>> aiRead(@PathVariable UUID id) {
        var result = reader.read(id, de.buerokratiecopilot.shared.context.CurrentUser.required());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.status());
        body.put("found", result.found());
        body.put("summary", result.summary());
        body.put("handwritten", result.handwritten());
        return ResponseEntity.ok(body);
    }

    /**
     * The platform filed an old document without work; this is how a person says "make a task anyway".
     * Nothing is archived or invented on the way: the task has no deadline unless the document states one that is
     * not in the past.
     */
    @org.springframework.web.bind.annotation.PostMapping("/{id}/create-task")
    @PreAuthorize("hasAuthority('tasks.create')")
    @Operation(summary = "Create the task for a document that was filed as old")
    public ResponseEntity<Map<String, Object>> createTask(@PathVariable UUID id) {
        var outcome = orchestrator.orchestrate(id, true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", outcome.result());
        body.put("explanation", outcome.explanation());
        body.put("taskId", outcome.taskId());
        return ResponseEntity.ok(body);
    }

    /** The words that made a rule fire, with the text around them, so "why was this suggested?" has an answer. */
    private List<Map<String, Object>> matchedPassages(UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.pattern, lower(left(d.extracted_text, 30000)) as lowered, left(d.extracted_text, 30000) as text
                from document_classifications c
                join documents d on d.id = c.document_id
                join document_type_rules r on r.document_type_id = c.document_type_id and r.is_active
                     and r.rule_type = 'KEYWORD' and (r.tenant_id is null or r.tenant_id = d.tenant_id)
                where c.document_id = ? and c.is_current
                """, documentId);
        List<Map<String, Object>> passages = new java.util.ArrayList<>();
        for (Map<String, Object> row : rows) {
            String lowered = String.valueOf(row.get("lowered"));
            String pattern = String.valueOf(row.get("pattern")).toLowerCase(java.util.Locale.GERMAN);
            int at = lowered.indexOf(pattern);
            if (at < 0 || row.get("text") == null) {
                continue;
            }
            String text = String.valueOf(row.get("text"));
            int from = Math.max(0, at - 45);
            int to = Math.min(text.length(), at + pattern.length() + 45);
            Map<String, Object> passage = new LinkedHashMap<>();
            passage.put("pattern", row.get("pattern"));
            passage.put("snippet", (from > 0 ? "…" : "") + text.substring(from, to).replaceAll("\\s+", " ").strip()
                    + (to < text.length() ? "…" : ""));
            passages.add(passage);
            if (passages.size() == 4) {
                break;
            }
        }
        return passages;
    }

    @GetMapping("/{id}/pipeline")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "The processing steps of a document and what the platform did with it")
    public ResponseEntity<Map<String, Object>> pipeline(@PathVariable UUID id) {
        List<Map<String, Object>> documents = jdbc.queryForList("""
                select d.processing_status, d.review_status, t.code as type_code, t.name ->> 'de' as type_name,
                       (select c.confidence from document_classifications c
                         where c.document_id = d.id and c.is_current) as confidence
                from documents d left join document_types t on t.id = d.document_type_id
                where d.id = ? and d.deleted_at is null
                """, id);
        if (documents.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> document = documents.get(0);

        List<Map<String, Object>> steps = jdbc.query("""
                select step, status, attempt, started_at, finished_at, duration_ms, error_message
                from document_processing_steps where document_id = ?
                order by coalesce(started_at, created_at), created_at
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("step", rs.getString("step"));
            row.put("status", rs.getString("status"));
            row.put("attempt", rs.getInt("attempt"));
            row.put("startedAt", rs.getObject("started_at", OffsetDateTime.class));
            row.put("finishedAt", rs.getObject("finished_at", OffsetDateTime.class));
            row.put("error", rs.getString("error_message"));
            return row;
        }, id);

        Map<String, Object> outcome = new LinkedHashMap<>();
        List<Map<String, Object>> tasks = jdbc.queryForList("""
                select t.id, t.title, t.status, t.priority, t.due_at, t.case_id, t.assignment_pending
                from tasks t
                where t.source_document_id = ? and t.deleted_at is null and t.parent_task_id is null
                  and t.source = 'DOCUMENT'
                """, id);
        List<Map<String, Object>> audit = jdbc.queryForList("""
                select description, changes ->> 'result' as result, changes ->> 'documentDate' as document_date,
                       changes ->> 'statedDue' as stated_due, changes ->> 'periodTo' as period_to
                from audit_events
                where entity_type = 'documents' and entity_id = ? and action = 'DOCUMENT_ORCHESTRATED'
                order by created_at desc limit 1
                """, id);

        String status = String.valueOf(document.get("processing_status"));
        String next;
        if (!tasks.isEmpty()) {
            Map<String, Object> task = tasks.get(0);
            outcome.put("result", "TASK_CREATED");
            outcome.put("taskId", task.get("id"));
            outcome.put("taskTitle", task.get("title"));
            outcome.put("taskStatus", task.get("status"));
            outcome.put("priority", task.get("priority"));
            outcome.put("dueAt", task.get("due_at"));
            outcome.put("caseId", task.get("case_id"));
            outcome.put("assignmentPending", task.get("assignment_pending"));
            List<Map<String, Object>> scenario = jdbc.queryForList("""
                    select s.requires_human_review from document_type_scenarios s
                    join document_types dt on dt.code = s.document_type_code
                    join documents d on d.document_type_id = dt.id
                    where d.id = ? and s.tenant_id is null
                    """, id);
            outcome.put("humanReview", !scenario.isEmpty() && Boolean.TRUE.equals(scenario.get(0).get("requires_human_review")));
            next = Boolean.TRUE.equals(task.get("assignment_pending"))
                    ? "Die Aufgabe wartet darauf, jemandem zugewiesen zu werden."
                    : "Die Aufgabe wartet auf Bearbeitung.";
        }
        else if (!audit.isEmpty()) {
            outcome.put("result", audit.get(0).get("result"));
            outcome.put("documentDate", audit.get(0).get("document_date"));
            outcome.put("statedDue", audit.get(0).get("stated_due"));
            outcome.put("periodTo", audit.get(0).get("period_to"));
            next = "Keine weitere Handlung nötig.";
        }
        else if ("REVIEW_REQUIRED".equals(status)) {
            outcome.put("result", "WAITING_FOR_REVIEW");
            next = "Das Dokument wartet auf Ihre Entscheidung im Prüfzentrum.";
        }
        else if ("DUPLICATE".equals(status)) {
            outcome.put("result", "DUPLICATE");
            next = "Duplikat: es wurde abgelegt und startet keinen zweiten Vorgang.";
        }
        else if (steps.stream().anyMatch(step -> "FAILED".equals(step.get("status")))) {
            outcome.put("result", "FAILED");
            next = "Ein Verarbeitungsschritt ist fehlgeschlagen. Sie können die Verarbeitung erneut starten.";
        }
        else {
            outcome.put("result", "PROCESSING");
            next = "Das Dokument wird verarbeitet.";
        }
        outcome.put("explanation", audit.isEmpty() ? null : audit.get(0).get("description"));
        outcome.put("next", next);

        // What the platform thinks this is while nobody has confirmed it, why a person is asked, and the first
        // lines it read: the three things a reviewer needs before deciding.
        List<Map<String, Object>> suggestion = jdbc.queryForList("""
                select t.code, t.name ->> 'de' as name, c.confidence, c.rationale
                from document_classifications c join document_types t on t.id = c.document_type_id
                where c.document_id = ? and c.is_current
                """, id);
        List<Map<String, Object>> reason = jdbc.queryForList("""
                select reason from document_reviews where document_id = ? and status = 'PENDING'
                order by created_at desc limit 1
                """, id);
        List<Map<String, Object>> text = jdbc.queryForList(
                "select left(extracted_text, 1200) as preview from documents where id = ?", id);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", id);
        body.put("processingStatus", status);
        body.put("reviewStatus", document.get("review_status"));
        body.put("documentType", document.get("type_code"));
        body.put("documentTypeName", document.get("type_name"));
        body.put("confidence", document.get("confidence"));
        body.put("suggestedType", suggestion.isEmpty() ? null : suggestion.get(0));
        body.put("reviewReason", reason.isEmpty() ? null : reason.get(0).get("reason"));
        Map<String, Object> documentFacts = facts.documentFacts(id);
        body.put("reviewKind", reason.isEmpty() ? null : documentFacts.get("reviewKind"));
        body.put("acceptThreshold", documentFacts.get("acceptThreshold"));
        body.put("typeNames", documentFacts.get("typeName"));
        body.put("matchedPassages", suggestion.isEmpty() ? List.of() : matchedPassages(id));
        body.put("textPreview", text.isEmpty() ? null : text.get(0).get("preview"));
        body.put("steps", steps);
        body.put("outcome", outcome);
        return ResponseEntity.ok(body);
    }
}
