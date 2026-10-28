package de.buerokratiecopilot.agents.realestate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code realestate-viewing} — Besichtigungstermine.
 *
 * <p>Takes a property reference, the interested parties and the free slots the responsible person
 * offered, and turns them into a proposal: one appointment per slot and contact, a waiting list for
 * the contacts that do not fit, a calendar entry with the time window and a confirmation task. The
 * agent confirms nothing by itself — confirmation stays with the person who owns the viewing.</p>
 *
 * <p>This platform has no property master data, so the property id is the caller's reference. What the
 * agent can verify is the contacts and the slots; both are reported honestly when they do not
 * resolve.</p>
 */
@Component
public class ViewingAgent implements Agent {

    static final String ARTIFACT_KIND = "viewing-schedule";
    static final int DEFAULT_DURATION_MINUTES = 45;
    private static final int MAX_CONTACTS = 100;

    private final JdbcTemplate jdbc;
    private final CalendarEventService calendar;
    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public ViewingAgent(JdbcTemplate jdbc, CalendarEventService calendar, AgentTaskFactory tasks,
                        AgentArtifactStore artifacts) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "realestate-viewing";
    }

    /** One interested party: a stored contact or a name the caller supplied. */
    public record Contact(UUID id, String name, String email) {
    }

    /** The proposal: assigned appointments and the contacts that did not get a slot. */
    public record Schedule(List<Assignment> assignments, List<Contact> waitingList) {
    }

    public record Assignment(Contact contact, LocalDateTime slot) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        String propertyId = AgentInputs.text(context.input("propertyId"));
        String contactsRaw = AgentInputs.text(context.input("interestedContacts"));
        if (propertyId == null) {
            return AgentResult.refused("NO_INPUT", "Für die Besichtigung wird eine propertyId "
                    + "benötigt.");
        }
        if (contactsRaw == null) {
            return AgentResult.refused("NO_INPUT",
                    "Es wurden keine Interessenten angegeben (interestedContacts).");
        }
        String slotsRaw = context.input("availableSlots");
        List<LocalDateTime> slots = slots(slotsRaw);
        if (slots == null) {
            return AgentResult.refused("INVALID_INPUT",
                    "availableSlots ist kein gültiges JSON-Array: " + slotsRaw);
        }
        if (slots.isEmpty()) {
            return AgentResult.refused("NO_SLOTS",
                    "Es wurden keine gültigen Zeitfenster übergeben; ohne Termin kein Vorschlag.");
        }
        int duration = AgentInputs.integer(context.input("viewingDurationMinutes"),
                DEFAULT_DURATION_MINUTES, 15, 240);
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);

        List<Contact> contacts = new ArrayList<>();
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (String token : AgentInputs.list(contactsRaw).stream().limit(MAX_CONTACTS).toList()) {
            UUID id = AgentInputs.uuid(token);
            if (id == null) {
                contacts.add(new Contact(null, token, null));
                gaps.add(gap("CONTACT_WITHOUT_RECORD",
                        "Der Interessent \"" + token + "\" ist kein gespeicherter Kontakt; "
                                + "eine Bestätigung ist nur manuell möglich."));
                continue;
            }
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select id, display_name, email::text as email
                    from contacts
                    where tenant_id = ? and id = ? and deleted_at is null
                    """, context.tenantId(), id);
            if (rows.isEmpty()) {
                gaps.add(gap("CONTACT_NOT_FOUND",
                        "Kontakt " + id + " wurde nicht gefunden und wird nicht eingeplant."));
                continue;
            }
            Map<String, Object> row = rows.getFirst();
            String email = row.get("email") == null ? null : String.valueOf(row.get("email"));
            if (email == null) {
                gaps.add(gap("CONTACT_WITHOUT_EMAIL",
                        "Kontakt " + row.get("display_name") + " hat keine E-Mail; die Bestätigung "
                                + "ist nur manuell möglich."));
            }
            contacts.add(new Contact(id, String.valueOf(row.get("display_name")), email));
        }
        if (contacts.isEmpty()) {
            return AgentResult.refused("NO_CONTACTS",
                    "Keiner der angegebenen Interessenten konnte aufgelöst werden.",
                    Map.of("gaps", String.valueOf(gaps.size())));
        }

        Schedule schedule = schedule(contacts, slots);
        List<Map<String, Object>> assignments = new ArrayList<>();
        int createdTasks = 0;
        int calendarEntries = 0;
        for (Assignment assignment : schedule.assignments()) {
            Contact contact = assignment.contact();
            LocalDate date = assignment.slot().toLocalDate();
            LocalTime start = assignment.slot().toLocalTime();
            LocalTime end = assignment.slot().plusMinutes(duration).toLocalTime();
            String title = AgentInputs.truncate("Besichtigung " + propertyId + ": " + contact.name(),
                    300);
            String window = start + "–" + end + " (" + duration + " Minuten)";
            String note = "Vorschlag für ein Besichtigungsfenster: " + window + ". Bestätigung "
                    + "durch die zuständige Person; der Agent bestätigt keine Termine.";

            UUID calendarEventId = null;
            Integer existing = jdbc.queryForObject("""
                    select count(*) from calendar_events
                    where tenant_id = ? and starts_on = ? and title = ? and deleted_at is null
                    """, Integer.class, context.tenantId(), java.sql.Date.valueOf(date), title);
            if (existing == null || existing == 0) {
                Map<String, Object> created = calendar.create(context.tenantId(),
                        new CalendarEventService.Draft(title, date, date,
                                CalendarEventService.Kind.APPOINTMENT, null),
                        context.actorUserId(), note, null);
                calendarEventId = AgentInputs.uuid(String.valueOf(created.get("id")));
                if (calendarEventId != null) {
                    calendarEntries++;
                    context.addProduced("calendar-event", calendarEventId.toString(),
                            "Besichtigung " + propertyId + " am " + date + " " + window);
                }
            }

            String taskTitle = "Besichtigung bestätigen: " + propertyId + " – "
                    + AgentInputs.truncate(contact.name(), 200);
            LocalDate dueBase = date.isBefore(today) ? today : date;
            AgentTaskFactory.Request request = new AgentTaskFactory.Request(taskTitle,
                    "Vorgeschlagenes Fenster: " + date + " " + window + ". Interessent: "
                            + contact.name() + (contact.email() == null ? " (ohne E-Mail)"
                                    : " <" + contact.email() + ">")
                            + ". Bestätigen oder absagen und das Ergebnis im Vorgang festhalten.",
                    "Der Termin ist mit der interessierten Person bestätigt oder abgesagt.",
                    AgentInputs.dueAt(dueBase, -1), Task.Priority.NORMAL,
                    List.of("besichtigung", "bestaetigung"), null, context.actorUserId());
            var optionalTask = tasks.createIfAbsent(context, request);
            if (optionalTask.isPresent()) {
                AgentTaskFactory.produce(context, optionalTask.get());
                createdTasks++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("contactId", contact.id() == null ? null : contact.id().toString());
            row.put("name", contact.name());
            row.put("email", contact.email());
            row.put("slot", assignment.slot().toString());
            row.put("endsAt", assignment.slot().plusMinutes(duration).toString());
            row.put("calendarEventId", calendarEventId == null ? null
                    : calendarEventId.toString());
            assignments.add(row);
        }

        List<Map<String, Object>> waiting = new ArrayList<>();
        for (Contact contact : schedule.waitingList()) {
            waiting.add(Map.of("contactId", contact.id() == null ? "" : contact.id().toString(),
                    "name", contact.name(), "email", contact.email() == null ? "" : contact.email()));
            String taskTitle = "Warteliste informieren: " + propertyId + " – "
                    + AgentInputs.truncate(contact.name(), 200);
            AgentTaskFactory.Request request = new AgentTaskFactory.Request(taskTitle,
                    "Für " + contact.name() + " war kein Zeitfenster frei. Alternativtermin "
                            + "anbieten oder absagen.",
                    "Der Interessent ist über den Stand informiert und ein Alternativtermin ist "
                            + "dokumentiert.",
                    AgentInputs.dueAt(today, 2), Task.Priority.LOW,
                    List.of("besichtigung", "warteliste"), null, context.actorUserId());
            var optionalTask = tasks.createIfAbsent(context, request);
            if (optionalTask.isPresent()) {
                AgentTaskFactory.produce(context, optionalTask.get());
                createdTasks++;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("propertyId", propertyId);
        payload.put("durationMinutes", duration);
        payload.put("slots", slots.stream().map(LocalDateTime::toString).toList());
        payload.put("assignments", assignments);
        payload.put("waitingList", waiting);
        payload.put("gaps", gaps);
        payload.put("note", "Vorschlag; Bestätigung durch die zuständige Person. Keine automatische "
                + "Zusage.");

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND, AgentInputs.slug(propertyId, 60),
                AgentInputs.sha256(slots.toString()).substring(0, 16),
                Integer.toString(duration));
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("viewing-schedule", artifact.id().toString(),
                "Besichtigungsplan " + propertyId + ": " + assignments.size() + " Termin(e), "
                        + waiting.size() + " auf der Warteliste");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("propertyId", propertyId);
        meta.put("slots", String.valueOf(slots.size()));
        meta.put("assignments", String.valueOf(assignments.size()));
        meta.put("waitingList", String.valueOf(waiting.size()));
        meta.put("calendarEntries", String.valueOf(calendarEntries));
        meta.put("tasksCreated", String.valueOf(createdTasks));
        meta.put("gaps", String.valueOf(gaps.size()));
        meta.put("durationMinutes", String.valueOf(duration));
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Besichtigungsplan " + propertyId + ": " + assignments.size()
                + " Termin(e) vorgeschlagen, " + waiting.size() + " auf der Warteliste, "
                + createdTasks + " Aufgabe(n). Bestätigung steht aus.", context.produced(), meta);
    }

    /**
     * Parses the slots array. Accepts strings (ISO date-time) and objects with {@code start},
     * {@code from}, {@code slot} or {@code begin}. Returns {@code null} for malformed JSON.
     * Visible for tests.
     */
    public static List<LocalDateTime> slots(String raw) {
        if (AgentInputs.text(raw) == null) {
            return List.of();
        }
        JsonNode node = AgentInputs.json(raw);
        if (node == null || !node.isArray()) {
            return null;
        }
        Set<LocalDateTime> parsed = new LinkedHashSet<>();
        for (JsonNode element : node) {
            LocalDateTime slot = null;
            if (element.isTextual()) {
                slot = AgentInputs.dateTime(element.asText());
            }
            else if (element.isObject()) {
                for (String field : List.of("start", "from", "slot", "begin")) {
                    slot = AgentInputs.dateTime(AgentInputs.nodeText(element, field));
                    if (slot != null) {
                        break;
                    }
                }
            }
            if (slot != null) {
                parsed.add(slot);
            }
        }
        return parsed.stream().sorted().toList();
    }

    /** First contacts get the first slots; the rest goes to the waiting list. Visible for tests. */
    public static Schedule schedule(List<Contact> contacts, List<LocalDateTime> slots) {
        List<Assignment> assignments = new ArrayList<>();
        List<Contact> waiting = new ArrayList<>();
        for (int index = 0; index < contacts.size(); index++) {
            if (index < slots.size()) {
                assignments.add(new Assignment(contacts.get(index), slots.get(index)));
            }
            else {
                waiting.add(contacts.get(index));
            }
        }
        return new Schedule(List.copyOf(assignments), List.copyOf(waiting));
    }

    private static Map<String, Object> gap(String code, String message) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("code", code);
        gap.put("message", message);
        return gap;
    }
}
