package de.buerokratiecopilot.agents.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.notifications.NotificationService;
import de.buerokratiecopilot.notifications.domain.Notification;

class MonitorSystemsAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AuditService audit = mock(AuditService.class);
    private final MonitorSystemsAgent agent = new MonitorSystemsAgent(jdbc, notifications, audit,
            new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("from webhook_endpoints"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("group by endpoint_id"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("dedup_key"), any(Object[].class))).thenReturn(List.of());
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(TENANT, ACTOR, "manual", NOW, inputs);
    }

    private Map<String, Object> integration(UUID id, String status, String lastSyncStatus,
                                            long failures, boolean ownerAction, Object lastSyncAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("provider", "IMAP");
        row.put("category", "EMAIL");
        row.put("display_name", "Mail-Konto");
        row.put("status", status);
        row.put("is_enabled", true);
        row.put("last_sync_status", lastSyncStatus);
        row.put("last_sync_at", lastSyncAt);
        row.put("consecutive_failures", failures);
        row.put("requires_owner_action", ownerAction);
        row.put("owner_action_hint", null);
        row.put("last_error_code", "E1");
        row.put("last_error_message", "timeout");
        return row;
    }

    private Map<String, Object> endpoint(UUID id, boolean active, long failureCount,
                                         Object lastDeliveryAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("name", "Fachverfahren");
        row.put("url", "https://example.org/hook");
        row.put("is_active", active);
        row.put("last_delivery_at", lastDeliveryAt);
        row.put("failure_count", failureCount);
        return row;
    }

    @Test
    void reportsDownDegradedAndOk() {
        UUID down = UUID.randomUUID();
        UUID degraded = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(
                        integration(down, "ERROR", "SUCCESS", 2, false, Timestamp.from(NOW)),
                        integration(degraded, "DEGRADED", "SUCCESS", 0, false, Timestamp.from(NOW)),
                        integration(ok, "CONNECTED", "SUCCESS", 0, false, Timestamp.from(NOW))));

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("down"));
        assertEquals("1", result.meta().get("degraded"));
        assertEquals("1", result.meta().get("ok"));
        assertEquals(3, result.produced().size());
        assertTrue(result.produced().stream().allMatch(p -> p.kind().equals("system-check")));
    }

    @Test
    void staleIntegrationIsDegraded() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(integration(UUID.randomUUID(), "CONNECTED", "SUCCESS", 0, false,
                        null)));

        AgentResult result = agent.run(ctx(Map.of()));

        assertEquals("0", result.meta().get("down"));
        assertEquals("1", result.meta().get("degraded"));
    }

    @Test
    void notifiesForDownSystemThroughNotificationCentre() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(integration(UUID.randomUUID(), "ERROR", "SUCCESS", 2, false,
                        Timestamp.from(NOW))));

        AgentResult result = agent.run(ctx(Map.of()));

        assertEquals("1", result.meta().get("notified"));
        ArgumentCaptor<NotificationService.Request> captor = ArgumentCaptor
                .forClass(NotificationService.Request.class);
        verify(notifications, times(1)).notify(captor.capture());
        NotificationService.Request request = captor.getValue();
        assertEquals(Notification.Category.INTEGRATION_ERROR, request.category());
        assertEquals(Notification.Severity.WARNING, request.severity());
        assertTrue(request.dedupKey().startsWith("monitor-systems:"));
    }

    @Test
    void doesNotNotifyAgainForAKnownProblem() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(integration(UUID.randomUUID(), "ERROR", "SUCCESS", 2, false,
                        Timestamp.from(NOW))));
        when(jdbc.queryForList(contains("dedup_key"), any(Object[].class)))
                .thenReturn(List.of(Map.of("1", 1)));

        AgentResult result = agent.run(ctx(Map.of()));

        assertEquals("0", result.meta().get("notified"));
        verify(notifications, never()).notify(any());
    }

    @Test
    void notifyFalseSuppressesNotifications() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(integration(UUID.randomUUID(), "ERROR", "SUCCESS", 2, false,
                        Timestamp.from(NOW))));

        AgentResult result = agent.run(ctx(Map.of("notify", "false")));

        assertEquals("0", result.meta().get("notified"));
        verify(notifications, never()).notify(any());
    }

    @Test
    void skipsWhenNothingToMonitor() {
        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine verbundenen Systeme"));
    }

    @Test
    void scopeWebhooksSkipsIntegrationsAndFindsDeliveryFailures() {
        UUID webhook = UUID.randomUUID();
        when(jdbc.queryForList(contains("from webhook_endpoints"), any(Object[].class)))
                .thenReturn(List.of(endpoint(webhook, true, 0, Timestamp.from(NOW))));
        when(jdbc.queryForList(contains("group by endpoint_id"), any(Object[].class)))
                .thenReturn(List.of(Map.of("endpoint_id", webhook, "status", "DEAD_LETTER", "n", 3L)));

        AgentResult result = agent.run(ctx(Map.of("scope", "webhooks")));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("down"));
        assertEquals("0", result.meta().get("degraded"));
        verify(jdbc, never()).queryForList(contains("from integrations"), any(Object[].class));
    }

    @Test
    void inactiveWebhookIsReportedAsInfoNotFailure() {
        when(jdbc.queryForList(contains("from webhook_endpoints"), any(Object[].class)))
                .thenReturn(List.of(endpoint(UUID.randomUUID(), false, 9, null)));

        AgentResult result = agent.run(ctx(Map.of("scope", "webhooks")));

        assertTrue(result.ok());
        assertEquals("0", result.meta().get("down"));
        assertEquals("0", result.meta().get("degraded"));
    }
}
