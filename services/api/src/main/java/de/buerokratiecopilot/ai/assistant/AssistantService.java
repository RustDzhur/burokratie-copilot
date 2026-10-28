package de.buerokratiecopilot.ai.assistant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.ai.security.ToolPolicy;
import de.buerokratiecopilot.ai.security.UntrustedContent;
import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.deadlines.DeadlineService;
import de.buerokratiecopilot.deadlines.domain.TaskDeadline;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The assistant as a way of operating the platform (spec §25, E-007).
 *
 * <p>The assistant may read and may propose. It may not act, and the two acceptances are the reason.</p>
 *
 * <p><strong>It does not see another tenant's data.</strong> That is not enforced here but by row level
 * security, which is the authority; what this class does is never provide a way around it. Every read it
 * performs runs in the caller's tenant context, and there is no parameter through which another tenant
 * could be named.</p>
 *
 * <p><strong>Sending, paying and terminating need an explicit confirmation.</strong> A proposal is a
 * record with an identifier; applying it is a second, separate act by the same person. The assistant can
 * put a letter in front of somebody; it cannot post it. This is the same rule as in E-006, seen from the
 * user's side rather than from the tool's.</p>
 *
 * <p>The history keeps what makes a turn reproducible: the model, the instruction version, the tool, the
 * parameters and who asked. It does not keep the conversation, because a conversation is a document.</p>
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    /** How long a proposal stays valid; a proposal nobody confirmed should not wait forever. */
    private static final long PROPOSAL_VALID_SECONDS = 900;

    private final JdbcTemplate jdbc;
    private final ToolPolicy policy;
    private final de.buerokratiecopilot.radar.RadarQueryService radar;
    private final DocumentService documents;
    private final CalendarEventService calendar;
    private final DeadlineService deadlines;
    private final OutboundMessageService outbound;

    public AssistantService(JdbcTemplate jdbc, ToolPolicy policy,
                            de.buerokratiecopilot.radar.RadarQueryService radar,
                            DocumentService documents, CalendarEventService calendar,
                            DeadlineService deadlines, OutboundMessageService outbound) {
        this.jdbc = jdbc;
        this.policy = policy;
        this.radar = radar;
        this.documents = documents;
        this.calendar = calendar;
        this.deadlines = deadlines;
        this.outbound = outbound;
    }

    /** What a request produced. */
    public record Answer(String kind, String message, UUID proposalId, String toolName, String preview,
                         Map<String, Object> result, String refusalCode) {

        /** Whether the assistant only read something. */
        public boolean isAnswer() {
            return "ANSWER".equals(kind);
        }

        /** Whether a person still has to confirm. */
        public boolean needsConfirmation() {
            return "PROPOSAL".equals(kind);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            map.put("message", message);
            map.put("proposalId", proposalId == null ? null : proposalId.toString());
            map.put("toolName", toolName);
            map.put("preview", preview);
            map.put("result", result);
            map.put("refusalCode", refusalCode);
            return map;
        }
    }

    /** A turn of the conversation. */
    public record Turn(UUID tenantId, UUID userId, String question, String model,
                       String instructionVersion, String instructionHash,
                       UntrustedContent.Origin triggerOrigin, String triggerReference) {
    }

    /**
     * Handles one turn.
     *
     * <p>A read-only tool is run and its result returned. A tool with an effect is turned into a proposal
     * with a preview, and nothing happens until somebody confirms it.</p>
     */
    @Transactional
    public Answer ask(Turn turn, String toolName, Map<String, Object> parameters) {
        if (turn.userId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Assistent handelt für eine Person; ohne sie gibt es niemanden zu fragen");
        }

        ToolPolicy.Decision decision = policy.decide(turn.tenantId(),
                new ToolPolicy.Request(toolName, parameters, turn.userId(), turn.triggerOrigin(),
                        turn.triggerReference()));

        Intent intent = recordIntent(turn, toolName, parameters);

        if (decision.needsConfirmation()) {
            // A proposal is a record, not an action: the assistant may put a letter in front of somebody,
            // it may not post it.
            jdbc.update("""
                    update ai_jobs
                    set input = input || '{"awaitingConfirmation": true}'::jsonb
                    where id = ? and tenant_id = ?
                    """, intent.jobId(), turn.tenantId());

            Intent stored = new Intent(intent.jobId(), null);
            log.info("Assistant proposed {} for {} (job {})", toolName, turn.userId(), intent.jobId());
            return new Answer("PROPOSAL",
                    "Der Assistent schlägt vor, '" + toolName + "' auszuführen. Das geschieht erst, wenn "
                            + "Sie es bestätigen.",
                    intent.jobId(), toolName, preview(turn, toolName, parameters), Map.of(), null);
        }

        if (!decision.allowed()) {
            jdbc.update("""
                    update ai_jobs set status = 'CANCELLED', finished_at = now(), error_message = ?
                    where id = ? and tenant_id = ?
                    """, decision.code(), intent.jobId(), turn.tenantId());
            log.info("Assistant refused {}: {}", toolName, decision.code());
            return new Answer("REFUSED", decision.reason(), null, toolName, null, Map.of(),
                    decision.code());
        }

        Map<String, Object> result = executeReadOnly(turn, toolName, parameters);
        jdbc.update("""
                update ai_jobs set status = 'SUCCEEDED', finished_at = now()
                where id = ? and tenant_id = ?
                """, intent.jobId(), turn.tenantId());

        return new Answer("ANSWER", "Ergebnis von '" + toolName + "'", null, toolName, null, result, null);
    }

    /**
     * Applies a proposal.
     *
     * <p>The confirmation is a separate act by the same person who asked, in the same tenant, and it
     * happens once. All three conditions are checked, because each of them is a way the rule could be
     * undone: somebody else confirming, confirming twice, or confirming in another tenant.</p>
     */
    @Transactional
    public Answer confirm(UUID tenantId, UUID userId, UUID proposalId) {
        Map<String, Object> job = jdbc.queryForMap("""
                select input ->> 'tool' as tool_name,
                       input ->> 'awaitingConfirmation' as awaiting, status, requested_by, model,
                       input -> 'parameters' as parameters,
                       instruction_version, created_at
                from ai_jobs where id = ? and tenant_id = ?
                """, proposalId, tenantId);

        if (!"QUEUED".equals(String.valueOf(job.get("status")))
                || !"true".equals(String.valueOf(job.get("awaiting")))) {
            throw ApiException.conflict("Dieser Vorschlag wartet nicht auf eine Bestätigung");
        }
        if (job.get("requested_by") == null || !job.get("requested_by").equals(userId)) {
            // Confirming somebody else's proposal would let one person act with another's rights.
            throw ApiException.forbidden(
                    "Nur wer den Vorschlag angefragt hat, kann ihn bestätigen");
        }
        Instant created = ((java.sql.Timestamp) job.get("created_at")).toInstant();
        if (created.plusSeconds(PROPOSAL_VALID_SECONDS).isBefore(Instant.now())) {
            throw ApiException.conflict("Der Vorschlag ist abgelaufen und muss neu erstellt werden");
        }

        String toolName = String.valueOf(job.get("tool_name"));
        // Re-checked because the world may have changed since the proposal was made: the tenant may have
        // withdrawn the tool and the user may have lost the right. NEEDS_CONFIRMATION is not a refusal
        // here - it is the question this method is the answer to - so only a genuine refusal blocks.
        ToolPolicy.Decision decision = policy.decide(tenantId,
                new ToolPolicy.Request(toolName, Map.of(), userId, UntrustedContent.Origin.PLATFORM, null));
        if (!decision.allowed() && !decision.needsConfirmation()) {
            throw ApiException.conflict("Das Werkzeug '" + toolName + "' ist jetzt nicht zulässig: "
                    + decision.reason());
        }

        jdbc.update("""
                update ai_jobs set status = 'SUCCEEDED', finished_at = now(),
                       input = input || '{"awaitingConfirmation": false, "confirmed": true}'::jsonb
                where id = ? and tenant_id = ? and status = 'QUEUED'
                  and input ->> 'awaitingConfirmation' = 'true'
                """, proposalId, tenantId);

        // The tool runs here, not at the moment it was proposed. Marking the job SUCCEEDED and returning
        // "confirmed: true" was all this method did, so the screen said "the proposal was confirmed and
        // executed" while nothing had been executed - the worst kind of lie, because it is a success message.
        Map<String, Object> parameters = parametersOf(job.get("parameters"));
        AssistantService.Turn confirming = new AssistantService.Turn(tenantId, userId, null,
                String.valueOf(job.get("model")), String.valueOf(job.get("instruction_version")), null,
                UntrustedContent.Origin.PLATFORM, null);
        Map<String, Object> result = executeReadOnly(confirming, toolName, parameters);

        log.info("Assistant proposal {} confirmed and executed by {}", proposalId, userId);
        Map<String, Object> answer = new java.util.LinkedHashMap<>(result);
        answer.put("confirmed", true);
        return new Answer("ANSWER", "Der Vorschlag wurde bestätigt und ausgeführt", proposalId, toolName,
                null, answer, null);
    }

    /** Rejects a proposal without applying it. */
    @Transactional
    public void reject(UUID tenantId, UUID userId, UUID proposalId) {
        int updated = jdbc.update("""
                update ai_jobs set status = 'CANCELLED', finished_at = now(),
                       input = input || '{"awaitingConfirmation": false, "rejected": true}'::jsonb
                where id = ? and tenant_id = ? and requested_by = ? and status = 'QUEUED'
                  and input ->> 'awaitingConfirmation' = 'true'
                """, proposalId, tenantId, userId);
        if (updated == 0) {
            throw ApiException.conflict("Dieser Vorschlag kann nicht abgelehnt werden");
        }
    }

    /** The proposals waiting for this person. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openProposals(UUID tenantId, UUID userId) {
        return jdbc.queryForList("""
                select id, input ->> 'tool' as tool_name, model, instruction_version, created_at
                from ai_jobs
                where tenant_id = ? and requested_by = ? and status = 'QUEUED'
                  and input ->> 'awaitingConfirmation' = 'true'
                order by created_at desc
                """, tenantId, userId);
    }

    /** The history of what the assistant was asked, with the model and instruction version. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(UUID tenantId, UUID userId, int limit) {
        return jdbc.queryForList("""
                select id, input ->> 'tool' as tool_name, status, model, instruction_version,
                       created_at, finished_at
                from ai_jobs
                where tenant_id = ? and requested_by = ?
                order by created_at desc limit ?
                """, tenantId, userId, Math.max(1, Math.min(limit, 100)));
    }

    /** What a proposal would do, written out for a person to judge. */
    private String preview(Turn turn, String toolName, Map<String, Object> parameters) {
        StringBuilder preview = new StringBuilder("Werkzeug: ").append(toolName);
        String human = humanPreview(turn, toolName, parameters);
        if (human != null && !human.isBlank()) {
            preview.append("\n").append(human);
        }
        if (parameters != null && !parameters.isEmpty()) {
            parameters.forEach((key, value) -> preview.append("\n  ").append(key).append(": ")
                    .append(value == null ? "—" : String.valueOf(value)));
        }
        return preview.toString();
    }

    /**
     * The same preview as a sentence about the tenant's own objects: what the person confirms is a document
     * with a name, a case with a reference, a task with a title — not an identifier.
     */
    private String humanPreview(Turn turn, String toolName, Map<String, Object> parameters) {
        try {
            return switch (toolName) {
                case "delete_document" -> {
                    String name = documentName(turn.tenantId(), parameters);
                    yield "Das Dokument \u201e" + name + "\u201c wird gelöscht. Es verschwindet aus allen "
                            + "Listen und ist für den Betreiber wiederherstellbar.";
                }
                case "move_document" -> {
                    UUID caseId = uuidOrNull(parameters == null ? null : parameters.get("caseId"));
                    String name = documentName(turn.tenantId(), parameters);
                    if (caseId == null) {
                        yield "Das Dokument \u201e" + name + "\u201c wird aus allen Vorgängen entfernt.";
                    }
                    yield "Das Dokument \u201e" + name + "\u201c wird in den Vorgang "
                            + caseReference(turn.tenantId(), caseId) + " verschoben.";
                }
                case "update_case" -> {
                    UUID caseId = uuidOrNull(parameters == null ? null : parameters.get("caseId"));
                    if (caseId == null) {
                        yield null;
                    }
                    yield "Der Vorgang " + caseReference(turn.tenantId(), caseId) + " wird geändert.";
                }
                case "create_calendar_event" -> "Der Termin \u201e"
                        + (parameters == null ? "" : String.valueOf(parameters.getOrDefault("title", "")))
                        + "\u201c wird in den Kalender eingetragen.";
                case "create_deadline" -> "Eine Frist wird zu einer Aufgabe notiert.";
                case "send_email" -> "Eine Nachricht an "
                        + (parameters == null ? "" : String.valueOf(parameters.getOrDefault("to", "")))
                        + " wird als Entwurf angelegt. Versendet wird erst, wenn ein Mensch sie freigibt.";
                default -> null;
            };
        }
        catch (RuntimeException lookupFailed) {
            // A preview is help for a person, not a lookup that may fail the proposal: names that cannot be
            // read fall back to the generic parameter list above.
            return null;
        }
    }

    private String documentName(UUID tenantId, Map<String, Object> parameters) {
        UUID documentId = uuidOrNull(parameters == null ? null : parameters.get("documentId"));
        if (documentId == null) {
            return "unbekannt";
        }
        List<String> names = jdbc.queryForList("""
                select original_filename from documents where id = ? and tenant_id = ? and deleted_at is null
                """, String.class, documentId, tenantId);
        return names.isEmpty() ? "unbekannt" : names.getFirst();
    }

    private String caseReference(UUID tenantId, UUID caseId) {
        List<String> refs = jdbc.queryForList("""
                select reference from cases where id = ? and tenant_id = ? and deleted_at is null
                """, String.class, caseId, tenantId);
        return refs.isEmpty() ? "unbekannt" : refs.getFirst();
    }

    /**
     * Creates a task on behalf of the person who confirmed the proposal.
     *
     * <p>The assignee is the requester, not somebody the model chose: an assistant that can assign work to
     * other people is an assistant that can quietly hand somebody a job. The title comes from the proposal and
     * is required; the due date is optional and parsed only when it is a real date.</p>
     */
    private Map<String, Object> createTask(Turn turn, Map<String, Object> parameters) {
        String title = parameters == null ? null : String.valueOf(parameters.get("title"));
        if (title == null || title.isBlank() || "null".equals(title)) {
            throw new de.buerokratiecopilot.shared.error.ApiException(
                    de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST,
                    "Eine Aufgabe braucht einen Titel");
        }
        java.time.LocalDate due = null;
        Object dueRaw = parameters == null ? null : parameters.get("dueDate");
        if (dueRaw != null && !String.valueOf(dueRaw).isBlank()) {
            try {
                due = java.time.LocalDate.parse(String.valueOf(dueRaw));
            }
            catch (java.time.format.DateTimeParseException ex) {
                // A date the model made up is not a reason to fail the person's confirmed action; the task
                // is created without one and the answer says so.
                due = null;
            }
        }
        final java.time.LocalDate dueDate = due;
        java.util.UUID taskId = jdbc.queryForObject("""
                insert into tasks (id, tenant_id, title, status, priority, priority_source, source,
                                   created_by_user_id, due_at)
                values (gen_random_uuid(), ?, ?, 'NEW', 'NORMAL', 'AI', 'AI', ?, ?)
                returning id
                """, java.util.UUID.class, turn.tenantId(), title.strip(), turn.userId(),
                dueDate == null ? null : java.sql.Timestamp.from(dueDate.atStartOfDay(
                        java.time.ZoneId.systemDefault()).toInstant()));
        jdbc.update("""
                insert into task_assignees (id, task_id, tenant_id, user_id, role, assigned_at)
                values (gen_random_uuid(), ?, ?, ?, 'ASSIGNEE', now())
                """, taskId, turn.tenantId(), turn.userId());
        return Map.of("taskId", taskId.toString(), "title", title.strip(),
                "dueDate", dueDate == null ? "keine" : dueDate.toString(),
                "created", true);
    }

    /** The stored parameters of a proposal, back from JSON. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parametersOf(Object stored) {
        if (stored == null) {
            return Map.of();
        }
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    String.valueOf(stored), Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new java.util.LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // A proposal whose parameters cannot be read is refused rather than executed with guesses.
            throw de.buerokratiecopilot.shared.error.ApiException.conflict(
                    "Die Parameter dieses Vorschlags sind nicht lesbar");
        }
        return Map.of();
    }

    /** Records the intent, which is the history entry. */
    private Intent recordIntent(Turn turn, String toolName, Map<String, Object> parameters) {
        UUID jobId = jdbc.queryForObject("""
                insert into ai_jobs (id, tenant_id, job_type, status, priority, input,
                                     instruction_version, model, requested_by, attempts, max_attempts)
                values (gen_random_uuid(), ?, 'CHAT', 'QUEUED', 5, ?::jsonb, ?, ?, ?, 0, 3)
                returning id
                """, UUID.class, turn.tenantId(), jsonOf(toolName, turn.question(), parameters),
                turn.instructionVersion(), turn.model(), turn.userId());
        return new Intent(jobId, turn.instructionVersion());
    }

    private record Intent(UUID jobId, String instructionVersion) {
    }

    /**
     * Runs a read-only tool.
     *
     * <p>The queries here are the only place the assistant reads data, and every one of them is scoped to
     * the tenant. Row level security would refuse anything else anyway, which is the point: the assistant
     * does not have to be trusted to behave.</p>
     */
    private Map<String, Object> executeReadOnly(Turn turn, String toolName,
                                                Map<String, Object> parameters) {
        return switch (toolName) {
            case "list_open_tasks" -> Map.of("tasks", jdbc.queryForList("""
                    select id, title, status, due_at from tasks
                    where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                    order by due_at nulls last limit 20
                    """, turn.tenantId()));
            case "search_documents" -> Map.of("documents", jdbc.queryForList("""
                    select id, original_filename, processing_status, created_at from documents
                    where tenant_id = ? and deleted_at is null
                    order by created_at desc limit 20
                    """, turn.tenantId()));
            case "read_document", "summarize" -> describeDocument(turn, parameters);
            // The Radar tools go through the same query service as the screen, so what a person may not see in
            // the Radar (a private case) the assistant cannot read out either.
            case "list_radar_findings" -> Map.of("findings", radar.list(turn.tenantId(), turn.userId(),
                    new de.buerokratiecopilot.radar.RadarQueryService.Filter(null, null, null, null, null, null,
                            true), 0, 10).get("items"));
            case "explain_finding" -> explainFinding(turn, parameters);
            // The one write the assistant may perform, and only after a person confirmed the proposal.
            // It was registered in the policy and not implemented here, so the assistant could offer it and
            // then fail - the worst of both: the tool exists in the catalogue and does nothing.
            case "create_task" -> createTask(turn, parameters);
            case "list_contracts" -> Map.of("contracts", jdbc.queryForList("""
                    select id, title, contract_type, status, start_date, end_date, auto_renewal,
                           notice_period_value, notice_period_unit
                    from contracts
                    where tenant_id = ? and deleted_at is null
                    order by end_date nulls last limit 20
                    """, turn.tenantId()));
            case "list_screens" -> Map.of("screens", SCREENS.stream()
                    .map(screen -> Map.of("id", screen.id(), "href", screen.href(), "title", screen.title()))
                    .toList());
            case "list_calendar_events" -> listCalendarEvents(turn, parameters);
            case "create_calendar_event" -> createCalendarEvent(turn, parameters);
            case "list_deadlines" -> Map.of("deadlines", jdbc.queryForList("""
                    select d.id::text as id, d.deadline_type, d.due_date, d.due_at, d.note,
                           t.id::text as task_id, t.title, coalesce(d.case_id, t.case_id)::text as case_id
                    from task_deadlines d
                    join tasks t on t.id = d.task_id and t.deleted_at is null
                    where d.tenant_id = ? and d.is_active
                      and coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date) >= current_date
                    order by coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date)
                    limit 20
                    """, turn.tenantId()));
            case "create_deadline" -> createDeadline(turn, parameters);
            case "move_document" -> moveDocument(turn, parameters);
            case "delete_document" -> deleteDocument(turn, parameters);
            case "update_case" -> updateCase(turn, parameters);
            // The assistant puts a letter in front of a person; it does not send it. This is what
            // "send_email" means here: a draft, released only by a human being in the mail centre.
            case "send_email" -> draftEmail(turn, parameters);
            default -> Map.of("note", "Für dieses Werkzeug gibt es keine Leseabfrage");
        };
    }

    /** The areas of the application, kept as the mirror of apps/web/src/navigation/screens.ts. */
    private record Screen(String id, String href, String title) {
    }

    private static final List<Screen> SCREENS = List.of(
            new Screen("dashboard", "/dashboard", "Dashboard"),
            new Screen("inbox", "/inbox", "Eingang"),
            new Screen("review", "/review", "Prüfzentrum"),
            new Screen("documents", "/documents", "Dokumentenregister"),
            new Screen("cases", "/cases", "Vorgänge und Akten"),
            new Screen("tasks", "/tasks", "Aufgaben und Abläufe"),
            new Screen("radar", "/radar", "Radar"),
            new Screen("approvals", "/approvals", "Freigaben"),
            new Screen("future", "/future", "Zukunft"),
            new Screen("calendar", "/calendar", "Kalender und Fristen"),
            new Screen("contracts", "/contracts", "Verträge"),
            new Screen("invoices", "/invoices", "Rechnungen und Ausgaben"),
            new Screen("payments", "/payments", "Zahlungen und Abgleich"),
            new Screen("contacts", "/contacts", "Kontakte und Organisationen"),
            new Screen("mail", "/mail", "Postzentrum"),
            new Screen("templates", "/templates", "Vorlagen-Editor"),
            new Screen("generated-documents", "/generated-documents", "Erzeugte Schreiben"),
            new Screen("integrations", "/integrations", "Integrationen"),
            new Screen("parties", "/parties", "Personen und Profil"),
            new Screen("family", "/family", "Familien-Cockpit"),
            new Screen("company", "/company", "Unternehmens-Arbeitsbereich"),
            new Screen("employees", "/employees", "Mitarbeitende und Rollen"),
            new Screen("reports", "/reports", "Berichte und Analyse"),
            new Screen("archive", "/archive", "Archiv und Aufbewahrung"),
            new Screen("security", "/security", "Sicherheitseinstellungen"),
            new Screen("accessibility", "/accessibility", "Barrierefreiheit"),
            new Screen("rules", "/rules", "Regeln und Empfindlichkeit"),
            new Screen("date-markers", "/settings/date-markers", "Datumsbegriffe"),
            new Screen("notifications", "/notifications", "Benachrichtigungen"),
            new Screen("audit", "/audit", "Audit-Log"),
            new Screen("billing", "/billing", "Abrechnung"),
            new Screen("profile", "/profile", "Profil"),
            new Screen("account-settings", "/settings", "Kontoeinstellungen"));

    private Map<String, Object> listCalendarEvents(Turn turn, Map<String, Object> parameters) {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Berlin"));
        LocalDate from = parseDate(parameters == null ? null : parameters.get("from"), today);
        LocalDate to = parseDate(parameters == null ? null : parameters.get("to"), today.plusDays(30));
        if (to.isBefore(from)) {
            return Map.of("error", "Das Ende des Zeitraums liegt vor dem Anfang");
        }
        if (from.plusDays(CalendarEventService.MAX_RANGE_DAYS).isBefore(to)) {
            to = from.plusDays(CalendarEventService.MAX_RANGE_DAYS);
        }
        return Map.of("events", calendar.events(turn.tenantId(), turn.userId(), false, from, to));
    }

    private Map<String, Object> createCalendarEvent(Turn turn, Map<String, Object> parameters) {
        String title = stringOf(parameters, "title");
        LocalDate date = parseDate(parameters == null ? null : parameters.get("date"), null);
        LocalDate endDate = parseDate(parameters == null ? null : parameters.get("endDate"), null);
        CalendarEventService.Kind kind = "REMINDER".equalsIgnoreCase(stringOf(parameters, "type"))
                ? CalendarEventService.Kind.REMINDER
                : CalendarEventService.Kind.APPOINTMENT;
        UUID caseId = requireCaseIfNamed(turn, parameters);
        Map<String, Object> created = calendar.create(turn.tenantId(),
                new CalendarEventService.Draft(title, date, endDate, kind, caseId), turn.userId());
        return Map.of("event", created, "created", true);
    }

    private Map<String, Object> createDeadline(Turn turn, Map<String, Object> parameters) {
        UUID taskId = requireTask(turn, parameters);
        UUID caseId = requireCaseIfNamed(turn, parameters);
        LocalDate dueDate = parseDate(parameters == null ? null : parameters.get("dueDate"), null);
        if (dueDate == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Frist braucht ein Datum");
        }
        String note = stringOf(parameters, "note");
        TaskDeadline created = deadlines.recordManual(turn.tenantId(), caseId, taskId, dueDate, note,
                turn.userId());
        return Map.of("deadlineId", created.getId().toString(), "taskId", taskId.toString(),
                "dueDate", dueDate.toString(), "created", true);
    }

    private Map<String, Object> moveDocument(Turn turn, Map<String, Object> parameters) {
        UUID documentId = requireDocument(turn, parameters);
        UUID caseId = requireCaseIfNamed(turn, parameters);
        // The document leaves every case it is in, and enters the named one; a document of another tenant
        // was already refused above, and the case is checked the same way.
        jdbc.update("delete from case_documents where document_id = ? and tenant_id = ?",
                documentId, turn.tenantId());
        if (caseId != null) {
            jdbc.update("""
                    insert into case_documents (id, case_id, document_id, tenant_id, link_origin,
                                                linked_by, confirmed_at)
                    values (gen_random_uuid(), ?, ?, ?, 'MANUAL', ?, now())
                    """, caseId, documentId, turn.tenantId(), turn.userId());
        }
        return Map.of("documentId", documentId.toString(),
                "caseId", caseId == null ? null : caseId.toString(), "moved", true);
    }

    private Map<String, Object> deleteDocument(Turn turn, Map<String, Object> parameters) {
        UUID documentId = requireDocument(turn, parameters);
        // The same soft delete the document list uses: the row stays for the audit trail, everything else
        // forgets the document.
        documents.delete(documentId, "assistant");
        return Map.of("documentId", documentId.toString(), "deleted", true);
    }

    private Map<String, Object> updateCase(Turn turn, Map<String, Object> parameters) {
        UUID caseId = uuidOrNull(parameters == null ? null : parameters.get("caseId"));
        if (caseId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Vorgang muss angegeben werden");
        }
        if (!exists("cases", caseId, turn.tenantId())) {
            return Map.of("error", "Der Vorgang wurde nicht gefunden");
        }
        String title = stringOf(parameters, "title");
        String description = stringOf(parameters, "description");
        String priority = stringOf(parameters, "priority");
        String dueAtRaw = stringOf(parameters, "dueAt");
        if ((title == null || title.isBlank()) && (description == null || description.isBlank())
                && (priority == null || priority.isBlank()) && (dueAtRaw == null || dueAtRaw.isBlank())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Es wurde kein Feld angegeben, das geändert werden soll");
        }
        List<String> fields = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            fields.add("title = ?");
            values.add(title.strip());
        }
        if (description != null && !description.isBlank()) {
            fields.add("description = ?");
            values.add(description.strip());
        }
        if (priority != null && !priority.isBlank()) {
            if (!List.of("CRITICAL", "HIGH", "NORMAL", "LOW", "INFORMATIONAL")
                    .contains(priority.strip().toUpperCase(java.util.Locale.ROOT))) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Die Priorität '" + priority + "' gibt es nicht");
            }
            fields.add("priority = ?");
            values.add(priority.strip().toUpperCase(java.util.Locale.ROOT));
        }
        if (dueAtRaw != null && !dueAtRaw.isBlank()) {
            LocalDate due = parseDate(dueAtRaw, null);
            if (due == null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Das Fälligkeitsdatum ist nicht lesbar");
            }
            fields.add("due_at = ?");
            values.add(java.sql.Timestamp.from(due.atStartOfDay(ZoneId.of("Europe/Berlin")).toInstant()));
        }
        values.add(caseId);
        values.add(turn.tenantId());
        jdbc.update("update cases set " + String.join(", ", fields)
                + " where id = ? and tenant_id = ? and deleted_at is null", values.toArray());
        return Map.of("caseId", caseId.toString(), "changed", fields.stream()
                .map(field -> field.substring(0, field.indexOf(" ="))).toList());
    }

    private Map<String, Object> draftEmail(Turn turn, Map<String, Object> parameters) {
        List<String> to = listOf(parameters, "to");
        if (to.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Nachricht braucht einen Empfänger");
        }
        String subject = stringOf(parameters, "subject");
        if (subject == null || subject.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Nachricht braucht einen Betreff");
        }
        String body = stringOf(parameters, "body");
        UUID caseId = requireCaseIfNamed(turn, parameters);
        UUID messageId = outbound.draft(new OutboundMessageService.DraftRequest(turn.tenantId(), caseId,
                "EMAIL", subject.strip(), body, null, null, to, null, null,
                "ai-" + UUID.randomUUID(), turn.userId()));
        return Map.of("messageId", messageId.toString(), "status", "DRAFT", "sent", false);
    }

    /** The document id the tool acts on, refusing anything that is not a document of this tenant. */
    private UUID requireDocument(Turn turn, Map<String, Object> parameters) {
        UUID documentId = uuidOrNull(parameters == null ? null : parameters.get("documentId"));
        if (documentId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurde kein Dokument angegeben");
        }
        if (!exists("documents", documentId, turn.tenantId())) {
            // Not existing and belonging to somebody else is the same answer, exactly as for reads.
            throw new ApiException(ErrorCode.NOT_FOUND, "Das Dokument wurde nicht gefunden");
        }
        return documentId;
    }

    /** The task id the tool acts on; a task of another tenant simply does not exist from here. */
    private UUID requireTask(Turn turn, Map<String, Object> parameters) {
        UUID taskId = uuidOrNull(parameters == null ? null : parameters.get("taskId"));
        if (taskId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Aufgabe muss angegeben werden");
        }
        if (!exists("tasks", taskId, turn.tenantId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Die Aufgabe wurde nicht gefunden");
        }
        return taskId;
    }

    /** The case a tool may name; {@code null} is allowed, anything else must be this tenant's. */
    private UUID requireCaseIfNamed(Turn turn, Map<String, Object> parameters) {
        UUID caseId = uuidOrNull(parameters == null ? null : parameters.get("caseId"));
        if (caseId == null) {
            return null;
        }
        if (!exists("cases", caseId, turn.tenantId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Der Vorgang wurde nicht gefunden");
        }
        return caseId;
    }

    private boolean exists(String table, UUID id, UUID tenantId) {
        // Only the three tables a tool can name exist here; the table name never comes from the model.
        String column = switch (table) {
            case "documents", "cases", "tasks" -> "id";
            default -> throw new IllegalArgumentException("unbekannte Tabelle");
        };
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where " + column
                        + " = ? and tenant_id = ? and deleted_at is null",
                Integer.class, id, tenantId);
        return count != null && count > 0;
    }

    private static UUID uuidOrNull(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank() || "null".equals(String.valueOf(raw))) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(raw));
        }
        catch (IllegalArgumentException malformed) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Kennung '" + raw + "' ist nicht lesbar");
        }
    }

    private static LocalDate parseDate(Object raw, LocalDate fallback) {
        if (raw == null || String.valueOf(raw).isBlank() || "null".equals(String.valueOf(raw))) {
            return fallback;
        }
        try {
            return LocalDate.parse(String.valueOf(raw).strip());
        }
        catch (DateTimeParseException malformed) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Das Datum '" + raw + "' ist nicht lesbar (JJJJ-MM-TT erwartet)");
        }
    }

    private static String stringOf(Map<String, Object> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        Object raw = parameters.get(key);
        return raw == null || "null".equals(String.valueOf(raw)) ? null : String.valueOf(raw);
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Map<String, Object> parameters, String key) {
        if (parameters == null || parameters.get(key) == null) {
            return List.of();
        }
        Object raw = parameters.get(key);
        if (raw instanceof List<?> list) {
            return list.stream().map(value -> String.valueOf(value).strip())
                    .filter(value -> !value.isBlank() && !"null".equals(value)).toList();
        }
        return java.util.Arrays.stream(String.valueOf(raw).split("[,;]"))
                .map(String::strip).filter(value -> !value.isBlank()).toList();
    }

    private Map<String, Object> explainFinding(Turn turn, Map<String, Object> parameters) {
        Object findingId = parameters == null ? null : parameters.get("findingId");
        if (findingId == null) {
            return Map.of("error", "Es wurde kein Hinweis angegeben");
        }
        UUID id;
        try {
            id = UUID.fromString(String.valueOf(findingId));
        } catch (IllegalArgumentException malformed) {
            return Map.of("error", "Der Hinweis wurde nicht gefunden");
        }
        // Not visible and not existing are the same answer, as for documents.
        return radar.detailIfVisible(turn.tenantId(), turn.userId(), id)
                .<Map<String, Object>>map(finding -> Map.of("finding", finding))
                .orElse(Map.of("error", "Der Hinweis wurde nicht gefunden"));
    }

    private Map<String, Object> describeDocument(Turn turn, Map<String, Object> parameters) {
        Object documentId = parameters == null ? null : parameters.get("documentId");
        if (documentId == null) {
            return Map.of("error", "Es wurde kein Dokument angegeben");
        }
        List<Map<String, Object>> found = jdbc.queryForList("""
                select id, original_filename, mime_type, processing_status, document_date
                from documents where id = ?::uuid and tenant_id = ? and deleted_at is null
                """, String.valueOf(documentId), turn.tenantId());
        if (found.isEmpty()) {
            // The same answer for "does not exist" and "belongs to somebody else": an assistant that
            // distinguishes them tells its user what another tenant has.
            return Map.of("error", "Das Dokument wurde nicht gefunden");
        }
        return Map.of("document", found.getFirst());
    }

    private static String jsonOf(String toolName, String question, Map<String, Object> parameters) {
        StringBuilder json = new StringBuilder("{\"tool\":\"")
                .append(toolName == null ? "" : toolName.replace("\"", ""))
                .append("\",\"question\":\"")
                .append(question == null ? "" : question.replace("\\", "\\\\").replace("\"", "'")
                        .replace("\n", " "))
                .append("\",\"parameters\":{");
        if (parameters != null) {
            boolean first = true;
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                json.append('"').append(entry.getKey().replace("\"", "")).append("\":\"")
                        .append(entry.getValue() == null ? ""
                                : String.valueOf(entry.getValue()).replace("\\", "\\\\")
                                        .replace("\"", "'"))
                        .append('"');
                first = false;
            }
        }
        return json.append("}}").toString();
    }
}
