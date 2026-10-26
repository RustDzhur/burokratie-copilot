package de.buerokratiecopilot.agents.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.notifications.NotificationService;
import de.buerokratiecopilot.notifications.domain.Notification;

/**
 * {@code monitor-systems} — System-Monitoring.
 *
 * <p>Checks the availability of the connected integrations and webhooks from what the
 * platform recorded about them — the last success and the errors — never by opening new
 * connections to the outside. Every system gets an honest state ({@code OK},
 * {@code DEGRADED}, {@code DOWN} or informational), the run produces a report, and
 * problems raise notifications through the notification centre.</p>
 *
 * <p><strong>Notifications are deduplicated.</strong> The dedup key is derived from the
 * system and its state, and a key that already produced a notification does not produce
 * another one — a repeated monitoring run must not fill the inbox with the same alarm.</p>
 */
@Component
public class MonitorSystemsAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(MonitorSystemsAgent.class);

    /** Thresholds mirroring the manifest config. */
    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration STALE_AFTER = Duration.ofHours(24);
    /** The states that raise a notification. */
    private static final List<String> NOTIFY_LEVELS = List.of("DOWN", "DEGRADED");

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public MonitorSystemsAgent(JdbcTemplate jdbc, NotificationService notifications,
                               AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "monitor-systems";
    }

    @Override
    public AgentResult run(AgentContext context) {
        try {
            boolean checkIntegrations = scopeAllows(context.input("scope"), "integrations");
            boolean checkWebhooks = scopeAllows(context.input("scope"), "webhooks");
            boolean notify = !"false".equalsIgnoreCase(context.input("notify"));

            List<Map<String, Object>> integrationRows = checkIntegrations
                    ? loadIntegrations(context.tenantId()) : List.of();
            List<Map<String, Object>> endpointRows = checkWebhooks ? loadEndpoints(context.tenantId())
                    : List.of();
            List<Map<String, Object>> recentFailures = checkWebhooks ? loadRecentFailures(context)
                    : List.of();

            if (integrationRows.isEmpty() && endpointRows.isEmpty()) {
                return AgentResult.skipped("Es gibt keine verbundenen Systeme, die überwacht werden "
                        + "könnten.");
            }

            List<Health> report = new ArrayList<>();
            for (Map<String, Object> row : integrationRows) {
                report.add(healthOfIntegration(context, row));
            }
            for (Map<String, Object> endpoint : endpointRows) {
                report.add(healthOfWebhook(context, endpoint, recentFailures));
            }

            long down = countByLevel(report, "DOWN");
            long degraded = countByLevel(report, "DEGRADED");
            long ok = countByLevel(report, "OK");

            for (Health health : report) {
                context.addProduced("system-check", health.ref(),
                        health.name() + ": " + health.state()
                                + (IntegrationAgentSupport.blank(health.detail()) ? ""
                                        : " — " + health.detail()));
            }

            int notified = 0;
            if (notify && context.actorUserId() == null) {
                if (down + degraded > 0) {
                    log.info("monitor-systems: Probleme gefunden, aber kein Benutzer für "
                            + "Benachrichtigungen vorhanden ({} gestört, {} eingeschränkt)",
                            down, degraded);
                }
            }
            else if (notify) {
                for (Health health : report) {
                    if (NOTIFY_LEVELS.contains(health.level()) && sendAlert(context, health)) {
                        notified++;
                    }
                }
            }

            audit(context, report);

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("checked", String.valueOf(report.size()));
            meta.put("ok", String.valueOf(ok));
            meta.put("degraded", String.valueOf(degraded));
            meta.put("down", String.valueOf(down));
            meta.put("notified", String.valueOf(notified));
            meta.put("report", writeJson(report.stream().map(Health::asMap).toList()));

            if (down > 0 || degraded > 0) {
                return AgentResult.done("System-Monitoring: " + down + " gestört, " + degraded
                        + " eingeschränkt, " + ok + " in Ordnung." + (notified > 0
                                ? " " + notified + " Benachrichtigung(en) erstellt." : ""),
                        context.produced(), meta);
            }
            return AgentResult.done("System-Monitoring: alle " + report.size()
                    + " geprüften Systeme sind in Ordnung.", context.produced(), meta);
        }
        catch (RuntimeException ex) {
            log.error("monitor-systems: Prüfung fehlgeschlagen", ex);
            return AgentResult.failed("System-Monitoring fehlgeschlagen: " + ex.getMessage());
        }
    }

    private static boolean scopeAllows(String scope, String part) {
        String value = IntegrationAgentSupport.blank(scope) ? "all"
                : scope.strip().toLowerCase(Locale.ROOT);
        return "all".equals(value) || value.equals(part);
    }

    /** The state of one integration, from what its own rows recorded. */
    private Health healthOfIntegration(AgentContext context, Map<String, Object> row) {
        String ref = IntegrationAgentSupport.str(row.get("id"));
        String name = IntegrationAgentSupport.str(row.get("display_name"));
        String status = IntegrationAgentSupport.str(row.get("status"));
        boolean enabled = Boolean.TRUE.equals(row.get("is_enabled"));
        String lastSyncStatus = IntegrationAgentSupport.str(row.get("last_sync_status"));
        Object lastSyncAt = row.get("last_sync_at");
        long failures = IntegrationAgentSupport.num(row.get("consecutive_failures"));
        boolean ownerAction = Boolean.TRUE.equals(row.get("requires_owner_action"));

        if ("DISABLED".equals(status)) {
            return new Health(ref, "integration", name, "DISABLED", "INFO",
                    "Deaktiviert — wird nicht überwacht");
        }
        if ("NOT_SUPPORTED".equals(status)) {
            return new Health(ref, "integration", name, "NOT_SUPPORTED", "INFO",
                    "In dieser Fassung nicht enthalten");
        }
        if ("PENDING_AUTHORIZATION".equals(status)) {
            return new Health(ref, "integration", name, "PENDING_AUTHORIZATION", "DEGRADED",
                    "Wartet auf die Autorisierung durch den Eigentümer");
        }
        if (ownerAction) {
            return new Health(ref, "integration", name, "OWNER_ACTION", "DEGRADED",
                    "Braucht eine Handlung des Eigentümers"
                            + hintSuffix(row.get("owner_action_hint")));
        }
        if ("ERROR".equals(status)) {
            return new Health(ref, "integration", name, "ERROR", "DOWN", errorDetail(row, failures));
        }
        if (failures >= FAILURE_THRESHOLD) {
            return new Health(ref, "integration", name, "REPEATED_FAILURES", "DOWN",
                    failures + " aufeinanderfolgende Fehler");
        }
        if ("FAILED".equals(lastSyncStatus)) {
            return new Health(ref, "integration", name, "LAST_SYNC_FAILED", "DEGRADED",
                    "Letzte Synchronisierung fehlgeschlagen");
        }
        if ("DEGRADED".equals(status)) {
            return new Health(ref, "integration", name, "DEGRADED", "DEGRADED",
                    "Vom System als eingeschränkt gemeldet");
        }
        if (enabled && IntegrationAgentSupport.stale(context.now(), lastSyncAt, STALE_AFTER)) {
            return new Health(ref, "integration", name, "STALE", "DEGRADED",
                    lastSyncAt == null ? "Noch nie synchronisiert"
                            : "Letzte Synchronisierung liegt mehr als " + STALE_AFTER.toHours()
                                    + " Stunden zurück");
        }
        return new Health(ref, "integration", name, "OK", "OK",
                lastSyncAt == null ? "" : "Letzter Erfolg: " + IntegrationAgentSupport.toInstant(lastSyncAt));
    }

    /** The state of one webhook endpoint, from its delivery records. */
    private Health healthOfWebhook(AgentContext context, Map<String, Object> endpoint,
                                   List<Map<String, Object>> recentFailures) {
        String ref = IntegrationAgentSupport.str(endpoint.get("id"));
        String name = IntegrationAgentSupport.str(endpoint.get("name"));
        boolean active = Boolean.TRUE.equals(endpoint.get("is_active"));
        Object lastDelivery = endpoint.get("last_delivery_at");
        long failureCount = IntegrationAgentSupport.num(endpoint.get("failure_count"));
        long recent = recentFailures.stream()
                .filter(row -> ref.equals(IntegrationAgentSupport.str(row.get("endpoint_id"))))
                .mapToLong(row -> IntegrationAgentSupport.num(row.get("n")))
                .sum();

        if (!active) {
            return new Health(ref, "webhook_endpoint", name, "INACTIVE", "INFO", "Deaktiviert");
        }
        if (recent >= FAILURE_THRESHOLD) {
            return new Health(ref, "webhook_endpoint", name, "DELIVERY_FAILURES", "DOWN",
                    recent + " fehlgeschlagene Zustellungen in den letzten "
                            + STALE_AFTER.toHours() + " Stunden");
        }
        if (recent > 0) {
            return new Health(ref, "webhook_endpoint", name, "DELIVERY_FAILURES", "DEGRADED",
                    recent + " fehlgeschlagene Zustellungen in den letzten "
                            + STALE_AFTER.toHours() + " Stunden");
        }
        if (failureCount > 0) {
            return new Health(ref, "webhook_endpoint", name, "FAILURE_COUNT", "DEGRADED",
                    failureCount + " fehlgeschlagene Zustellungen insgesamt");
        }
        if (IntegrationAgentSupport.stale(context.now(), lastDelivery, STALE_AFTER)) {
            return new Health(ref, "webhook_endpoint", name, "STALE", "DEGRADED",
                    lastDelivery == null ? "Noch nie zugestellt"
                            : "Letzte Zustellung liegt mehr als " + STALE_AFTER.toHours()
                                    + " Stunden zurück");
        }
        return new Health(ref, "webhook_endpoint", name, "OK", "OK",
                lastDelivery == null ? "" : "Letzte Zustellung: "
                        + IntegrationAgentSupport.toInstant(lastDelivery));
    }

    /**
     * Sends one notification for a problem state, unless the same state was already reported:
     * the dedup key makes a repeated monitoring run quiet.
     */
    private boolean sendAlert(AgentContext context, Health health) {
        String dedupKey = "monitor-systems:" + health.ref() + ":" + health.state();
        try {
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "select 1 from notifications where tenant_id = ? and dedup_key = ? limit 1",
                    context.tenantId(), dedupKey);
            if (!existing.isEmpty()) {
                return false;
            }
            boolean down = "DOWN".equals(health.level());
            notifications.notify(new NotificationService.Request(context.tenantId(),
                    context.actorUserId(), null, "integration.monitoring",
                    "integration".equals(health.kind()) ? Notification.Category.INTEGRATION_ERROR
                            : Notification.Category.SYSTEM,
                    down ? Notification.Severity.WARNING : Notification.Severity.NOTICE,
                    "integration.monitoring", "integration.monitoring.body",
                    Map.of("title", health.name() + " meldet eine Störung",
                            "body", health.name() + ": " + health.state() + " — " + health.detail(),
                            "system", health.name(), "state", health.state(),
                            "detail", health.detail()),
                    health.kind(), parseUuidOrNull(health.ref()), "/integrations", false, dedupKey));
            return true;
        }
        catch (RuntimeException ex) {
            // A notification that cannot be stored must not fail the report.
            log.warn("monitor-systems: Benachrichtigung für {} konnte nicht erstellt werden: {}",
                    health.ref(), ex.getMessage());
            return false;
        }
    }

    private List<Map<String, Object>> loadIntegrations(UUID tenantId) {
        return jdbc.queryForList("""
                select id, provider, category, display_name, status, is_enabled, last_sync_status,
                       last_sync_at, consecutive_failures, requires_owner_action, owner_action_hint,
                       last_error_code, last_error_message
                from integrations
                where tenant_id = ? and deleted_at is null
                order by category, provider
                """, tenantId);
    }

    private List<Map<String, Object>> loadEndpoints(UUID tenantId) {
        return jdbc.queryForList("""
                select id, name, url, is_active, last_delivery_at, failure_count
                from webhook_endpoints
                where tenant_id = ? and deleted_at is null
                order by created_at
                """, tenantId);
    }

    private List<Map<String, Object>> loadRecentFailures(AgentContext context) {
        return jdbc.queryForList("""
                select endpoint_id, status, count(*) as n
                from webhook_events
                where tenant_id = ? and direction = 'OUTBOUND' and status in ('FAILED', 'DEAD_LETTER')
                  and created_at > ?
                group by endpoint_id, status
                """, context.tenantId(), java.sql.Timestamp.from(context.now().minus(STALE_AFTER)));
    }

    private static long countByLevel(List<Health> report, String level) {
        return report.stream().filter(health -> level.equals(health.level())).count();
    }

    private static String hintSuffix(Object hint) {
        String text = IntegrationAgentSupport.str(hint);
        return IntegrationAgentSupport.blank(text) ? "" : ": " + text;
    }

    private static String errorDetail(Map<String, Object> row, long failures) {
        String code = IntegrationAgentSupport.str(row.get("last_error_code"));
        String message = IntegrationAgentSupport.str(row.get("last_error_message"));
        if (!IntegrationAgentSupport.blank(code)) {
            return "Fehler " + code + (IntegrationAgentSupport.blank(message) ? "" : ": " + message);
        }
        return failures + " aufeinanderfolgende Fehler";
    }

    private static UUID parseUuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** A failing audit must not fail the report. */
    private void audit(AgentContext context, List<Health> report) {
        try {
            long down = countByLevel(report, "DOWN");
            long degraded = countByLevel(report, "DEGRADED");
            audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.INTEGRATION, "systems.monitored")
                    .actor(context.actorUserId() == null ? AuditEvent.ActorType.SYSTEM
                            : AuditEvent.ActorType.USER, context.actorUserId(), "monitor-systems")
                    .tenant(context.tenantId())
                    .description("System-Monitoring: " + report.size() + " Systeme geprüft ("
                            + down + " gestört, " + degraded + " eingeschränkt)")
                    .changes(Map.of("down", down, "degraded", degraded))
                    .result(AuditEvent.Result.SUCCESS)
                    .build());
        }
        catch (RuntimeException ex) {
            log.warn("monitor-systems: Bericht konnte nicht auditiert werden: {}", ex.getMessage());
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (Exception ex) {
            return "[]";
        }
    }

    /** One line of the report. */
    private record Health(String ref, String kind, String name, String state, String level,
                          String detail) {

        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ref", ref);
            map.put("kind", kind);
            map.put("name", name);
            map.put("state", state);
            map.put("level", level);
            map.put("detail", detail);
            return map;
        }
    }
}
