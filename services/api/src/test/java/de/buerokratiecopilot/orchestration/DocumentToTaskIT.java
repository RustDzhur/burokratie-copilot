package de.buerokratiecopilot.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentReview;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.documents.review.ReviewService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.outbox.OutboxDispatcher;
import de.buerokratiecopilot.support.TestAccounts;
import de.buerokratiecopilot.tasks.TaskService;

/**
 * The guard against the failure this suite did not catch: a document was uploaded and then nothing
 * happened to it.
 *
 * <p>Every earlier test called {@code pipeline.advance()} itself, so each stage was proven to work and the
 * chain was never proven to start. These tests only upload (and run the outbox, which is what the scheduler
 * does every few seconds) and then look at what the platform did on its own: no step is triggered by hand.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentToTaskIT {

    private static final DateTimeFormatter GERMAN = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Autowired
    private TestAccounts accounts;

    @Autowired
    private DocumentService documents;

    @Autowired
    private OutboxDispatcher outbox;

    @Autowired
    private TaskService tasks;

    @Autowired
    private de.buerokratiecopilot.calendar.CalendarEventService calendar;

    @Autowired
    private ReviewService reviews;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    @Autowired
    private OrchestrationWatchdog watchdog;

    @Autowired
    private CaseOrchestrator orchestrator;

    @Autowired
    private JdbcTemplate jdbc;

    private record Account(UUID tenantId, UUID adminUserId) {
    }

    private Account account() {
        var created = accounts.create("orc");
        return new Account(created.tenantId(), created.adminUserId());
    }

    private UUID upload(Account account, String name, String text) {
        return RequestContext.runAs(account.tenantId(), () -> documents.receive(
                new DocumentService.UploadRequest(new MockMultipartFile("file", name, "text/plain",
                        text.getBytes(StandardCharsets.UTF_8)), null, null, Document.Source.UPLOAD,
                        "Stadtwerke Musterstadt GmbH")).document().getId());
    }

    /** Uploads text as a document and lets the outbox run, as the scheduler does. */
    private UUID uploadAndRun(Account account, String name, String text) {
        UUID id = upload(account, name, text);
        runOutbox();
        return id;
    }

    private void runOutbox() {
        for (int round = 0; round < 4; round++) {
            outbox.dispatch();
        }
    }

    private String mahnung(LocalDate due) {
        return """
                Stadtwerke Musterstadt GmbH
                Letzte Mahnung
                Rechnungsnummer: RE-2026-%d
                Offener Betrag: 280,00 EUR
                Mahngebühr: 5,00 EUR
                Frist: %s
                Bitte zahlen Sie den Betrag. Andernfalls schalten wir ein Inkassounternehmen ein.
                """.formatted(System.nanoTime() % 100000, due.format(GERMAN));
    }

    @Test
    @DisplayName("an uploaded Mahnung becomes a case with a task, deadlines and a checklist, without a manual step")
    void uploadedMahnungBecomesWork() {
        Account account = account();
        LocalDate due = LocalDate.now().plusDays(10);

        UUID documentId = uploadAndRun(account, "mahnung.txt", mahnung(due));

        Map<String, Object> document = jdbc.queryForMap(
                "select processing_status from documents where id = ?", documentId);
        assertThat(document.get("processing_status")).as("the pipeline ran on its own").isEqualTo("READY");

        Map<String, Object> task = jdbc.queryForMap("""
                select id, case_id, priority, requires_approval, completion_criteria, assignment_pending, due_at
                from tasks where source_document_id = ? and source = 'DOCUMENT'
                """, documentId);
        assertThat(task.get("priority")).isIn("HIGH", "CRITICAL");
        assertThat(task.get("requires_approval")).as("a claim is never acknowledged without approval")
                .isEqualTo(true);
        assertThat((String) task.get("completion_criteria")).isNotBlank();
        assertThat(task.get("case_id")).as("the matter is a case").isNotNull();
        assertThat(task.get("assignment_pending"))
                .as("one active member: the responsible person is unambiguous").isEqualTo(false);

        UUID taskId = (UUID) task.get("id");
        List<Map<String, Object>> deadlines = jdbc.queryForList(
                "select deadline_type, due_date from task_deadlines where task_id = ? order by deadline_type",
                taskId);
        assertThat(deadlines).extracting(row -> row.get("deadline_type"))
                .contains("DOCUMENT_DATE", "INTERNAL");
        assertThat(deadlines).filteredOn(row -> "DOCUMENT_DATE".equals(row.get("deadline_type")))
                .singleElement().satisfies(row -> assertThat(row.get("due_date").toString())
                        .isEqualTo(due.toString()));

        Integer checklist = jdbc.queryForObject(
                "select count(*) from task_checklist_items where task_id = ?", Integer.class, taskId);
        assertThat(checklist).isGreaterThanOrEqualTo(3);

        Integer notifications = jdbc.queryForObject("""
                select count(*) from notifications where tenant_id = ? and entity_id = ?
                """, Integer.class, account.tenantId(), taskId);
        assertThat(notifications).as("the person responsible was told").isEqualTo(1);

        Integer linked = jdbc.queryForObject("select count(*) from case_documents where document_id = ?",
                Integer.class, documentId);
        assertThat(linked).isEqualTo(1);
    }

    @Test
    @DisplayName("delivering the completion event again creates one task, not two")
    void idempotent() {
        Account account = account();
        UUID documentId = uploadAndRun(account, "mahnung.txt", mahnung(LocalDate.now().plusDays(10)));

        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                    insert into outbox_events (id, event_id, tenant_id, aggregate_type, aggregate_id,
                                               event_type, payload, status, available_at)
                    values (gen_random_uuid(), gen_random_uuid(), ?, 'documents', ?,
                            'document.processing_completed', '{}'::jsonb, 'PENDING', now())
                    """, account.tenantId(), documentId);
        }
        runOutbox();

        Integer count = jdbc.queryForObject("""
                select count(*) from tasks where source_document_id = ? and source = 'DOCUMENT'
                """, Integer.class, documentId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("an advertisement is filed and explained, and does not create work")
    void advertisementCreatesNoTask() {
        Account account = account();
        UUID documentId = uploadAndRun(account, "newsletter.txt", """
                Newsletter Oktober
                Unsere Angebote des Monats. Newsletter abbestellen oder im Browser ansehen.
                Nur diese Woche 20 Prozent Rabatt, jetzt bestellen und sparen.
                """ + System.nanoTime());

        Integer tasksCount = jdbc.queryForObject(
                "select count(*) from tasks where source_document_id = ?", Integer.class, documentId);
        assertThat(tasksCount).isZero();

        String explanation = jdbc.queryForObject("""
                select description from audit_events
                where entity_id = ? and action = 'DOCUMENT_ORCHESTRATED'
                """, String.class, documentId);
        assertThat(explanation).as("the person can see why nothing was asked of them").contains("keine Handlung");
    }

    @Test
    @DisplayName("a document that cannot be classified is never dropped: it waits in the review centre and the owner is told")
    void unknownDocumentGoesToAPerson() {
        Account account = account();
        UUID documentId = uploadAndRun(account, "unklar.txt",
                "Zwei Zeilen ohne jedes erkennbare Merkmal " + System.nanoTime() + "\nnoch eine Zeile");

        assertThat(jdbc.queryForObject("select processing_status from documents where id = ?", String.class,
                documentId)).isEqualTo("REVIEW_REQUIRED");
        Integer openReviews = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from document_reviews where document_id = ? and status = 'PENDING'",
                Integer.class, documentId));
        assertThat(openReviews).as("the review centre holds the question").isGreaterThanOrEqualTo(1);
        Integer told = jdbc.queryForObject("""
                select count(*) from notifications where tenant_id = ? and notification_type = 'document.review_required'
                """, Integer.class, account.tenantId());
        assertThat(told).as("the owner is told that a decision is needed").isEqualTo(1);
        Integer tasksCount = jdbc.queryForObject(
                "select count(*) from tasks where source_document_id = ?", Integer.class, documentId);
        assertThat(tasksCount).as("no work is invented from an unconfirmed classification").isZero();
    }


    @Test
    @DisplayName("completing a payment task does not close the matter: a follow-up waits for the confirmation")
    void followUpAfterCompletion() {
        Account account = account();
        UUID documentId = uploadAndRun(account, "mahnung.txt", mahnung(LocalDate.now().plusDays(10)));
        UUID taskId = jdbc.queryForObject(
                "select id from tasks where source_document_id = ? and source = 'DOCUMENT'", UUID.class,
                documentId);

        RequestContext.runAs(account.tenantId(), () -> {
            tasks.approve(taskId, account.adminUserId());
            tasks.complete(taskId, account.adminUserId(), "Zahlung und Antwort erledigt", true);
        });
        runOutbox();

        Map<String, Object> follow = jdbc.queryForMap("""
                select title, status, completion_criteria from tasks where parent_task_id = ?
                """, taskId);
        assertThat(follow.get("status")).as("open, not done: the matter waits for the confirmation")
                .isIn("NEW", "IN_PROGRESS");
        assertThat((String) follow.get("completion_criteria")).contains("Bestätigung");
    }

    @Test
    @DisplayName("a task past its date is reported once, not on every cycle")
    void overdueReportedOnce() {
        Account account = account();
        UUID documentId = uploadAndRun(account, "mahnung.txt", mahnung(LocalDate.now().plusDays(10)));
        UUID taskId = jdbc.queryForObject(
                "select id from tasks where source_document_id = ? and source = 'DOCUMENT'", UUID.class,
                documentId);
        jdbc.update("update tasks set due_at = now() - interval '2 days' where id = ?", taskId);

        watchdog.reportOverdue(account.tenantId());
        watchdog.reportOverdue(account.tenantId());

        Integer reports = jdbc.queryForObject("""
                select count(*) from notifications where entity_id = ? and notification_type = 'task.overdue'
                """, Integer.class, taskId);
        assertThat(reports).isEqualTo(1);
    }

    @Test
    @DisplayName("a document whose event was lost is picked up by the watchdog")
    void watchdogRecoversLostEvent() {
        Account account = account();
        UUID id = upload(account, "mahnung.txt", mahnung(LocalDate.now().plusDays(10)));
        // The event is lost, and the document has waited longer than the watchdog's patience.
        jdbc.update("delete from outbox_events where aggregate_id = ?", id);
        jdbc.update("update documents set received_at = now() - interval '10 minutes' where id = ?", id);

        watchdog.recoverDocuments(account.tenantId());
        runOutbox();

        assertThat(jdbc.queryForObject("select processing_status from documents where id = ?", String.class, id))
                .isEqualTo("READY");
        assertThat(jdbc.queryForObject(
                "select count(*) from tasks where source_document_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("a document that cannot be read keeps the record of the failure and tells the owner, instead of waiting for ever")
    void unreadableDocumentIsReported() {
        Account account = account();
        // A PDF by its first bytes, nothing readable behind them: the structure check passes, reading fails.
        byte[] broken = ("%PDF-1.4\n" + "nicht lesbar ".repeat(40) + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
        UUID id = RequestContext.runAs(account.tenantId(), () -> documents.receive(
                new DocumentService.UploadRequest(new MockMultipartFile("file", "kaputt.pdf", "application/pdf",
                        broken), null, null, Document.Source.UPLOAD, "Absender")).document().getId());
        runOutbox();

        List<Map<String, Object>> stepRows = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForList(
                "select step, status from document_processing_steps where document_id = ? order by created_at", id));
        long failed = stepRows.stream().filter(row -> "FAILED".equals(row.get("status"))).count();
        assertThat(failed).as("the failure is recorded, not lost with a rolled-back transaction: " + stepRows)
                .isEqualTo(1);
        Integer told = jdbc.queryForObject("""
                select count(*) from notifications where tenant_id = ? and notification_type = 'document.processing_stopped'
                """, Integer.class, account.tenantId());
        assertThat(told).as("the owner is told why the document is not moving").isEqualTo(1);
    }

    @Test
    @DisplayName("a document that waits for the reviewer already shows what was read from it")
    void reviewerSeesTheFieldsBeforeDeciding() {
        Account account = account();
        LocalDate due = LocalDate.now().plusDays(9);
        // One weak keyword only: the type is suggested but not certain, so a person has to confirm it.
        UUID documentId = uploadAndRun(account, "unsicher.txt", """
                Mahngebühr: 5,00 EUR
                Frist: %s
                Aktenzeichen: AZ-%d
                """.formatted(due.format(GERMAN), System.nanoTime()));

        assertThat(jdbc.queryForObject("select processing_status from documents where id = ?", String.class,
                documentId)).isEqualTo("REVIEW_REQUIRED");
        List<Map<String, Object>> fields = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForList(
                "select field_key, normalized_value, verified_by from document_extractions where document_id = ?",
                documentId));
        assertThat(fields).as("the reviewer is not shown an empty table")
                .extracting(row -> row.get("field_key")).contains("Frist");
        assertThat(fields).filteredOn(row -> "Frist".equals(row.get("field_key")))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("normalized_value").toString()).isEqualTo(due.toString());
                    assertThat(row.get("verified_by")).as("a reading is not a confirmation").isNull();
                });
    }

    private String invoice(LocalDate invoiceDate, String term) {
        return """
                Stadtwerke Musterstadt GmbH
                Rechnung
                Rechnungsnummer: RE-%d
                Rechnungsdatum: %s
                Rechnungsbetrag: 280,00 EUR
                %s
                """.formatted(System.nanoTime() % 1000000, invoiceDate.format(GERMAN), term);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("a payment term in days counts from the invoice date, not from the day of the upload")
    void paymentTermCountsFromTheInvoiceDate() {
        Account account = account();
        LocalDate invoiceDate = LocalDate.now().minusDays(1);
        UUID documentId = uploadAndRun(account, "rechnung.txt",
                invoice(invoiceDate, "Zahlbar innerhalb von 5 Tagen ohne Abzug."));

        UUID taskId = jdbc.queryForObject(
                "select id from tasks where source_document_id = ? and source = 'DOCUMENT'", UUID.class, documentId);
        assertThat(jdbc.queryForObject(
                "select due_date from task_deadlines where task_id = ? and deadline_type = 'DOCUMENT_DATE'",
                LocalDate.class, taskId)).isEqualTo(invoiceDate.plusDays(5));
        assertThat(jdbc.queryForObject(
                "select note from task_deadlines where task_id = ? and deadline_type = 'DOCUMENT_DATE'",
                String.class, taskId)).contains("Berechnet").contains("5 Tage");
    }

    @Test
    @DisplayName("an old invoice is filed without a task, a calendar entry or a message: nothing is invented")
    void oldInvoiceIsFiledQuietly() {
        Account account = account();
        LocalDate invoiceDate = LocalDate.now().minusYears(1);
        UUID documentId = uploadAndRun(account, "alte-rechnung.txt",
                invoice(invoiceDate, "Zahlbar innerhalb von 5 Tagen ohne Abzug."));

        assertThat(count("select count(*) from tasks where source_document_id = ?", documentId)).isZero();
        assertThat(count("select count(*) from tasks where tenant_id = ?", account.tenantId())).isZero();
        assertThat(count("select count(*) from notifications where tenant_id = ? and notification_type = 'document.action_required'",
                account.tenantId())).isZero();
        Map<String, Object> audit = jdbc.queryForMap("""
                select changes ->> 'result' as result, changes ->> 'documentDate' as document_date,
                       changes ->> 'statedDue' as stated_due
                from audit_events where entity_id = ? and action = 'DOCUMENT_ORCHESTRATED'
                """, documentId);
        assertThat(audit.get("result")).isEqualTo("ARCHIVED_OLD");
        assertThat(audit.get("document_date")).isEqualTo(invoiceDate.toString());
        assertThat(audit.get("stated_due")).as("invoice date + 5 days, from the document").isEqualTo(invoiceDate.plusDays(5).toString());
    }

    @Test
    @DisplayName("asking for the task anyway creates it without a deadline the document does not state")
    void taskOnRequestHasNoInventedDeadline() {
        Account account = account();
        UUID documentId = uploadAndRun(account, "alte-rechnung.txt",
                invoice(LocalDate.now().minusYears(1), "Zahlbar innerhalb von 5 Tagen ohne Abzug."));

        RequestContext.runAs(account.tenantId(), () -> orchestrator.orchestrate(documentId, true));

        UUID taskId = jdbc.queryForObject(
                "select id from tasks where source_document_id = ? and source = 'DOCUMENT'", UUID.class, documentId);
        assertThat(count("select count(*) from task_deadlines where task_id = ?", taskId))
                .as("no date from the day of the upload, no default number of days").isZero();
        assertThat(jdbc.queryForObject("select due_at from tasks where id = ?", java.sql.Timestamp.class, taskId))
                .isNull();
        assertThat(jdbc.queryForObject("select priority from tasks where id = ?", String.class, taskId))
                .as("an old document is not escalated").isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("a document that states no deadline gets a task without one, not today plus a number of days")
    void noStatedDeadlineMeansNoDeadline() {
        Account account = account();
        UUID documentId = uploadAndRun(account, "ohne-frist.txt", """
                Stadtwerke Musterstadt GmbH
                Letzte Mahnung
                Rechnungsnummer: RE-%d
                Mahngebühr: 5,00 EUR
                """.formatted(System.nanoTime() % 100000));

        UUID taskId = jdbc.queryForObject(
                "select id from tasks where source_document_id = ? and source = 'DOCUMENT'", UUID.class, documentId);
        assertThat(count("select count(*) from task_deadlines where task_id = ?", taskId)).isZero();
        assertThat(jdbc.queryForObject("select due_at from tasks where id = ?", java.sql.Timestamp.class, taskId)).isNull();
    }

    @Test
    @DisplayName("a form for a closed period shows the period and gets no deadline of the platform's invention")
    void formForAClosedPeriod() {
        Account account = account();
        UUID documentId = upload(account, "eks.txt", """
                Jobcenter Musterstadt
                Bedarfsgemeinschaft Nr. 123/%d
                Erklärung zum Einkommen aus selbständiger Tätigkeit (EKS)
                Bewilligungszeitraum: 01.11.2025 bis 31.12.2025
                """.formatted(System.nanoTime() % 100000));
        runOutbox();
        // Letters of the authorities always need a person's confirmation of their type first.
        UUID reviewId = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject(
                "select id from document_reviews where document_id = ? and status = 'PENDING'", UUID.class,
                documentId));
        RequestContext.runAs(account.tenantId(), () -> {
            reviews.decide(reviewId, DocumentReview.Status.APPROVED, "ok", null, account.adminUserId());
            pipeline.advance(documentId);
        });
        runOutbox();

        // A letter of an authority may also refer to a past period (it asks for the form), and a fresh deadline
        // must never be filed away on a guess, so this stays a task: but one without a date that nobody stated.
        Map<String, Object> task = jdbc.queryForMap(
                "select id, description, due_at from tasks where source_document_id = ? and source = 'DOCUMENT'",
                documentId);
        assertThat((String) task.get("description")).contains("Zeitraum laut Dokument: 2025-11-01 – 2025-12-31");
        assertThat(task.get("due_at")).isNull();
        assertThat(count("select count(*) from task_deadlines where task_id = ?", task.get("id"))).isZero();
    }

    @Test
    @DisplayName("in a normal cabinet a document that needs a person's decision still waits for one")
    void sensitiveDocumentStaysInReview() {
        Account account = account();
        LocalDate due = LocalDate.now().plusDays(10);
        // Inkasso is one of the types the platform may not decide on its own (spec §9.3): the demo fix must not
        // turn "the type is plausible" into "the document is ready".
        UUID documentId = uploadAndRun(account, "inkasso.txt", """
                Inkasso Beispiel GmbH
                Forderungsmanagement
                Forderung: 842,50 EUR
                Frist: %s
                """.formatted(due.format(GERMAN)));

        assertThat(jdbc.queryForObject("select processing_status from documents where id = ?", String.class,
                documentId)).isEqualTo("REVIEW_REQUIRED");
        assertThat(count("select count(*) from document_reviews where document_id = ? and status = 'PENDING'",
                documentId)).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from tasks where source_document_id = ?", documentId))
                .as("no work is invented before a person has confirmed the type").isZero();
    }
}
