package de.buerokratiecopilot.agents.communication;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * {@code meeting-notes} — Besprechungsnotizen.
 *
 * <p>Forms a meeting note: a {@code NOTE_ADDED} timeline entry on the case carrying the minutes
 * and the decisions, one entry per participant contact (which is what attaches the note to the
 * contacts), and one task per action item the note names. The case is the caller's {@code caseId}
 * or, when only participants are named, the newest open case of the first participant.
 *
 * <p>Idempotent: a re-run with the same title, date and case finds the note via its source key
 * and does not duplicate notes or tasks.</p>
 */
@Component
public class MeetingNotesAgent implements Agent {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final JdbcTemplate jdbc;
    private final CaseEventRepository caseEventRepository;
    private final TaskService taskService;

    public MeetingNotesAgent(JdbcTemplate jdbc, CaseEventRepository caseEventRepository,
                             TaskService taskService) {
        this.jdbc = jdbc;
        this.caseEventRepository = caseEventRepository;
        this.taskService = taskService;
    }

    @Override
    public String id() {
        return "meeting-notes";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String title = context.input("title");
        if (title == null || title.isBlank()) {
            return AgentResult.skipped("Kein Titel vorhanden: 'title' fehlt.");
        }

        List<UUID> participants = parseUuids(context.input("participants"));
        UUID caseId = resolveCase(context, participants);
        if (caseId == null) {
            return AgentResult.skipped(
                    "Kein Vorgang gefunden: weder 'caseId' noch ein offener Vorgang zu den Teilnehmern.");
        }

        String date = meetingDate(context);
        String sourceKey = sourceKey(context, caseId, title, date);

        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id from case_events
                where tenant_id = ? and case_id = ? and event_type = 'NOTE_ADDED'
                  and metadata ->> 'sourceKey' = ?
                limit 1
                """, context.tenantId(), caseId, sourceKey);
        if (!existing.isEmpty()) {
            Object id = existing.getFirst().get("id");
            String ref = String.valueOf(id);
            context.addProduced("note", ref, "Besprechungsnotiz bereits erfasst");
            return AgentResult.done("Die Besprechungsnotiz wurde bereits erfasst.",
                    context.produced(), Map.of("noteId", ref, "duplicate", "true"));
        }

        String minutes = context.input("minutes");
        String decisions = context.input("decisions");
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("sourceKey", sourceKey);
        metadata.put("meetingTitle", title.strip());
        metadata.put("meetingDate", date);
        metadata.put("decisions", decisions == null ? "" : decisions.strip());
        metadata.put("participants", participants.stream().map(UUID::toString).toList());

        List<AgentResult.Produced> produced = new ArrayList<>();
        List<UUID> noteTargets = participants.isEmpty() ? List.of((UUID) null) : participants;
        for (UUID contactId : noteTargets) {
            CaseEvent note = new CaseEvent(UUID.randomUUID(), context.tenantId(), caseId,
                    CaseEvent.EventType.NOTE_ADDED, context.actorUserId(),
                    "Besprechungsnotiz: " + title.strip());
            note.setDescription(minutes == null || minutes.isBlank() ? "" : minutes.strip());
            note.setContactId(contactId);
            note.setMetadata(metadata);
            CaseEvent saved = caseEventRepository.save(note);
            produced.add(new AgentResult.Produced("note", saved.getId().toString(),
                    contactId == null ? "Besprechungsnotiz am Vorgang"
                            : "Besprechungsnotiz am Kontakt " + contactId));
        }

        int tasksCreated = 0;
        for (String line : lines(context.input("actionItems"))) {
            Task task = createActionItem(context, caseId, title, line);
            if (task == null) {
                continue;
            }
            produced.add(new AgentResult.Produced("task", task.getId().toString(),
                    "Aufgabe: " + task.getTitle()));
            tasksCreated++;
        }

        return AgentResult.done(
                "Besprechungsnotiz angelegt (%d Notiz(en) am Vorgang/Kontakt, %d Aufgabe(n))."
                        .formatted(noteTargets.size(), tasksCreated),
                produced,
                Map.of("caseId", caseId.toString(), "notesCreated", String.valueOf(noteTargets.size()),
                        "tasksCreated", String.valueOf(tasksCreated)));
    }

    /** The caller's case, or the newest open case of the first participant contact. */
    private UUID resolveCase(AgentContext context, List<UUID> participants) {
        UUID caseId = parseUuid(context.input("caseId"));
        if (caseId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select id from cases where id = ? and tenant_id = ? and deleted_at is null
                    """, caseId, context.tenantId());
            return rows.isEmpty() ? null : caseId;
        }
        if (participants.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id from cases
                where tenant_id = ? and counterparty_contact_id = ? and deleted_at is null
                  and status not in ('COMPLETED', 'CLOSED')
                order by created_at desc
                limit 1
                """, context.tenantId(), participants.getFirst());
        if (rows.isEmpty()) {
            return null;
        }
        Object id = rows.getFirst().get("id");
        return id instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(id));
    }

    /** One action line {@code Text} or {@code Text|YYYY-MM-DD} as a task of the case. */
    private Task createActionItem(AgentContext context, UUID caseId, String title, String line) {
        String[] parts = line.split("\\|", 2);
        String text = parts[0].strip();
        if (text.isEmpty()) {
            return null;
        }
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
                context.tenantId(), caseId, null, text.length() > 300 ? text.substring(0, 300) : text,
                "Aus Besprechung „" + title.strip() + "“ übernommen",
                Task.Priority.NORMAL, "MANUAL", null,
                due == null ? null : due.atTime(12, 0).atZone(BERLIN).toInstant(),
                null, null, false, null, null, List.of(), Task.Source.SYSTEM, null),
                context.actorUserId());
    }

    private static String meetingDate(AgentContext context) {
        String input = context.input("date");
        if (input != null && !input.isBlank()) {
            try {
                return LocalDate.parse(input.strip()).toString();
            }
            catch (java.time.format.DateTimeParseException ignored) {
                // Unreadable date: fall back to today.
            }
        }
        Instant now = context.now() == null ? Instant.now() : context.now();
        return LocalDate.ofInstant(now, BERLIN).toString();
    }

    private static String sourceKey(AgentContext context, UUID caseId, String title, String date) {
        String explicit = context.input("idempotencyKey");
        if (explicit != null && !explicit.isBlank()) {
            return explicit.strip();
        }
        return "meeting-notes:" + caseId + ":" + sha256(title.strip() + "|" + date);
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

    private static List<UUID> parseUuids(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String part : commaSeparated.split(",")) {
            UUID id = parseUuid(part);
            if (id != null && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
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
