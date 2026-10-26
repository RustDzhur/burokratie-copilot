package de.buerokratiecopilot.agents.crm;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.cases.CaseEventRepository;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;

/**
 * {@code crm-activity} — Aktivitäten-Protokoll.
 *
 * <p>Records an activity (call, e-mail, letter, meeting, note) where it belongs: as a timeline
 * entry on the case and as an entry in the contact's activity log. Outbound correspondence maps
 * to {@code CORRESPONDENCE_SENT}, inbound to {@code CORRESPONDENCE_RECEIVED}, everything else to
 * {@code NOTE_ADDED}. Idempotent on the external key (e.g. a mail message id): the same event
 * is protocolled exactly once.</p>
 */
@Component
public class CrmActivityAgent implements Agent {

    /** Aktivitätstyp → Ereignistyp auf der Vorgangs-Timeline. */
    private static final Map<String, CaseEvent.EventType> EVENT_TYPES = Map.of(
            "CALL", CaseEvent.EventType.NOTE_ADDED,
            "MEETING", CaseEvent.EventType.NOTE_ADDED,
            "NOTE", CaseEvent.EventType.NOTE_ADDED,
            "EMAIL_OUT", CaseEvent.EventType.CORRESPONDENCE_SENT,
            "LETTER_OUT", CaseEvent.EventType.CORRESPONDENCE_SENT,
            "EMAIL_IN", CaseEvent.EventType.CORRESPONDENCE_RECEIVED,
            "LETTER_IN", CaseEvent.EventType.CORRESPONDENCE_RECEIVED);

    private final CaseRepository cases;
    private final CaseEventRepository events;
    private final CrmStore store;

    public CrmActivityAgent(CaseRepository cases, CaseEventRepository events, CrmStore store) {
        this.cases = cases;
        this.events = events;
        this.store = store;
    }

    @Override
    public String id() {
        return "crm-activity";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID tenantId = context.tenantId();
        UUID actor = context.actorUserId();

        String subject = clean(context.input("subject"));
        if (subject == null) {
            return AgentResult.failed("Aktivitäten-Protokoll: ein Betreff ('subject') ist erforderlich.");
        }
        String typeRaw = clean(context.input("type"));
        if (typeRaw == null) {
            return AgentResult.failed("Aktivitäten-Protokoll: ein Typ ('type') ist erforderlich.");
        }
        String type = typeRaw.toUpperCase(Locale.ROOT);
        if (!EVENT_TYPES.containsKey(type)) {
            return AgentResult.failed("Aktivitäten-Protokoll: unbekannter Typ '" + typeRaw
                    + "' — erwartet: " + String.join(", ", EVENT_TYPES.keySet()) + ".");
        }
        String note = clean(context.input("note"));
        String externalKey = clean(context.input("externalKey"));
        String occurredAt = clean(context.input("occurredAt"));
        if (occurredAt != null) {
            try {
                Instant.parse(occurredAt);
            }
            catch (Exception e) {
                return AgentResult.failed("Aktivitäten-Protokoll: ungültiger Zeitpunkt 'occurredAt'"
                        + " — ISO-8601 erwartet.");
            }
        }

        UUID caseId;
        UUID contactId;
        try {
            caseId = parseUuid(context.input("caseId"));
            contactId = parseUuid(context.input("contactId"));
        }
        catch (IllegalArgumentException e) {
            return AgentResult.failed("Aktivitäten-Protokoll: ungültige UUID: " + e.getMessage());
        }
        if (caseId == null && contactId == null) {
            return AgentResult.failed("Aktivitäten-Protokoll: 'caseId' oder 'contactId' ist erforderlich.");
        }

        // Idempotenz über den externen Schlüssel (z.B. Mail-Message-Id).
        if (externalKey != null) {
            Optional<UUID> existingEvent = store.findCaseEventByExternalKey(tenantId, externalKey);
            if (existingEvent.isPresent()) {
                context.addProduced("case-event", existingEvent.get().toString(),
                        "Bereits protokollierte Aktivität");
                return AgentResult.done("Aktivität ist bereits protokolliert (externer Schlüssel).",
                        context.produced(), Map.of("existing", "true", "matchedBy", "externalKey"));
            }
            if (contactId != null && store.contactActivityExists(tenantId, contactId, externalKey)) {
                context.addProduced("contact-activity", contactId.toString(),
                        "Bereits protokollierte Aktivität");
                return AgentResult.done("Aktivität ist am Kontakt bereits protokolliert "
                        + "(externer Schlüssel).", context.produced(),
                        Map.of("existing", "true", "matchedBy", "externalKey"));
            }
        }

        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("type", type);
        activity.put("subject", subject);
        if (note != null) {
            activity.put("note", note);
        }
        if (occurredAt != null) {
            activity.put("occurredAt", occurredAt);
        }
        if (externalKey != null) {
            activity.put("externalKey", externalKey);
        }
        if (caseId != null) {
            activity.put("caseId", caseId.toString());
        }
        activity.put("recordedAt", context.now().toString());

        boolean recordedCase = false;
        String eventId = null;
        if (caseId != null) {
            Optional<Case> caseOpt = cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId);
            if (caseOpt.isEmpty()) {
                return AgentResult.failed("Aktivitäten-Protokoll: Vorgang " + caseId
                        + " nicht gefunden.");
            }
            CaseEvent event = new CaseEvent(UUID.randomUUID(), tenantId, caseId,
                    EVENT_TYPES.get(type), actor, subject);
            if (note != null) {
                event.setDescription(note);
            }
            if (contactId != null) {
                event.setContactId(contactId);
            }
            event.setMetadata(Map.of("activity", activity, "agent", "crm-activity"));
            CaseEvent saved = events.save(event);
            eventId = saved.getId().toString();
            recordedCase = true;
            context.addProduced("case-event", eventId, subject);
        }

        boolean contactMissing = false;
        if (contactId != null) {
            if (store.findContactRow(tenantId, contactId).isEmpty()) {
                contactMissing = true;
            }
            else {
                int added = store.appendContactActivity(tenantId, contactId, activity);
                if (added > 0) {
                    context.addProduced("contact-activity", contactId.toString(), subject);
                }
            }
        }

        if (!recordedCase && context.produced().isEmpty()) {
            return AgentResult.skipped("Aktivitäten-Protokoll: nichts protokolliert — Kontakt "
                    + contactId + " nicht gefunden.");
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("type", type);
        if (eventId != null) {
            meta.put("caseEventId", eventId);
        }
        if (caseId != null) {
            meta.put("caseId", caseId.toString());
        }
        if (contactId != null) {
            meta.put("contactId", contactId.toString());
        }
        if (contactMissing) {
            meta.put("contactMissing", "true");
        }
        return AgentResult.done("Aktivität protokolliert: " + subject, context.produced(), meta);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return UUID.fromString(raw.strip());
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
