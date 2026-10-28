package de.buerokratiecopilot.agents.compliance;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.audit.query.AuditQueryService;

/**
 * {@code audit-trail} — Prüfprotokoll.
 *
 * <p>Assembles the complete audit trail of one object from the audit module and prepares it as a
 * structured export: the events themselves (who did what when), grouped statistics and the list of
 * actors. The export is written into the run journal ({@code meta.exportJson}) and reported as a
 * produced item; the agent never writes to the audit log itself, which stays append-only.</p>
 */
@Component
public class AuditTrailAgent implements Agent {

    private static final int MAX_LIMIT = 200;

    private final AuditQueryService auditQueries;
    private final ObjectMapper json;

    public AuditTrailAgent(AuditQueryService auditQueries, ObjectMapper json) {
        this.auditQueries = auditQueries;
        this.json = json;
    }

    @Override
    public String id() {
        return "audit-trail";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String entityType = context.input("entityType");
        if (entityType == null || entityType.isBlank()) {
            return AgentResult.skipped("entityType fehlt — das Prüfprotokoll braucht einen Objekttyp");
        }
        UUID entityId = parseUuid(context.input("entityId"));
        if (entityId == null) {
            return AgentResult.skipped("entityId fehlt oder ist keine gültige UUID");
        }

        Instant from = parseInstant(context.input("from"));
        if (context.input("from") != null && from == null) {
            return AgentResult.skipped("from ist kein gültiger ISO-8601 Zeitpunkt");
        }
        Instant to = parseInstant(context.input("to"));
        if (context.input("to") != null && to == null) {
            return AgentResult.skipped("to ist kein gültiger ISO-8601 Zeitpunkt");
        }
        int limit = parseInt(context.input("limit"), MAX_LIMIT);

        List<Map<String, Object>> events = auditQueries.events(context.tenantId(), from, to, null, null,
                entityType.strip().toLowerCase(Locale.ROOT), entityId, limit);

        Map<String, Object> export = buildExport(entityType.strip().toLowerCase(Locale.ROOT), entityId, events);
        context.addProduced("auditTrail", entityId.toString(),
                "Prüfprotokoll für " + entityType.strip() + ": " + events.size() + " Ereignisse");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("entityType", entityType.strip().toLowerCase(Locale.ROOT));
        meta.put("entityId", entityId.toString());
        meta.put("totalEvents", String.valueOf(events.size()));
        meta.put("exportJson", toJson(export));
        return AgentResult.done("Prüfprotokoll erstellt: " + events.size() + " Ereignisse zu " + entityType.strip(),
                context.produced(), meta);
    }

    /** The export: events plus the statistics that answer “who did what when”. */
    Map<String, Object> buildExport(String entityType, UUID entityId, List<Map<String, Object>> events) {
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("generatedAt", Instant.now().toString());
        export.put("entity", Map.of("type", entityType, "id", entityId.toString()));

        // The audit module returns events newest first, so the newest is the first row and the
        // oldest the last row of the trail.
        Map<String, Integer> byResult = new LinkedHashMap<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Set<String> actors = new LinkedHashSet<>();
        String newest = null;
        String oldest = null;
        for (Map<String, Object> event : events) {
            byResult.merge(String.valueOf(event.get("result")), 1, Integer::sum);
            byCategory.merge(String.valueOf(event.get("category")), 1, Integer::sum);
            Object actor = event.get("actorUserId");
            if (actor != null) {
                actors.add(String.valueOf(actor));
            }
            else {
                actors.add(String.valueOf(event.get("actorType")));
            }
            if (newest == null) {
                newest = String.valueOf(event.get("createdAt"));
            }
            oldest = String.valueOf(event.get("createdAt"));
        }
        export.put("totalEvents", events.size());
        export.put("byResult", byResult);
        export.put("byCategory", byCategory);
        export.put("actors", actors);
        export.put("newestEventAt", newest);
        export.put("oldestEventAt", oldest);
        export.put("events", events);
        return export;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return "{}";
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.min(Math.max(Integer.parseInt(raw.strip()), 1), MAX_LIMIT);
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
