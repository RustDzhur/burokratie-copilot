package de.buerokratiecopilot.agents.integration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.integrations.adapters.IntegrationAdapterCatalogue;

/**
 * {@code api-bridge} — API-Brücke.
 *
 * <p>Transfers a payload from one system to another through the integrations that actually
 * exist. Routing follows the rules from the manifest; every transfer is logged both in the
 * delivery queue ({@code webhook_events}) and in the audit trail.</p>
 *
 * <p><strong>The bridge only uses transfer paths that exist.</strong> Today that is the
 * outbound webhook queue of the integrations module: a transfer enqueues a {@code PENDING}
 * {@code webhook_events} row for an active endpoint. A route that names a provider without
 * such a path is refused with that fact stated, instead of pretending a transfer happened —
 * the same honesty the adapter catalogue applies to its entries.</p>
 *
 * <p><strong>Every transfer is idempotent.</strong> A run with the same idempotency key —
 * given, or derived from source, entity and payload — returns the existing transfer instead
 * of enqueueing a second one, so a retry after a timeout cannot deliver the same payload
 * twice.</p>
 */
@Component
public class ApiBridgeAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(ApiBridgeAgent.class);

    /** Routing rules, mirroring the manifest config: entity type → target. */
    private static final Map<String, String> ROUTING = Map.of(
            "invoice", "webhook",
            "document", "webhook",
            "payment.matched", "BANK_FINTS");

    private static final String DEFAULT_TARGET = "webhook";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public ApiBridgeAgent(JdbcTemplate jdbc, AuditService audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "api-bridge";
    }

    @Override
    public AgentResult run(AgentContext context) {
        try {
            String entityType = context.input("entityType");
            if (IntegrationAgentSupport.blank(entityType)) {
                return AgentResult.skipped(
                        "Keine entityType angegeben; ohne sie ist keine Route bestimmbar.");
            }
            String payload = context.input("payload");
            if (IntegrationAgentSupport.blank(payload)) {
                return AgentResult.skipped("Keine payload angegeben; es gibt nichts zu übertragen.");
            }
            if (!isValidJson(payload)) {
                return AgentResult.failed("Die payload ist kein gültiges JSON; übertragen wird nur, "
                        + "was das Ziel auch lesen kann.");
            }

            String endpointIdInput = context.input("endpointId");
            if (!IntegrationAgentSupport.blank(endpointIdInput)) {
                try {
                    UUID.fromString(endpointIdInput.strip());
                }
                catch (IllegalArgumentException ex) {
                    return AgentResult.failed("endpointId ist keine gültige UUID: " + endpointIdInput);
                }
            }

            Route route = resolveRoute(context);
            if (!route.isWebhookRoute()) {
                String adapterNote = noteForProvider(route.target());
                return AgentResult.skipped("Für das Ziel '" + route.target()
                        + "' gibt es in dieser Fassung keinen Übertragungsweg. Unterstützt wird die "
                        + "Übertragung an aktive Webhook-Endpunkte des Arbeitsbereichs." + adapterNote);
            }

            EndpointPick pick = pickEndpoint(context.tenantId(), route);
            if (pick.endpoint() == null) {
                return AgentResult.skipped(pick.problem());
            }
            Map<String, Object> endpoint = pick.endpoint();
            UUID endpointId = (UUID) endpoint.get("id");

            String key = idempotencyKey(context, route, entityType, payload);
            UUID existing = findExisting(context.tenantId(), key);
            if (existing != null) {
                audit(context, endpoint, entityType, existing, "duplicate");
                return AgentResult.done("Bereits übertragen: der Idempotenzschlüssel gehört zu einer "
                        + "vorhandenen Übertragung (" + existing + "). Es wurde nichts doppelt angelegt.",
                        context.produced(),
                        Map.of("duplicate", "true", "webhookEventId", existing.toString(),
                                "idempotencyKey", key));
            }

            UUID eventId = UUID.randomUUID();
            String eventType = IntegrationAgentSupport.blank(context.input("eventType"))
                    ? entityType
                    : context.input("eventType").strip();
            jdbc.update("""
                    insert into webhook_events (id, endpoint_id, tenant_id, event_type, payload, direction,
                                                status, attempts, idempotency_key)
                    values (?, ?, ?, ?, ?::jsonb, 'OUTBOUND', 'PENDING', 0, ?)
                    """, eventId, endpointId, context.tenantId(), eventType, payload, key);

            String label = "Übertragen an " + endpoint.get("name") + " (" + entityType + ")";
            context.addProduced("transfer", eventId.toString(), label);
            audit(context, endpoint, entityType, eventId, "enqueued");

            return AgentResult.done("Übertragung an den Webhook-Endpunkt '" + endpoint.get("name")
                    + "' eingereiht und protokolliert.", context.produced(),
                    Map.of("webhookEventId", eventId.toString(), "endpointId", endpointId.toString(),
                            "entityType", entityType, "eventType", eventType,
                            "sourceSystem", IntegrationAgentSupport.blankToDash(context.input("sourceSystem")),
                            "idempotencyKey", key));
        }
        catch (RuntimeException ex) {
            log.error("api-bridge: Übertragung fehlgeschlagen", ex);
            return AgentResult.failed("Übertragung fehlgeschlagen: " + ex.getMessage());
        }
    }

    /** The target for this run: explicit input first, then the routing rules, then the default. */
    private Route resolveRoute(AgentContext context) {
        if (!IntegrationAgentSupport.blank(context.input("endpointId"))) {
            return Route.byId(UUID.fromString(context.input("endpointId").strip()));
        }
        if (!IntegrationAgentSupport.blank(context.input("endpointName"))) {
            return Route.byName(context.input("endpointName").strip());
        }
        String target = context.input("targetSystem");
        if (IntegrationAgentSupport.blank(target)) {
            target = ROUTING.getOrDefault(context.input("entityType"), DEFAULT_TARGET);
        }
        target = target.strip();
        if ("webhook".equalsIgnoreCase(target)) {
            return Route.anyWebhook();
        }
        return Route.provider(target.toUpperCase(Locale.ROOT));
    }

    /** What the catalogue says about a provider, so a refusal explains itself. */
    private String noteForProvider(String provider) {
        try {
            IntegrationAdapterCatalogue.Adapter adapter = IntegrationAdapterCatalogue.byProvider(provider);
            return " (" + adapter.displayName() + ": " + adapter.note() + ")";
        }
        catch (RuntimeException ex) {
            return "";
        }
    }

    /**
     * The one endpoint a transfer may go to. Refused when there is none, and refused when
     * several could be meant and the caller did not say which one.
     */
    private EndpointPick pickEndpoint(UUID tenantId, Route route) {
        List<Map<String, Object>> endpoints = jdbc.queryForList("""
                select id, name, url from webhook_endpoints
                where tenant_id = ? and direction = 'OUTBOUND' and is_active and deleted_at is null
                order by created_at
                """, tenantId);

        if (route.endpointId() != null) {
            return endpoints.stream()
                    .filter(endpoint -> route.endpointId().equals(endpoint.get("id")))
                    .findFirst()
                    .map(endpoint -> new EndpointPick(endpoint, null))
                    .orElse(new EndpointPick(null, "Der Endpunkt " + route.endpointId()
                            + " ist unbekannt, nicht aktiv oder kein ausgehender Webhook."));
        }
        if (route.endpointName() != null) {
            List<Map<String, Object>> matches = endpoints.stream()
                    .filter(endpoint -> route.endpointName()
                            .equalsIgnoreCase(IntegrationAgentSupport.str(endpoint.get("name"))))
                    .toList();
            if (matches.size() == 1) {
                return new EndpointPick(matches.getFirst(), null);
            }
            return new EndpointPick(null, matches.isEmpty()
                    ? "Kein aktiver ausgehender Webhook-Endpunkt mit dem Namen '"
                            + route.endpointName() + "'."
                    : "Mehrere Endpunkte heißen '" + route.endpointName()
                            + "'; endpointId angeben, um das Ziel eindeutig zu machen.");
        }
        if (endpoints.isEmpty()) {
            return new EndpointPick(null, "Es ist kein aktiver ausgehender Webhook-Endpunkt "
                    + "eingerichtet. Zuerst einen Endpunkt unter Integrationen anlegen, dann erneut "
                    + "übertragen.");
        }
        if (endpoints.size() > 1) {
            return new EndpointPick(null, "Es gibt " + endpoints.size()
                    + " aktive Webhook-Endpunkte; endpointId oder endpointName angeben, um das Ziel "
                    + "eindeutig zu machen.");
        }
        return new EndpointPick(endpoints.getFirst(), null);
    }

    /**
     * The key that makes a transfer idempotent. A key the caller gives wins; otherwise one is
     * derived from source, entity and payload hash, so the same retried request lands on the
     * same key.
     */
    private String idempotencyKey(AgentContext context, Route route, String entityType, String payload) {
        String given = context.input("idempotencyKey");
        if (!IntegrationAgentSupport.blank(given)) {
            return IntegrationAgentSupport.truncate(given.strip(), 200);
        }
        String derived = "bridge:" + IntegrationAgentSupport.blankToDash(context.input("sourceSystem"))
                + ":" + entityType + ":" + IntegrationAgentSupport.blankToDash(context.input("entityId"))
                + ":" + route.target() + ":" + IntegrationAgentSupport.sha256(payload);
        return IntegrationAgentSupport.truncate(derived, 200);
    }

    private UUID findExisting(UUID tenantId, String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from webhook_events where tenant_id = ? and idempotency_key = ?",
                tenantId, key);
        return rows.isEmpty() ? null : (UUID) rows.getFirst().get("id");
    }

    private boolean isValidJson(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            return node != null;
        }
        catch (Exception ex) {
            return false;
        }
    }

    /** Every transfer is written to the audit trail; a failed audit must not fail the transfer. */
    private void audit(AgentContext context, Map<String, Object> endpoint, String entityType,
                       UUID eventId, String outcome) {
        try {
            audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.INTEGRATION, "bridge.transfer")
                    .actor(context.actorUserId() == null ? AuditEvent.ActorType.SYSTEM
                            : AuditEvent.ActorType.USER, context.actorUserId(), "api-bridge")
                    .tenant(context.tenantId())
                    .entity("webhook_event", eventId, IntegrationAgentSupport.str(endpoint.get("name")))
                    .description("Übertragung '" + entityType + "' von "
                            + IntegrationAgentSupport.blankToDash(context.input("sourceSystem")) + " an '"
                            + endpoint.get("name") + "' "
                            + ("duplicate".equals(outcome) ? "als Duplikat erkannt" : "eingereiht"))
                    .result(AuditEvent.Result.SUCCESS)
                    .build());
        }
        catch (RuntimeException ex) {
            log.warn("api-bridge: Übertragung {} konnte nicht auditiert werden: {}",
                    eventId, ex.getMessage());
        }
    }

    /** Where a payload goes. */
    private record Route(String target, UUID endpointId, String endpointName, boolean anyEndpoint) {

        static Route anyWebhook() {
            return new Route("webhook", null, null, true);
        }

        static Route byId(UUID endpointId) {
            return new Route("webhook", endpointId, null, false);
        }

        static Route byName(String endpointName) {
            return new Route("webhook", null, endpointName, false);
        }

        static Route provider(String target) {
            return new Route(target, null, null, false);
        }

        boolean isWebhookRoute() {
            return anyEndpoint || endpointId != null || endpointName != null;
        }
    }

    /** The resolved endpoint, or the reason there is none. */
    private record EndpointPick(Map<String, Object> endpoint, String problem) {
    }
}
