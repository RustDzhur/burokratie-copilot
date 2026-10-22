package de.buerokratiecopilot.orchestration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.cases.CaseService;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.contracts.ContractService;
import de.buerokratiecopilot.contracts.domain.Contract;
import de.buerokratiecopilot.deadlines.TaskDeadlineRepository;
import de.buerokratiecopilot.deadlines.domain.TaskDeadline;
import de.buerokratiecopilot.notifications.NotificationService;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * Turns an understood document into work that somebody is responsible for.
 *
 * <p>This is the step the specification calls "determine the scenario" and "organise the actions".
 * Until it existed, a document that had been read, classified and matched simply stayed in the archive:
 * the platform understood it and then did nothing. The orchestrator reads the document type's scenario
 * (table {@code document_type_scenarios}), the values extracted from the document and the people of the
 * tenant, and from them creates the case, the task with its checklist and completion criteria, the
 * deadlines, the contract card and the notification.</p>
 *
 * <p>What it deliberately does not do:</p>
 * <ul>
 *   <li>It never pays, signs, terminates, answers or acknowledges a claim. Every scenario that does
 *       something with legal or financial effect creates a task that requires approval and says so; the
 *       prepared decision goes to a person.</li>
 *   <li>It never closes anything. A task is complete when its completion criteria are met, which only a
 *       person (or a later confirmation, see {@link FollowUpService}) can establish.</li>
 *   <li>It does not state a legal deadline. A date found in a document is recorded as "stated in the
 *       document", and for legally relevant types as "assumed legal deadline, to be confirmed", never as
 *       a computed or confirmed one.</li>
 *   <li>It does not guess an owner. With one active member the answer is unambiguous; with several, the
 *       case's existing owner is used, and otherwise the task goes to the manual distribution queue and
 *       the tenant's owners are told so.</li>
 *   <li>It is idempotent: a second delivery of the event finds the task it created and changes nothing.</li>
 * </ul>
 */
