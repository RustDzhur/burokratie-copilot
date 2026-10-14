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
import de.buerokratiecopilot.identity.onboarding.OnboardingService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.outbox.OutboxDispatcher;
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
    private OnboardingService onboarding;

    @Autowired
    private DocumentService documents;

    @Autowired
    private OutboxDispatcher outbox;

    @Autowired
    private TaskService tasks;

    @Autowired
    private ReviewService reviews;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    @Autowired
    private OrchestrationWatchdog watchdog;

    @Autowired
    private JdbcTemplate jdbc;

    private record Account(UUID tenantId, UUID adminUserId) {
    }

    private Account account() {
        var created = onboarding.onboard(new OnboardingService.Request(
                "orc-" + UUID.randomUUID().toString().substring(0, 12), "Orchestrierung", "PRIVATE_PERSON",
                "orc-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de", "Erika Mustermann",
                "de", "Europe/Berlin", "DE", "Orchestrierung"));
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
    @DisplayName("a contract becomes a contract card with the dates it states")
    void contractCard() {
        Account account = account();
        LocalDate start = LocalDate.now().plusDays(30);
        UUID documentId = uploadAndRun(account, "vertrag.txt", """
                Vertrag
                Vertragsparteien: Beispiel Internet GmbH und Erika Mustermann
                Vertragsgegenstand: Internetanschluss
                Vertragsbeginn: %s
                Laufzeit: 24 Monate
                Kündigungsfrist: 3 Monate
                Vergütung: 39,90 EUR
                Verlängerung: Der Vertrag verlängert sich automatisch um 12 Monate.
                Vertragsnummer: V-%d
                """.formatted(start.format(GERMAN), System.nanoTime()));

        // A contract is a type that always needs a person's confirmation before anything acts on it.
        assertThat(jdbc.queryForObject("select processing_status from documents where id = ?", String.class,
                documentId)).isEqualTo("REVIEW_REQUIRED");
        UUID reviewId = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject(
                "select id from document_reviews where document_id = ? and status = 'PENDING'", UUID.class,
                documentId));
        RequestContext.runAs(account.tenantId(), () -> {
            reviews.decide(reviewId, DocumentReview.Status.APPROVED, "Vertrag bestätigt", null,
                    account.adminUserId());
            pipeline.advance(documentId);
        });
        runOutbox();

        Map<String, Object> contract = jdbc.queryForMap("""
                select start_date, end_date, notice_period_value, auto_renewal, status
                from contracts where source_document_id = ?
                """, documentId);
        assertThat(contract.get("start_date").toString()).isEqualTo(start.toString());
        assertThat(contract.get("end_date").toString()).isEqualTo(start.plusMonths(24).toString());
        assertThat(contract.get("auto_renewal")).isEqualTo(true);

        Integer events = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from calendar_events where tenant_id = ? and deleted_at is null
                """, Integer.class, account.tenantId()));
        assertThat(events).as("start, end and the end of the notice period are in the calendar").isEqualTo(3);
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
}
