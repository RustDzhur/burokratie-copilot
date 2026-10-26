package de.buerokratiecopilot.agents.communication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.cases.CaseEventRepository;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code phone-transcript} — Anruf-Transkription.
 *
 * <p>Turns dictated or typed conversation text into a case note: a {@code NOTE_ADDED} entry on the
 * case timeline, linked to the contact that was spoken with, with the metadata (direction, caller,
 * source key) that makes the note traceable. Follow-ups the caller names — one per line,
 * optionally {@code |YYYY-MM-DD} — become tasks of the case.
 *
 * <p>No external speech recognition: the text arrives as input. Idempotent: a re-run with the same
 * text and case finds the existing note (via its source key) and reports it instead of writing a
 * second one.</p>
 */
@Component
public class PhoneTranscriptAgent implements Agent {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final JdbcTemplate jdbc;
    private final CaseEventRepository caseEventRepository;
    private final TaskService taskService;

    public PhoneTranscriptAgent(JdbcTemplate jdbc, CaseEventRepository caseEventRepository,
                                TaskService taskService) {
        this.jdbc = jdbc;
        this.caseEventRepository = caseEventRepository;
        this.taskService = taskService;
    }

    @Override
    public String id() {
        return "phone-transcript";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String text = context.input("text");
        if (text == null || text.isBlank()) {
            return AgentResult.skipped("Kein Gesprächstext vorhanden: 'text' fehlt.");
        }

        UUID caseId = resolveCase(context);
        if (caseId == null) {
            return AgentResult.skipped(
                    "Kein Vorgang gefunden: weder 'caseId' noch ein offener Vorgang zum Kontakt.");
        }

        UUID contactId = parseUuid(context.input("contactId"));
        String direction = "OUT".equalsIgnoreCase(context.input("direction")) ? "OUT" : "IN";
        String caller = context.input("caller");
        String sourceKey = sourceKey(context, caseId, text);

        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id from case_events
                where tenant_id = ? and case_id = ? and event_type = 'NOTE_ADDED'
                  and metadata ->> 'sourceKey' = ?
                limit 1
                """, context.tenantId(), caseId, sourceKey);
        if (!existing.isEmpty()) {
            Object id = existing.getFirst().get("id");
            String ref = String.valueOf(id);
            context.addProduced("note", ref, "Gesprächsnotiz bereits erfasst");
            return AgentResult.done("Die Gesprächsnotiz zu diesem Text wurde bereits erfasst.",
                    context.produced(), Map.of("noteId", ref, "duplicate", "true"));
        }

        String title = caller == null || caller.isBlank()
                ? "Gesprächsnotiz"
                : (direction.equals("OUT") ? "Telefonat mit " + caller.strip()
                        : "Anruf von " + caller.strip());
        CaseEvent note = new CaseEvent(UUID.randomUUID(), context.tenantId(), caseId,
                CaseEvent.EventType.NOTE_ADDED, context.actorUserId(), title);
        note.setDescription(text.strip());
        note.setContactId(contactId);
        note.setMetadata(Map.of("sourceKey", sourceKey, "direction", direction,
                "caller", caller == null ? "" : caller.strip()));
        CaseEvent saved = caseEventRepository.save(note);

        context.addProduced("note", saved.getId().toString(), title);
        List<AgentResult.Produced> produced = new java.util.ArrayList<>(context.produced());

        int tasksCreated = 0;
        for (String line : lines(context.input("followUps"))) {
            Task task = createFollowUp(context, caseId, line);
            if (task == null) {
                continue;
            }
            produced.add(new AgentResult.Produced("task", task.getId().toString(),
                    "Folgeaufgabe: " + task.getTitle()));
            tasksCreated++;
        }

        return AgentResult.done(
                "Gesprächsnotiz angelegt und am Vorgang vermerkt"
                        + (tasksCreated > 0 ? ", " + tasksCreated + " Folgeaufgabe(n) angelegt" : "")
                        + ".",
                produced,
                Map.of("noteId", saved.getId().toString(), "caseId", caseId.toString(),
                        "tasksCreated", String.valueOf(tasksCreated)));
    }

    /** The case the note belongs to: the caller's id, or the newest open case of the contact. */
    private UUID resolveCase(AgentContext context) {
        UUID caseId = parseUuid(context.input("caseId"));
        if (caseId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select id from cases where id = ? and tenant_id = ? and deleted_at is null
                    """, caseId, context.tenantId());
            return rows.isEmpty() ? null : caseId;
        }
        UUID contactId = parseUuid(context.input("contactId"));
        if (contactId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id from cases
                where tenant_id = ? and counterparty_contact_id = ? and deleted_at is null
                  and status not in ('COMPLETED', 'CLOSED')
                order by created_at desc
                limit 1
                """, context.tenantId(), contactId);
        if (rows.isEmpty()) {
            return null;
        }
        Object id = rows.getFirst().get("id");
        return id instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(id));
    }

    /** One follow-up line {@code Text} or {@code Text|YYYY-MM-DD} as a task of the case. */
    private Task createFollowUp(AgentContext context, UUID caseId, String line) {
        String[] parts = line.split("\\|", 2);
        String text = parts[0].strip();
        if (text.isEmpty()) {
            return null;
        }
        String title = text.length() > 300 ? text.substring(0, 300) : text;
        LocalDate due = null;
        if (parts.length > 1 && !parts[1].isBlank()) {
            try {
                due = LocalDate.parse(parts[1].strip());
            }
            catch (java.time.format.DateTimeParseException ignored) {
                // A line without a readable date still becomes a task, just without a due date.
            }
        }
        return taskService.create(new TaskService.CreateRequest(
                context.tenantId(), caseId, null, title,
                "Aus Gesprächsnotiz übernommen", Task.Priority.NORMAL, "MANUAL", null,
                due == null ? null : due.atTime(12, 0).atZone(BERLIN).toInstant(),
                null, null, false, null, null, List.of(), Task.Source.SYSTEM, null),
                context.actorUserId());
    }

    /** The key under which the note is recognised on a re-run. */
    private static String sourceKey(AgentContext context, UUID caseId, String text) {
        String explicit = context.input("idempotencyKey");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        String sample = text.length() > 500 ? text.substring(0, 500) : text;
        return "phone-transcript:" + caseId + ":" + sha256(sample);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static List<String> lines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return value.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