@Service
public class CaseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CaseOrchestrator.class);

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String FALLBACK_TYPE = "unbekannt";

    private final JdbcTemplate jdbc;
    private final CaseService cases;
    private final TaskService tasks;
    private final TaskDeadlineRepository deadlines;
    private final ContractService contracts;
    private final CalendarEventService calendar;
    private final NotificationService notifications;
    private final AuditService audit;
    private final EntityManager entityManager;
    private final OwnerNotifier owners;
    private final de.buerokratiecopilot.shared.settings.SettingsService settings;

    public CaseOrchestrator(JdbcTemplate jdbc, CaseService cases, TaskService tasks,
                            TaskDeadlineRepository deadlines, ContractService contracts,
                            CalendarEventService calendar, NotificationService notifications,
                            AuditService audit, EntityManager entityManager, OwnerNotifier owners,
                            de.buerokratiecopilot.shared.settings.SettingsService settings) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.tasks = tasks;
        this.deadlines = deadlines;
        this.contracts = contracts;
        this.calendar = calendar;
        this.notifications = notifications;
        this.audit = audit;
        this.entityManager = entityManager;
        this.owners = owners;
        this.settings = settings;
    }

    /** What happened to a document, in words a person can read. */
    public record Outcome(String result, String explanation, UUID caseId, UUID taskId, UUID contractId) {

        static Outcome skipped(String explanation) {
            return new Outcome("SKIPPED", explanation, null, null, null);
        }
    }

    /** The scenario row of a document type. */
    record Scenario(String typeCode, String actionKind, String priority, String caseTypeCode,
                    boolean createsTask, String taskTitle, String completionCriteria, String checklist,
                    List<String> dueKeys, List<String> amountKeys, Integer defaultDueDays, int leadDays,
                    boolean requiresApproval, boolean requiresHumanReview, boolean legalDeadline,
                    boolean createsContract, Integer followUpDays, String followUpTitle) {
    }

    @Transactional
    public Outcome orchestrate(UUID documentId) {
        return orchestrate(documentId, false);
    }

    /**
     * @param force a person asked for the task although the document looks old: no archive decision is made, no
     *              deadline is invented and nobody is notified (the person is the one who just asked)
     */
    @Transactional
    public Outcome orchestrate(UUID documentId, boolean force) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, d.tenant_id, d.original_filename, d.sender, d.created_by, d.processing_status,
                       d.document_type_id, t.code as type_code, t.name ->> 'de' as type_name,
                       (select c.confidence from document_classifications c
                         where c.document_id = d.id and c.is_current) as confidence,
                       -- §5.2/§4.3: the client of a voucher is a contact. The old subquery joined parties through
                       -- party_id, which is no longer written - the case summary silently lost the client line.
                       (select c.display_name from contacts c where c.id = d.mandant_id) as mandant_name
                from documents d left join document_types t on t.id = d.document_type_id
                where d.id = ? and d.deleted_at is null
                """, documentId);
        if (rows.isEmpty()) {
            return Outcome.skipped("Das Dokument existiert nicht mehr.");
        }
        Map<String, Object> doc = rows.get(0);
        UUID tenantId = (UUID) doc.get("tenant_id");
        String status = String.valueOf(doc.get("processing_status"));
        String filename = String.valueOf(doc.get("original_filename"));

        if (!"READY".equals(status)) {
            return Outcome.skipped("Das Dokument wartet noch auf eine Entscheidung (Status " + status + ").");
        }

        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id, case_id from tasks
                where source_document_id = ? and source = 'DOCUMENT' and parent_task_id is null
                  and deleted_at is null
                """, documentId);
        if (!existing.isEmpty()) {
            return new Outcome("ALREADY_HANDLED", "Für dieses Dokument besteht bereits eine Aufgabe.",
                    (UUID) existing.get(0).get("case_id"), (UUID) existing.get(0).get("id"), null);
        }

        String typeCode = doc.get("type_code") == null ? FALLBACK_TYPE : String.valueOf(doc.get("type_code"));
        String typeName = doc.get("type_name") == null ? "Unbekanntes Dokument"
                : String.valueOf(doc.get("type_name"));
        Scenario scenario = scenarioOf(tenantId, typeCode);
        if (scenario == null) {
            scenario = scenarioOf(tenantId, FALLBACK_TYPE);
        }
        if (scenario == null) {
            return Outcome.skipped("Für den Dokumenttyp ist kein Szenario hinterlegt.");
        }

        Map<String, Extracted> values = extractedValues(documentId);
        LocalDate today = LocalDate.now(BERLIN);
        Extracted dueValue = firstDate(values, scenario.dueKeys());
        LocalDate documentDue = dueValue == null ? null : dueValue.date();
        String dueSnippet = dueValue == null ? null : dueValue.snippet();
        String dueDerivation = null;

        // What the document says about its own time: its date, the period it covers, a payment term in days.
        LocalDate documentDate = firstNonNull(dateOf(values, "Rechnungsdatum"), dateOf(values, "Bescheiddatum"),
                dateOf(values, "Schreibendatum"));
        LocalDate periodFrom = null;
        LocalDate periodTo = null;
        Extracted period = values.get("Zeitraum");
        if (period != null && period.normalized() != null && period.normalized().contains("/")) {
            String[] ends = period.normalized().split("/");
            try {
                periodFrom = LocalDate.parse(ends[0]);
                periodTo = LocalDate.parse(ends[1]);
            }
            catch (RuntimeException ignored) {
                // a period that is not two dates is shown as text only
            }
        }
        if (documentDue == null && documentDate != null) {
            // "zahlbar innerhalb von 5 Tagen" is a term, not a date: it counts from the date of the document. The
            // earlier code found no date, fell back to "today + 14 days" and put an invoice from last October
            // due in two weeks.
            Integer termDays = paymentTermDays(documentText(documentId));
            if (termDays != null) {
                documentDue = documentDate.plusDays(termDays);
                dueDerivation = "Berechnet: " + documentDate + " + " + termDays + " Tage (Zahlungsfrist laut Dokument)";
                dueSnippet = dueDerivation;
            }
        }
        BigDecimal amount = firstAmount(values, scenario.amountKeys());
        String reference = text(values, "Aktenzeichen", "Rechnungsnummer");

        UUID owner = ownerOf(tenantId);
        UUID actor = doc.get("created_by") != null ? (UUID) doc.get("created_by") : owner;
        if (actor == null) {
            return Outcome.skipped("Der Mandant hat keinen aktiven Besitzer, dem die Arbeit zugeordnet werden könnte.");
        }
        List<UUID> members = activeMembers(tenantId);

        if (!scenario.createsTask()) {
            String explanation = typeName + " erkannt. Es ist keine Handlung nötig: das Dokument wurde abgelegt"
                    + " und löst keine Aufgabe aus.";
            record(tenantId, documentId, filename, explanation, Map.of("type", typeCode, "result", "FILE_ONLY"));
            return new Outcome("FILE_ONLY", explanation, null, null, null);
        }

        // ---- is the document old?
        //
        // People upload their archive, not only what arrived today, so the platform must not turn a letter from last
        // year into work due in two weeks. Every date here comes from the document: the date it states, the
        // payment term counted from its own date, the period it covers. A document whose stated deadline (or
        // period, or - with nothing else to go by - date) lies well in the past is filed without a task, a calendar
        // entry or a message, and says so. Nothing is invented to fill a gap: a document that states no deadline
        // gets a task without one.
        long staleDays = Math.max(1, settings.getInt("orchestration.stale_after_days", 30));
        boolean documentOld = documentDate != null && ChronoUnit.DAYS.between(documentDate, today) > staleDays;
        boolean looksOld;
        if (documentDue != null) {
            // For a legally relevant type the document's own date must also be old: a date misread from a fresh
            // court letter must never file it away.
            boolean dueOld = ChronoUnit.DAYS.between(today, documentDue) < -staleDays;
            looksOld = dueOld && (documentDate != null ? documentOld : !scenario.legalDeadline());
        }
        else if (periodTo != null) {
            looksOld = ChronoUnit.DAYS.between(today, periodTo) < -staleDays
                    && (documentDate == null ? !scenario.legalDeadline() : documentOld);
        }
        else {
            looksOld = documentDate != null && ChronoUnit.DAYS.between(documentDate, today) > staleDays * 3;
        }
        boolean historical = looksOld;
        // A contract is registered whatever its age: the card, its dates and its notice period are the point.
        if (looksOld && !force && !"CONTRACT".equals(scenario.actionKind())) {
            String explanation = typeName + " erkannt. Das Dokument ist alt"
                    + (documentDate == null ? "" : " (Datum " + documentDate + ")")
                    + (documentDue == null ? "" : ", die Frist " + documentDue + " ist verstrichen")
                    + ": es wurde abgelegt, ohne Aufgabe, Kalendereintrag oder Benachrichtigung.";
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("result", "ARCHIVED_OLD");
            changes.put("type", typeCode);
            if (documentDate != null) {
                changes.put("documentDate", documentDate.toString());
            }
            if (documentDue != null) {
                changes.put("statedDue", documentDue.toString());
            }
            if (periodTo != null) {
                changes.put("periodTo", periodTo.toString());
            }
            record(tenantId, documentId, filename, explanation, changes);
            log.info("Document {} ({}) is old and was filed without work", documentId, typeCode);
            return new Outcome("ARCHIVED_OLD", explanation, null, null, null);
        }

        // ---- priority: the scenario's base, raised by the date the document states
        String priority = scenario.priority();
        if (documentDue != null && !historical) {
            long daysLeft = ChronoUnit.DAYS.between(today, documentDue);
            if (daysLeft < 0) {
                priority = moreUrgent(priority, "CRITICAL");
            }
            else if (daysLeft <= 3) {
                priority = moreUrgent(priority, "HIGH");
            }
        }
        String rationale = "Szenario " + typeCode + " (" + scenario.priority() + ")"
                + (documentDue == null ? "" : ", Frist laut Dokument " + documentDue)
                + (historical ? " - Dokument ist alt, Aufgabe auf Wunsch angelegt" : "");

        // ---- dates: only what the document states
        LocalDate internalDue = null;
        if (documentDue != null && !historical) {
            LocalDate lead = documentDue.minusDays(scenario.leadDays());
            internalDue = lead.isBefore(today) ? today : lead;
        }

        // ---- who is responsible
        UUID existingCase = confirmedCaseOf(documentId);
        UUID responsible = null;
        if (existingCase != null) {
            responsible = jdbc.query("select responsible_user_id from cases where id = ?",
                    rs -> rs.next() ? (UUID) rs.getObject(1) : null, existingCase);
        }
        if (responsible == null && members.size() == 1) {
            responsible = members.get(0);
        }

        // ---- the case
        UUID caseId = existingCase;
        Case caseRecord = null;
        if (caseId == null && scenario.caseTypeCode() != null) {
            try {
                String sender = doc.get("sender") == null ? null : String.valueOf(doc.get("sender"));
                String title = typeName + " – " + (sender != null && !sender.isBlank() ? sender : filename);
                caseRecord = cases.create(new CaseService.CreateRequest(tenantId,
                        title.length() > 250 ? title.substring(0, 250) : title, scenario.caseTypeCode(),
                        "Automatisch angelegt aus dem Dokument " + filename + ".",
                        Case.Priority.valueOf(priority), responsible, null, reference, null,
                        internalDue == null ? null : internalDue.atTime(9, 0).atZone(BERLIN).toInstant(),
                        List.of(typeCode)), actor);
                caseId = caseRecord.getId();
            }
            catch (RuntimeException ex) {
                log.warn("Case for document {} could not be created, the task is created without one: {}",
                        documentId, ex.getMessage());
            }
        }
        if (caseId != null) {
            // The case was saved through JPA and has not reached the database yet; the link below is plain
            // SQL, so the case has to be written first or the foreign key refuses the link.
            entityManager.flush();
            jdbc.update("""
                    insert into case_documents (case_id, document_id, tenant_id, is_primary, link_origin,
                                                confidence, linked_by)
                    values (?, ?, ?, ?, 'RULE', ?, null)
                    on conflict (case_id, document_id) do nothing
                    """, caseId, documentId, tenantId, caseRecord != null,
                    doc.get("confidence"));
            if (caseRecord != null) {
                cases.record(caseRecord, CaseEvent.EventType.DOCUMENT_LINKED, actor,
                        "Das Dokument " + filename + " wurde automatisch zugeordnet",
                        Map.of("documentId", documentId.toString(), "type", typeCode));
            }
        }

        // ---- the task
        Instant dueAt = internalDue == null ? null : internalDue.atTime(9, 0).atZone(BERLIN).toInstant();
        Instant remindAt = null;
        if (internalDue != null && internalDue.minusDays(1).isAfter(today)) {
            remindAt = internalDue.minusDays(1).atTime(9, 0).atZone(BERLIN).toInstant();
        }
        Task task = tasks.create(new TaskService.CreateRequest(tenantId, caseId, null, scenario.taskTitle(),
                describe(doc, typeName, scenario, amount, documentDue, reference, historical, documentDate,
                        periodFrom, periodTo, dueDerivation),
                Task.Priority.valueOf(priority),
                "DOCUMENT", rationale, dueAt, remindAt, scenario.completionCriteria(),
                scenario.requiresApproval(), null, responsible, List.of(typeCode), Task.Source.DOCUMENT,
                documentId), actor);
        if (scenario.checklist() != null) {
            for (String item : scenario.checklist().split("\\|")) {
                if (!item.isBlank()) {
                    tasks.addChecklistItem(task.getId(), item.strip(), true);
                }
            }
        }

        // ---- deadlines: the date the document states, and the date the platform wants work done by
        if (documentDue != null && !historical) {
            TaskDeadline stated = new TaskDeadline(UUID.randomUUID(), tenantId, task.getId(), caseId,
                    scenario.legalDeadline() ? TaskDeadline.DeadlineType.ASSUMED_LEGAL
                            : TaskDeadline.DeadlineType.DOCUMENT_DATE);
            stated.setDueDate(documentDue);
            stated.setSource(TaskDeadline.Source.DOCUMENT);
            stated.setSourceDocumentId(documentId);
            stated.setSourceSnippet(dueSnippet);
            stated.setSourcePage(dueValue == null ? null : dueValue.page());
            stated.setTimeZone("Europe/Berlin");
            stated.setCreatedBy(actor);
            stated.setNote((scenario.legalDeadline()
                    ? "Im Dokument genannte Frist. Eine rechtliche Frist ist damit nicht festgestellt; "
                            + "Zustellung und Besonderheiten müssen geprüft werden."
                    : "Im Dokument genannte Frist.")
                    + (dueDerivation != null ? " " + dueDerivation + "." : "")
                    + (historical ? " Das Datum liegt weit zurück: vermutlich ein bereits erledigter Vorgang. "
                            + "Bitte bestätigen, dass er abgeschlossen ist, oder die Aufgabe schließen." : "")
                    + " Die Zahl wurde automatisch aus dem Text gelesen und kann falsch sein.");
            deadlines.save(stated);
        }
        if (internalDue != null) {
            TaskDeadline internal = new TaskDeadline(UUID.randomUUID(), tenantId, task.getId(), caseId,
                    TaskDeadline.DeadlineType.INTERNAL);
            internal.setDueDate(internalDue);
            internal.setSource(TaskDeadline.Source.WORKFLOW);
            internal.setSourceDocumentId(documentId);
            internal.setTimeZone("Europe/Berlin");
            internal.setCreatedBy(actor);
            internal.setNote("Interner Termin: " + scenario.leadDays()
                    + " Tage vor der Frist, damit Prüfung und Rückfrage möglich bleiben.");
            deadlines.save(internal);
        }

        // ---- the contract card
        entityManager.flush();
        UUID contractId = null;
        if (scenario.createsContract()) {
            contractId = createContract(tenantId, actor, caseId, documentId, filename, typeName, typeCode,
                    values, reference, amount);
        }

        // ---- tell the person responsible, or those who must distribute the work
        if (!force) {
            notifyPeople(tenantId, task, scenario, priority, typeName, filename, documentId, responsible, owner,
                    documentDue);
        }

        String explanation = typeName + " erkannt"
                + (doc.get("confidence") == null ? "" : " (" + percent(doc.get("confidence")) + " %)")
                + ". Aufgabe „" + scenario.taskTitle() + "“ angelegt"
                + (internalDue == null ? "" : ", intern fällig am " + internalDue)
                + (caseId != null ? ", dem Vorgang zugeordnet" : "")
                + (responsible == null ? "; sie wartet auf eine Zuständige oder einen Zuständigen." : ".")
                + (scenario.requiresHumanReview()
                        ? " Dieses Schreiben darf nicht ohne menschliche Prüfung beantwortet oder anerkannt werden."
                        : "");
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("result", "TASK_CREATED");
        changes.put("type", typeCode);
        changes.put("taskId", task.getId().toString());
        changes.put("priority", priority);
        if (caseId != null) {
            changes.put("caseId", caseId.toString());
        }
        if (contractId != null) {
            changes.put("contractId", contractId.toString());
        }
        record(tenantId, documentId, filename, explanation, changes);
        log.info("Document {} ({}) became task {} (case {}, contract {})", documentId, typeCode, task.getId(),
                caseId, contractId);
        return new Outcome("TASK_CREATED", explanation, caseId, task.getId(), contractId);
    }

    // ------------------------------------------------------------------------------------------
    // contract
    // ------------------------------------------------------------------------------------------

    private UUID createContract(UUID tenantId, UUID actor, UUID caseId, UUID documentId, String filename,
                                String typeName, String typeCode, Map<String, Extracted> values,
                                String reference, BigDecimal cost) {
        List<Map<String, Object>> present = jdbc.queryForList(
                "select id from contracts where source_document_id = ? and deleted_at is null", documentId);
        if (!present.isEmpty()) {
            return (UUID) present.get(0).get("id");
        }
        LocalDate start = dateOf(values, "Beginn");
        Integer termMonths = months(values, "Laufzeit");
        Integer noticeMonths = months(values, "Kuendigungsfrist");
        LocalDate end = start != null && termMonths != null ? start.plusMonths(termMonths) : null;
        String renewal = text(values, "Verlaengerungsklausel");
        Boolean autoRenewal = renewal == null ? null
                : renewal.toLowerCase(Locale.GERMAN).contains("verlängert sich")
                        || renewal.toLowerCase(Locale.GERMAN).contains("automatisch");
        String contractType = switch (typeCode) {
            case "mietvertrag" -> "RENT";
            case "arbeitsvertrag" -> "EMPLOYMENT";
            default -> "OTHER";
        };
        String base = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
        try {
            Contract contract = contracts.create(new ContractService.CreateRequest(tenantId,
                    typeName + " – " + base, reference, contractType, caseId, null, start, end, null,
                    autoRenewal, null, noticeMonths, noticeMonths == null ? null : "MONTHS", cost,
                    cost == null ? null : "EUR", null, documentId, List.of(typeCode)), actor);
            String why = "Automatisch aus dem Dokument „" + filename + "“ gelesen (Beginn "
                    + start + (termMonths == null ? "" : ", Laufzeit " + termMonths + " Monate")
                    + (noticeMonths == null ? "" : ", Kündigungsfrist " + noticeMonths + " Monate")
                    + "). Die Angaben sind ungeprüft; bitte in der Vertragskarte bestätigen.";
            if (end != null) {
                calendarEvent(tenantId, actor, caseId, "Vertragsende: " + base, end, why, documentId);
                if (noticeMonths != null) {
                    calendarEvent(tenantId, actor, caseId, "Kündigungsfrist endet: " + base,
                            end.minusMonths(noticeMonths),
                            "Bis zu diesem Tag müsste eine Kündigung eingehen, damit der Vertrag nicht weiterläuft. "
                                    + why, documentId);
                }
            }
            if (start != null && start.isAfter(LocalDate.now(BERLIN))) {
                calendarEvent(tenantId, actor, caseId, "Vertragsbeginn: " + base, start, why, documentId);
            }
            return contract.getId();
        }
        catch (RuntimeException ex) {
            log.warn("Contract card for document {} could not be created: {}", documentId, ex.getMessage());
            return null;
        }
    }

    private void calendarEvent(UUID tenantId, UUID actor, UUID caseId, String title, LocalDate date, String note,
                               UUID documentId) {
        if (date.isBefore(LocalDate.now(BERLIN))) {
            return; // a date that has passed is history, not something to put in front of the person
        }
        try {
            calendar.create(tenantId, new CalendarEventService.Draft(title, date, null,
                    CalendarEventService.Kind.REMINDER, caseId), actor, note, documentId);
        }
        catch (RuntimeException ex) {
            log.warn("Calendar entry '{}' could not be created: {}", title, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------
    // notification
    // ------------------------------------------------------------------------------------------

    private void notifyPeople(UUID tenantId, Task task, Scenario scenario, String priority, String typeName,
                              String filename, UUID documentId, UUID responsible, UUID owner,
                              LocalDate documentDue) {
        List<UUID> recipients = new ArrayList<>();
        if (responsible != null) {
            recipients.add(responsible);
        }
        else {
            // Nobody is responsible yet: those who can distribute the work must hear about it.
            recipients.addAll(ownersOf(tenantId));
            if (recipients.isEmpty() && owner != null) {
                recipients.add(owner);
            }
        }
        Notification.Severity severity = switch (priority) {
            case "CRITICAL" -> Notification.Severity.CRITICAL;
            case "HIGH" -> Notification.Severity.WARNING;
            default -> Notification.Severity.NOTICE;
        };
        String body = typeName + " · " + filename
                + (documentDue == null ? "" : " · Frist laut Dokument: " + documentDue)
                + (responsible == null ? " · Zuständigkeit offen: bitte zuweisen" : "");
        Map<String, Object> facts = owners.documentFacts(documentId);
        for (UUID recipient : recipients) {
            try {
                Map<String, Object> parameters = new LinkedHashMap<>();
                // Facts for the screen, which writes the sentence in the user's language.
                parameters.putAll(facts);
                parameters.put("taskTitle", scenario.taskTitle());
                parameters.put("dueDate", documentDue == null ? null : documentDue.toString());
                parameters.put("unassigned", responsible == null);
                parameters.put("title", scenario.taskTitle());
                parameters.put("body", body);
                parameters.put("documentId", documentId.toString());
                parameters.put("taskId", task.getId().toString());
                parameters.put("priority", priority);
                notifications.notify(new NotificationService.Request(tenantId, recipient, null,
                        "document.action_required", Notification.Category.TASK, severity,
                        "document.action_required", "document.action_required.body", parameters, "tasks",
                        task.getId(), "/documents/" + documentId,
                        "medizinische_rechnung".equals(scenario.typeCode()), "doc-action-" + documentId));
            }
            catch (RuntimeException ex) {
                // A notification that cannot be delivered must not undo the work that was created.
                log.warn("Notification about document {} could not be stored: {}", documentId, ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // reading
    // ------------------------------------------------------------------------------------------

    private Scenario scenarioOf(UUID tenantId, String typeCode) {
        List<Scenario> rows = jdbc.query("""
                select * from document_type_scenarios
                where document_type_code = ? and (tenant_id = ? or tenant_id is null)
                order by tenant_id nulls last limit 1
                """, (rs, i) -> new Scenario(rs.getString("document_type_code"), rs.getString("action_kind"),
                rs.getString("priority"), rs.getString("case_type_code"), rs.getBoolean("creates_task"),
                rs.getString("task_title"), rs.getString("completion_criteria"), rs.getString("checklist"),
                split(rs.getString("due_field_keys")), split(rs.getString("amount_field_keys")),
                (Integer) rs.getObject("default_due_days"), rs.getInt("lead_days"),
                rs.getBoolean("requires_approval"), rs.getBoolean("requires_human_review"),
                rs.getBoolean("legal_deadline"), rs.getBoolean("creates_contract"),
                (Integer) rs.getObject("follow_up_days"), rs.getString("follow_up_title")),
                typeCode, tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return List.of(csv.split(",")).stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** A value found in the document, with the line it was found on. */
    record Extracted(String value, String normalized, String snippet, Integer page) {

        LocalDate date() {
            try {
                return normalized == null ? null : LocalDate.parse(normalized);
            }
            catch (RuntimeException ex) {
                return null;
            }
        }
    }

    private Map<String, Extracted> extractedValues(UUID documentId) {
        Map<String, Extracted> result = new LinkedHashMap<>();
        jdbc.query("""
                select field_key, field_value, normalized_value, source_snippet, source_page
                from document_extractions where document_id = ? order by created_at
                """, rs -> {
            result.putIfAbsent(rs.getString("field_key"), new Extracted(rs.getString("field_value"),
                    rs.getString("normalized_value"), rs.getString("source_snippet"),
                    (Integer) rs.getObject("source_page")));
        }, documentId);
        return result;
    }

    private static Extracted firstDate(Map<String, Extracted> values, List<String> keys) {
        for (String key : keys) {
            Extracted candidate = values.get(key);
            if (candidate != null && candidate.date() != null) {
                return candidate;
            }
        }
        return null;
    }

    private static BigDecimal firstAmount(Map<String, Extracted> values, List<String> keys) {
        for (String key : keys) {
            Extracted candidate = values.get(key);
            if (candidate != null && candidate.normalized() != null) {
                try {
                    return new BigDecimal(candidate.normalized());
                }
                catch (NumberFormatException ignored) {
                    // not an amount: try the next key
                }
            }
        }
        return null;
    }

    private static LocalDate dateOf(Map<String, Extracted> values, String key) {
        Extracted candidate = values.get(key);
        return candidate == null ? null : candidate.date();
    }

    private static Integer months(Map<String, Extracted> values, String key) {
        Extracted candidate = values.get(key);
        if (candidate == null || candidate.normalized() == null) {
            return null;
        }
        try {
            return Integer.valueOf(candidate.normalized().strip());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... candidates) {
        for (T candidate : candidates) {
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String documentText(UUID documentId) {
        String text = jdbc.queryForObject("select coalesce(extracted_text, '') from documents where id = ?",
                String.class, documentId);
        return text == null ? "" : text;
    }

    private static final java.util.regex.Pattern TERM_IN_DAYS = java.util.regex.Pattern.compile(
            "(?:(?:innerhalb|binnen)\\s+(?:von\\s+)?|zahlungsziel\\s*:?\\s*|zahlungsfrist\\s*:?\\s*"
                    + "|zahlbar\\s+(?:innerhalb\\s+)?(?:von\\s+)?|(?:протягом|впродовж|в течение|через)\\s+)"
                    + "(\\d{1,3})\\s*(?:kalender)?(?:tag|дн|день|дней)",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    private static final java.util.regex.Pattern NET_DAYS = java.util.regex.Pattern.compile(
            "(\\d{1,3})\\s*tage\\s*(?:netto|nach|ab)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * A payment term written as a number of days ("zahlbar innerhalb von 5 Tagen", "Zahlungsziel: 14 Tage",
     * "30 Tage netto"), or null. Working-day terms ("Werktage") are not read: counting them needs a holiday
     * calendar, and a wrong date is worse than none.
     */
    static Integer paymentTermDays(String text) {
        if (text == null || text.isBlank() || text.toLowerCase(Locale.GERMAN).matches("(?s).*(werktag|arbeitstag).*")) {
            return null;
        }
        for (java.util.regex.Pattern pattern : List.of(TERM_IN_DAYS, NET_DAYS)) {
            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                int days = Integer.parseInt(matcher.group(1));
                if (days >= 1 && days <= 120) {
                    return days;
                }
            }
        }
        return null;
    }

    private static String text(Map<String, Extracted> values, String... keys) {
        for (String key : keys) {
            Extracted candidate = values.get(key);
            if (candidate != null && candidate.value() != null && !candidate.value().isBlank()) {
                return candidate.value();
            }
        }
        return null;
    }

    private UUID confirmedCaseOf(UUID documentId) {
        List<UUID> found = jdbc.query("""
                select target_id from document_matches
                where document_id = ? and match_type = 'CASE' and status = 'CONFIRMED'
                order by score desc limit 1
                """, (rs, i) -> (UUID) rs.getObject(1), documentId);
        return found.isEmpty() ? null : found.get(0);
    }

    private UUID ownerOf(UUID tenantId) {
        List<UUID> owners = ownersOf(tenantId);
        return owners.isEmpty() ? null : owners.get(0);
    }

    private List<UUID> ownersOf(UUID tenantId) {
        return jdbc.query("""
                select user_id from memberships
                where tenant_id = ? and status = 'ACTIVE' and is_owner
                order by joined_at nulls last
                """, (rs, i) -> (UUID) rs.getObject(1), tenantId);
    }

    private List<UUID> activeMembers(UUID tenantId) {
        return jdbc.query("""
                select user_id from memberships
                where tenant_id = ? and status = 'ACTIVE'
                order by is_owner desc, joined_at nulls last
                """, (rs, i) -> (UUID) rs.getObject(1), tenantId);
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private static String describe(Map<String, Object> doc, String typeName, Scenario scenario,
                                   BigDecimal amount, LocalDate documentDue, String reference,
                                   boolean historical, LocalDate documentDate, LocalDate periodFrom,
                                   LocalDate periodTo, String dueDerivation) {
        StringBuilder text = new StringBuilder();
        text.append("Dokument: ").append(doc.get("original_filename")).append('\n');
        if (doc.get("sender") != null) {
            text.append("Absender: ").append(doc.get("sender")).append('\n');
        }
        if (doc.get("mandant_name") != null) {
            text.append("Betrifft: ").append(doc.get("mandant_name")).append('\n');
        }
        text.append("Dokumenttyp: ").append(typeName);
        if (doc.get("confidence") != null) {
            text.append(" (Sicherheit ").append(percent(doc.get("confidence"))).append(" %)");
        }
        text.append('\n');
        if (reference != null) {
            text.append("Aktenzeichen / Referenz: ").append(reference).append('\n');
        }
        if (amount != null) {
            text.append("Betrag: ").append(amount.toPlainString())
                    .append(" EUR (aus dem Dokument gelesen, ungeprüft)\n");
        }
        if (documentDate != null) {
            text.append("Datum des Dokuments: ").append(documentDate).append('\n');
        }
        if (periodFrom != null && periodTo != null) {
            text.append("Zeitraum laut Dokument: ").append(periodFrom).append(" – ").append(periodTo).append('\n');
        }
        if (dueDerivation != null) {
            text.append(dueDerivation).append('\n');
        }
        if (documentDue != null) {
            text.append("Frist laut Dokument: ").append(documentDue).append(historical
                    ? " (liegt weit zurück: vermutlich ein bereits erledigter Vorgang - bitte bestätigen "
                            + "oder die Aufgabe schließen)" : "").append('\n');
        }
        if (scenario.requiresHumanReview()) {
            text.append("\nWichtig: Dieses Schreiben darf nicht automatisch anerkannt, beantwortet oder bezahlt "
                    + "werden. Bitte zuerst prüfen; bei rechtlichen Folgen fachkundige Beratung einholen.\n");
        }
        else if (scenario.requiresApproval()) {
            text.append("\nHinweis: Zahlung oder Versand erfolgen erst nach Ihrer Freigabe.\n");
        }
        return text.toString().stripTrailing();
    }

    private static int percent(Object confidence) {
        return confidence instanceof BigDecimal value
                ? value.multiply(BigDecimal.valueOf(100)).intValue() : 0;
    }

    private static String moreUrgent(String current, String candidate) {
        List<String> order = List.of("CRITICAL", "HIGH", "NORMAL", "LOW", "INFORMATIONAL");
        return order.indexOf(candidate) < order.indexOf(current) ? candidate : current;
    }

    private void record(UUID tenantId, UUID documentId, String filename, String explanation,
                        Map<String, Object> changes) {
        audit.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.DOCUMENT, "DOCUMENT_ORCHESTRATED")
                .tenant(tenantId)
                .entity("documents", documentId, filename)
                .description(explanation)
                .changes(changes)
                .build());
    }
}
