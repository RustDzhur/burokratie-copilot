package de.buerokratiecopilot.agents.integration;

import java.sql.Timestamp;
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
import de.buerokratiecopilot.integrations.IntegrationService;

/**
 * {@code data-sync} — Daten-Synchronisation.
 *
 * <p>Compares what the platform records on both sides of its system boundaries — the last
 * synchronisation runs of the connected integrations and the delivery state of the outbound
 * webhooks — and reports the discrepancies as a plan of concrete steps.</p>
 *
 * <p><strong>Nothing destructive ever runs here.</strong> Disconnecting or deleting an
 * integration stays a step in the plan that explicitly requires the owner's confirmation.
 * The only actions the agent itself may execute, and only after the caller opts in with
 * {@code apply=true}, are safe corrections: starting a synchronisation for an integration
 * whose last run failed, and re-enqueueing failed webhook deliveries. Both are
 * condition-gated on the current state, so a repeated run with the same inputs does not
 * duplicate anything.</p>
 */
@Component
public class DataSyncAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DataSyncAgent.class);

    /** How old a synchronisation may be before it counts as stale (mirrors manifest config). */
    private static final Duration MAX_AGE = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final IntegrationService integrations;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public DataSyncAgent(JdbcTemplate jdbc, IntegrationService integrations, AuditService audit,
                         ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.integrations = integrations;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "data-sync";
    }

    @Override
    public AgentResult run(AgentContext context) {
        try {
            boolean apply = "true".equalsIgnoreCase(context.input("apply"));
            UUID integrationId = parseUuid(context.input("integrationId"), "integrationId");
            String provider = IntegrationAgentSupport.blank(context.input("provider"))
                    ? null
                    : context.input("provider").strip().toUpperCase(Locale.ROOT);
            boolean fullScan = integrationId == null && provider == null;

            List<Map<String, Object>> integrationRows = loadIntegrations(context.tenantId(),
                    integrationId, provider);
            List<Map<String, Object>> endpointRows = fullScan ? loadEndpoints(context.tenantId())
                    : List.of();
            List<Map<String, Object>> recentFailures = fullScan ? loadRecentOutboundFailures(context)
                    : List.of();
            long rejectedCount = fullScan ? loadRejectedInbound(context) : 0L;

            if (integrationRows.isEmpty() && endpointRows.isEmpty()) {
                return AgentResult.skipped("Es gibt keine verbundenen Systeme, deren Datenstände "
                        + "verglichen werden könnten.");
            }

            List<Finding> findings = new ArrayList<>();
            for (Map<String, Object> row : integrationRows) {
                findings.addAll(compareIntegration(context, row));
            }
            for (Map<String, Object> endpoint : endpointRows) {
                findings.addAll(compareWebhook(context, endpoint, recentFailures));
            }
            if (rejectedCount > 0) {
                findings.add(new Finding("inbound-rejected", "tenant", "Eingehende Webhooks",
                        rejectedCount + " eingehende Ereignisse wurden wegen ungültiger Signatur abgelehnt.",
                        "Absender prüfen: Signaturgeheimnis abgleichen und fehlgeschlagene Sendungen "
                                + "erneut einspielen."));
            }

            for (Finding finding : findings) {
                context.addProduced("sync-discrepancy", finding.ref(),
                        finding.system() + ": " + finding.detail());
            }
            String scopeRef = integrationId != null ? integrationId.toString()
                    : provider != null ? provider : context.tenantId().toString();
            context.addProduced("sync-plan", "plan:" + scopeRef,
                    "Synchronisationsplan über " + (integrationRows.size() + endpointRows.size())
                            + " Systeme (" + findings.size() + " Abweichung(en))");
            audit(context, "data-sync.plan", "Synchronisationsplan erstellt",
                    Map.of("discrepancies", findings.size(), "apply", apply));

            int syncsStarted = 0;
            int deliveriesReenqueued = 0;
            if (!findings.isEmpty() && apply) {
                for (Map<String, Object> row : integrationRows) {
                    syncsStarted += applyIntegrationFixes(context, row, findings);
                }
                for (Map<String, Object> endpoint : endpointRows) {
                    deliveriesReenqueued += applyWebhookFixes(context, endpoint, findings);
                }
                if (syncsStarted + deliveriesReenqueued > 0) {
                    audit(context, "data-sync.apply", "Gefahrlose Korrekturen ausgeführt",
                            Map.of("syncsStarted", syncsStarted, "deliveriesReenqueued",
                                    deliveriesReenqueued));
                }
            }

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("discrepancies", String.valueOf(findings.size()));
            meta.put("integrationsChecked", String.valueOf(integrationRows.size()));
            meta.put("webhooksChecked", String.valueOf(endpointRows.size()));
            meta.put("apply", String.valueOf(apply));
            meta.put("syncsStarted", String.valueOf(syncsStarted));
            meta.put("deliveriesReenqueued", String.valueOf(deliveriesReenqueued));
            meta.put("plan", writeJson(findings.stream().map(Finding::asMap).toList()));

            if (findings.isEmpty()) {
                return AgentResult.done("Keine Abweichungen gefunden: alle "
                        + (integrationRows.size() + endpointRows.size()) + " geprüften Systeme sind im Soll.",
                        context.produced(), meta);
            }
            String summary = findings.size() + " Abweichung(en) gefunden; Synchronisationsplan erstellt.";
            summary += apply
                    ? " Ausgeführt: " + syncsStarted + " Synchronisierung(en) gestartet, "
                            + deliveriesReenqueued + " Zustellung(en) erneut eingereiht."
                    : " Zum Ausführen der gefahrlosen Korrekturen apply=true setzen; zerstörende Schritte "
                            + "bleiben dem Eigentümer vorbehalten.";
            return AgentResult.done(summary, context.produced(), meta);
        }
        catch (RuntimeException ex) {
            log.error("data-sync: Abgleich fehlgeschlagen", ex);
            return AgentResult.failed("Abgleich fehlgeschlagen: " + ex.getMessage());
        }
    }

    /** Compares one integration against what its synchronisation runs recorded. */
    private List<Finding> compareIntegration(AgentContext context, Map<String, Object> row) {
        List<Finding> findings = new ArrayList<>();
        String ref = IntegrationAgentSupport.str(row.get("id"));
        String name = IntegrationAgentSupport.str(row.get("display_name"));
        String status = IntegrationAgentSupport.str(row.get("status"));
        String lastRunStatus = IntegrationAgentSupport.str(row.get("last_run_status"));
        String lastSyncStatus = IntegrationAgentSupport.str(row.get("last_sync_status"));
        long itemsFailed = IntegrationAgentSupport.num(row.get("items_failed"));
        long consecutiveFailures = IntegrationAgentSupport.num(row.get("consecutive_failures"));
        boolean ownerAction = Boolean.TRUE.equals(row.get("requires_owner_action"));
        boolean runFailed = "FAILED".equals(lastRunStatus) || "FAILED".equals(lastSyncStatus);

        if ("PENDING_AUTHORIZATION".equals(status)) {
            findings.add(new Finding("pending-authorization", ref, name,
                    "Die Anbindung wartet auf die Autorisierung durch den Eigentümer.",
                    "Autorisierung beim Anbieter abschließen; danach erneut vergleichen."));
        }
        if (ownerAction && !"PENDING_AUTHORIZATION".equals(status)) {
            findings.add(new Finding("owner-action", ref, name,
                    "Die Anbindung braucht eine Handlung des Eigentümers: "
                            + IntegrationAgentSupport.str(row.get("owner_action_hint")),
                    "Hinweis des Systems umsetzen; danach erneut vergleichen."));
        }
        if (runFailed) {
            findings.add(new Finding("sync-failed", ref, name,
                    "Der letzte Synchronisationslauf ist fehlgeschlagen" + errorSuffix(row),
                    "Synchronisierung erneut starten (apply=true führt sie aus); bei wiederholtem Fehler "
                            + "die Anbindung prüfen."));
        }
        else {
            if (itemsFailed > 0) {
                findings.add(new Finding("items-failed", ref, name,
                        itemsFailed + " Datensätze des letzten Laufs sind fehlgeschlagen.",
                        "Fehlgeschlagene Datensätze prüfen; ein erneuter Lauf holt sie nach."));
            }
            if ("ERROR".equals(status) || consecutiveFailures > 0) {
                findings.add(new Finding("integration-error", ref, name,
                        "Die Anbindung ist im Fehlerzustand (" + consecutiveFailures
                                + " aufeinanderfolgende Fehler)" + errorSuffix(row),
                        "Anbindung trennen und neu verbinden — das erfordert die Bestätigung des "
                                + "Eigentümers."));
            }
        }
        if (Boolean.TRUE.equals(row.get("is_enabled"))
                && IntegrationAgentSupport.stale(context.now(), row.get("last_sync_at"), MAX_AGE)) {
            findings.add(new Finding("stale", ref, name,
                    "Der Datenstand ist veraltet: letzte Synchronisierung "
                            + (row.get("last_sync_at") == null ? "hat nie stattgefunden"
                                    : "liegt mehr als " + MAX_AGE.toHours() + " Stunden zurück"),
                    "Synchronisierung starten, um den Stand zu aktualisieren."));
        }
        return findings;
    }

    /** Compares one outbound webhook endpoint against its delivery records. */
    private List<Finding> compareWebhook(AgentContext context, Map<String, Object> endpoint,
                                         List<Map<String, Object>> recentFailures) {
        List<Finding> findings = new ArrayList<>();
        String ref = IntegrationAgentSupport.str(endpoint.get("id"));
        String name = IntegrationAgentSupport.str(endpoint.get("name"));
        long failureCount = IntegrationAgentSupport.num(endpoint.get("failure_count"));
        long recent = recentFailures.stream()
                .filter(row -> ref.equals(IntegrationAgentSupport.str(row.get("endpoint_id"))))
                .mapToLong(row -> IntegrationAgentSupport.num(row.get("n")))
                .sum();

        if (recent > 0) {
            findings.add(new Finding("webhook-failures", ref, name,
                    recent + " Zustellungen sind in den letzten " + MAX_AGE.toHours()
                            + " Stunden fehlgeschlagen oder liegen als unzustellbar in der Warteschlange.",
                    "Zustellungen erneut einreihen (apply=true) und den Empfänger prüfen."));
        }
        else if (failureCount > 0) {
            findings.add(new Finding("webhook-failures", ref, name,
                    "Der Endpunkt meldet " + failureCount + " fehlgeschlagene Zustellungen.",
                    "Empfänger prüfen; ausstehende Zustellungen erneut einreihen (apply=true)."));
        }
        if (IntegrationAgentSupport.stale(context.now(), endpoint.get("last_delivery_at"), MAX_AGE)) {
            findings.add(new Finding("webhook-stale", ref, name,
                    endpoint.get("last_delivery_at") == null ? "Seit der Einrichtung wurde keine "
                            + "Zustellung bestätigt."
                            : "Seit mehr als " + MAX_AGE.toHours()
                                    + " Stunden wurde keine Zustellung bestätigt.",
                    "Endpunkt prüfen; wenn er ungenutzt ist, deaktivieren (Bestätigung des Eigentümers)."));
        }
        return findings;
    }

    /**
     * The safe correction for a failed or stale integration: one synchronisation run. Gated on
     * the finding and on the integration being enabled and runnable, so a repeat run that
     * finds nothing to fix starts nothing.
     */
    private int applyIntegrationFixes(AgentContext context, Map<String, Object> row,
                                      List<Finding> findings) {
        String ref = IntegrationAgentSupport.str(row.get("id"));
        boolean needsSync = findings.stream()
                .anyMatch(finding -> ref.equals(finding.ref())
                        && ("sync-failed".equals(finding.kind()) || "stale".equals(finding.kind())));
        if (!needsSync) {
            return 0;
        }
        String status = IntegrationAgentSupport.str(row.get("status"));
        if (!Boolean.TRUE.equals(row.get("is_enabled"))
                || (!"CONNECTED".equals(status) && !"DEGRADED".equals(status))) {
            return 0;
        }
        try {
            IntegrationService.SyncRun run = integrations.startSync(context.tenantId(),
                    (UUID) row.get("id"), "MANUAL", context.actorUserId());
            context.addProduced("sync-run", run.runId().toString(),
                    "Synchronisierung gestartet für " + IntegrationAgentSupport.str(row.get("display_name")));
            return 1;
        }
        catch (RuntimeException ex) {
            log.warn("data-sync: Synchronisierung für {} konnte nicht gestartet werden: {}",
                    row.get("id"), ex.getMessage());
            return 0;
        }
    }

    /** The safe correction for failed deliveries: they go back into the queue, never away. */
    private int applyWebhookFixes(AgentContext context, Map<String, Object> endpoint,
                                  List<Finding> findings) {
        String ref = IntegrationAgentSupport.str(endpoint.get("id"));
        boolean needsRedelivery = findings.stream()
                .anyMatch(finding -> ref.equals(finding.ref()) && "webhook-failures".equals(finding.kind()));
        if (!needsRedelivery) {
            return 0;
        }
        int reenqueued = jdbc.update("""
                update webhook_events set status = 'PENDING', next_attempt_at = ?
                where tenant_id = ? and endpoint_id = ? and direction = 'OUTBOUND'
                  and status in ('FAILED', 'DEAD_LETTER')
                """, Timestamp.from(context.now()), context.tenantId(), endpoint.get("id"));
        if (reenqueued > 0) {
            context.addProduced("webhook-redelivery", ref,
                    reenqueued + " Zustellung(en) erneut eingereiht für "
                            + IntegrationAgentSupport.str(endpoint.get("name")));
        }
        return reenqueued;
    }

    private List<Map<String, Object>> loadIntegrations(UUID tenantId, UUID integrationId,
                                                       String provider) {
        StringBuilder sql = new StringBuilder("""
                select i.id, i.provider, i.category, i.display_name, i.status, i.is_enabled,
                       i.last_sync_status, i.last_sync_at, i.consecutive_failures,
                       i.requires_owner_action, i.owner_action_hint, i.last_error_code,
                       i.last_error_message,
                       r.status as last_run_status, r.items_processed, r.items_failed,
                       r.error_code, r.error_message
                from integrations i
                left join lateral (
                    select status, items_processed, items_failed, error_code, error_message
                    from integration_sync_runs r
                    where r.integration_id = i.id
                    order by r.started_at desc
                    limit 1
                ) r on true
                where i.tenant_id = ? and i.deleted_at is null
                  and i.status in ('CONNECTED', 'DEGRADED', 'ERROR', 'PENDING_AUTHORIZATION')
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (integrationId != null) {
            sql.append(" and i.id = ?");
            args.add(integrationId);
        }
        if (provider != null) {
            sql.append(" and i.provider = ?");
            args.add(provider);
        }
        sql.append(" order by i.category, i.provider");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> loadEndpoints(UUID tenantId) {
        return jdbc.queryForList("""
                select id, name, url, last_delivery_at, failure_count
                from webhook_endpoints
                where tenant_id = ? and direction = 'OUTBOUND' and is_active and deleted_at is null
                order by created_at
                """, tenantId);
    }

    private List<Map<String, Object>> loadRecentOutboundFailures(AgentContext context) {
        return jdbc.queryForList("""
                select endpoint_id, status, count(*) as n
                from webhook_events
                where tenant_id = ? and direction = 'OUTBOUND' and status in ('FAILED', 'DEAD_LETTER')
                  and created_at > ?
                group by endpoint_id, status
                """, context.tenantId(), Timestamp.from(context.now().minus(MAX_AGE)));
    }

    private long loadRejectedInbound(AgentContext context) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select count(*) as n
                from webhook_events
                where tenant_id = ? and direction = 'INBOUND' and status = 'REJECTED' and created_at > ?
                """, context.tenantId(), Timestamp.from(context.now().minus(MAX_AGE)));
        return rows.isEmpty() ? 0L : IntegrationAgentSupport.num(rows.getFirst().get("n"));
    }

    private UUID parseUuid(String value, String field) {
        if (IntegrationAgentSupport.blank(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " ist keine gültige UUID: " + value, ex);
        }
    }

    private static String errorSuffix(Map<String, Object> row) {
        String code = IntegrationAgentSupport.str(row.get("last_error_code"));
        if (IntegrationAgentSupport.blank(code)) {
            code = IntegrationAgentSupport.str(row.get("error_code"));
        }
        String message = IntegrationAgentSupport.str(row.get("last_error_message"));
        if (IntegrationAgentSupport.blank(message)) {
            message = IntegrationAgentSupport.str(row.get("error_message"));
        }
        if (IntegrationAgentSupport.blank(code) && IntegrationAgentSupport.blank(message)) {
            return "";
        }
        String text = "";
        if (!IntegrationAgentSupport.blank(code)) {
            text += code;
        }
        text += ": ";
        text += IntegrationAgentSupport.blank(message) ? "ohne Fehlermeldung" : message;
        return " (" + text + ")";
    }

    private void audit(AgentContext context, String action, String description, Map<String, Object> changes) {
        try {
            audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.INTEGRATION, action)
                    .actor(context.actorUserId() == null ? AuditEvent.ActorType.SYSTEM
                            : AuditEvent.ActorType.USER, context.actorUserId(), "data-sync")
                    .tenant(context.tenantId())
                    .description(description)
                    .changes(changes)
                    .result(AuditEvent.Result.SUCCESS)
                    .build());
        }
        catch (RuntimeException ex) {
            log.warn("data-sync: {} konnte nicht auditiert werden: {}", action, ex.getMessage());
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

    /** One discrepancy and the step the plan assigns to it. */
    private record Finding(String kind, String ref, String system, String detail, String step) {

        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            map.put("ref", ref);
            map.put("system", system);
            map.put("detail", detail);
            map.put("step", step);
            return map;
        }
    }
}
