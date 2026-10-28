package de.buerokratiecopilot.agents.processes;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.processes.CalendarReconciler.Discrepancies;
import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.calendar.IcsEventWriter;
import de.buerokratiecopilot.calendar.caldav.CalDavClient;
import de.buerokratiecopilot.calendar.caldav.CalDavConnectionService;
import de.buerokratiecopilot.calendar.caldav.CalDavException;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * {@code calendar-sync} — Kalender-Sync.
 *
 * <p>Compares the internal calendar (appointments, reminders and deadlines) with the external
 * CalDAV calendar and reports the discrepancies — events missing remotely, leftover
 * {@code @copilot} files that a sync would remove, foreign entries that are never touched.
 * Strictly read-only: the CalDAV adapter is only used to list resources, nothing is written or
 * deleted without a person confirming a real sync.</p>
 */
@Component
public class CalendarSyncAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncAgent.class);

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final int DEFAULT_DAYS_AHEAD = 30;
    private static final int MAX_REPORTED = 20;

    private final CalendarEventService events;
    private final CalDavConnectionService connections;
    private final CalDavClient client;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CalendarSyncAgent(CalendarEventService events, CalDavConnectionService connections,
                             CalDavClient client, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.events = events;
        this.connections = connections;
        this.client = client;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "calendar-sync";
    }

    @Override
    public AgentResult run(AgentContext context) {
        return RequestContext.runAs(context.tenantId(), () -> doRun(context));
    }

    private AgentResult doRun(AgentContext context) {
        UUID tenantId = context.tenantId();
        UUID userId = context.actorUserId();

        String scope = context.input("scope");
        scope = scope == null || scope.isBlank() ? "all" : scope.strip().toLowerCase(java.util.Locale.ROOT);
        if (!scope.equals("all") && !scope.equals("caldav") && !scope.equals("internal")) {
            return AgentResult.failed("scope '" + scope + "' ist unbekannt — erlaubt sind all, caldav, internal");
        }
        int daysAhead = parseDaysAhead(context.input("daysAhead"));

        LocalDate from = LocalDate.now(ZONE);
        LocalDate to = from.plusDays(daysAhead);

        List<Map<String, Object>> internal;
        try {
            internal = events.events(tenantId, userId, true, from, to);
        }
        catch (IllegalArgumentException e) {
            return AgentResult.failed("Zeitraum ungültig: " + e.getMessage());
        }
        catch (Exception e) {
            return AgentResult.failed("Interner Kalender nicht lesbar: " + e.getMessage());
        }

        Set<String> localFiles = new LinkedHashSet<>();
        for (Map<String, Object> event : internal) {
            localFiles.add(IcsEventWriter.eventFileName(event));
        }

        if (scope.equals("internal")) {
            Map<String, String> meta = new LinkedHashMap<>(Map.of(
                    "scope", "internal",
                    "daysAhead", String.valueOf(daysAhead),
                    "internalEvents", String.valueOf(internal.size())));
            return AgentResult.done("Interner Kalender gelesen: " + internal.size()
                    + " Eintrag/Einträge in den nächsten " + daysAhead + " Tagen", List.of(), meta);
        }

        if (userId == null) {
            return AgentResult.skipped("Für den Kalender-Sync fehlt der Nutzer, dem die "
                    + "CalDAV-Verbindung gehört (actorUserId ist leer)");
        }
        CalDavClient.Connection connection = connections.activeConnection(tenantId, userId)
                .orElse(null);
        if (connection == null) {
            return AgentResult.skipped("Keine CalDAV-Verbindung für diesen Nutzer — der Abgleich "
                    + "kann nur intern erfolgen");
        }

        List<CalDavClient.Resource> remote;
        try {
            remote = client.listResources(connection);
        }
        catch (CalDavException e) {
            return AgentResult.failed("Externer Kalender nicht lesbar: " + e.getMessage());
        }

        List<String> remoteHrefs = new ArrayList<>();
        for (CalDavClient.Resource resource : remote) {
            remoteHrefs.add(resource.href());
        }

        Discrepancies discrepancies = CalendarReconciler.reconcile(localFiles, remoteHrefs);

        Map<String, Object> report = new LinkedHashMap<>(discrepancies.asMap());
        report.put("scope", scope.equals("all") ? "all" : "caldav");
        report.put("daysAhead", daysAhead);
        report.put("provider", connection.serverUrl());
        report.put("missingRemotelyList", cap(discrepancies.missingRemotely(), MAX_REPORTED));
        report.put("orphanedRemotelyList", cap(discrepancies.orphanedRemotely(), MAX_REPORTED));

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("report", toJson(report));
        meta.put("scope", scope);
        meta.put("discrepancies", String.valueOf(discrepancies.discrepancyCount()));
        meta.put("internalEvents", String.valueOf(internal.size()));

        return AgentResult.done("Abgleich fertig: " + discrepancies.matched() + " Einträge stimmen "
                + "überein, " + discrepancies.missingRemotely().size() + " fehlen extern, "
                + discrepancies.orphanedRemotely().size() + " sind nur extern — es wurde nichts "
                + "verändert",
                List.of(new AgentResult.Produced("calendar_reconciliation",
                        tenantId.toString(),
                        discrepancies.discrepancyCount() + " Abweichung(en) gemeldet")), meta);
    }

    private int parseDaysAhead(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DAYS_AHEAD;
        }
        try {
            return Math.max(1, Math.min(CalendarEventService.MAX_RANGE_DAYS,
                    Integer.parseInt(raw.strip())));
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("daysAhead '" + raw + "' ist keine Zahl");
        }
    }

    private static List<String> cap(Set<String> values, int limit) {
        List<String> sorted = new ArrayList<>(new java.util.TreeSet<>(values));
        return sorted.size() <= limit ? sorted : new ArrayList<>(sorted.subList(0, limit));
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
