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

    public DocumentOutcomeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                select description, changes ->> 'result' as result from audit_events
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
            List<Map<String, Object>> contracts = jdbc.queryForList(
                    "select id from contracts where source_document_id = ? and deleted_at is null", id);
            if (!contracts.isEmpty()) {
                outcome.put("contractId", contracts.get(0).get("id"));
            }
            next = Boolean.TRUE.equals(task.get("assignment_pending"))
                    ? "Die Aufgabe wartet darauf, jemandem zugewiesen zu werden."
                    : "Die Aufgabe wartet auf Bearbeitung.";
        }
        else if (!audit.isEmpty()) {
            outcome.put("result", audit.get(0).get("result"));
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
        body.put("textPreview", text.isEmpty() ? null : text.get(0).get("preview"));
        body.put("steps", steps);
        body.put("outcome", outcome);
        return ResponseEntity.ok(body);
    }
}
