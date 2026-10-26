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
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.integrations.IntegrationService;

class DataSyncAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID INTEGRATION = UUID.randomUUID();
    private static final UUID WEBHOOK = UUID.randomUUID();
    private static final Instant NOW = Instant.now();

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IntegrationService integrations = mock(IntegrationService.class);
    private final AuditService audit = mock(AuditService.class);
    private final DataSyncAgent agent = new DataSyncAgent(jdbc, integrations, audit, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("from webhook_endpoints"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("group by endpoint_id"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("direction = 'INBOUND'"), any(Object[].class)))
                .thenReturn(List.of(Map.of("n", 0L)));
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(TENANT, ACTOR, "manual", NOW, inputs);
    }

    private Map<String, Object> integration(UUID id, String status, String lastRunStatus,
                                            long itemsFailed, long consecutiveFailures,
                                            boolean ownerAction, Object lastSyncAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("provider", "IMAP");
        row.put("category", "EMAIL");
        row.put("display_name", "Mail-Konto");
        row.put("status", status);
        row.put("is_enabled", true);
        row.put("last_sync_status", lastRunStatus);
        row.put("last_sync_at", lastSyncAt);
        row.put("consecutive_failures", consecutiveFailures);
        row.put("requires_owner_action", ownerAction);
        row.put("owner_action_hint", null);
        row.put("last_error_code", "E1");
        row.put("last_error_message", "timeout");
        row.put("last_run_status", lastRunStatus);
        row.put("items_processed", 0);
        row.put("items_failed", itemsFailed);
        row.put("error_code", "E1");
        row.put("error_message", "timeout");
        return row;
    }

    private Map<String, Object> endpoint(Object lastDeliveryAt, long failureCount) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", WEBHOOK);
        row.put("name", "Fachverfahren");
        row.put("url", "https://example.org/hook");
        row.put("last_delivery_at", lastDeliveryAt);
        row.put("failure_count", failureCount);
        return row;
    }

    @Test
    void findsDiscrepanciesAndBuildsAPlan() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(integration(INTEGRATION, "CONNECTED", "FAILED", 0, 2, false,
                        Timestamp.from(NOW))));
        when(jdbc.queryForList(contains("from webhook_endpoints"), any(Object[].class)))
                .thenReturn(List.of(endpoint(null, 5)));
        when(jdbc.queryForList(contains("group by endpoint_id"), any(Object[].class)))
                .thenReturn(List.of(Map.of("endpoint_id", WEBHOOK, "status", "FAILED", "n", 2L)));

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        assertTrue(result.summary().contains("Abweichung"));
        assertTrue(Integer.parseInt(result.meta().get("discrepancies")) >= 2);
        assertEquals("false", result.meta().get("apply"));
        assertTrue(result.produced().stream().anyMatch(p -> p.kind().equals("sync-plan")));
        assertTrue(result.produced().stream().anyMatch(p -> p.kind().equals("sync-discrepancy")));
        assertTrue(result.meta().get("plan").contains("step"));
        // Plan only: nothing was executed.
        verify(integrations, never()).startSync(any(), any(), any(), any());
        verify(jdbc, never()).update(contains("set status = 'PENDING'"), any(Object[].class));
    }

    @Test
    void reportsConsistencyHonestly() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(integration(INTEGRATION, "CONNECTED", "SUCCESS", 0, 0, false,
                        Timestamp.from(NOW))));

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        assertTrue(result.summary().contains("Keine Abweichungen"));
        assertEquals("0", result.meta().get("discrepancies"));
    }

    @Test
    void skipsWhenNothingToCompare() {
        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine verbundenen Systeme"));
    }

    @Test
    void applyStartsSyncForFailedIntegration() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(integration(INTEGRATION, "CONNECTED", "FAILED", 0, 2, false,
                        Timestamp.from(NOW))));
        when(integrations.startSync(any(), any(), any(), any()))
                .thenReturn(new IntegrationService.SyncRun(UUID.randomUUID(), INTEGRATION, "RUNNING", NOW));

        AgentResult result = agent.run(ctx(Map.of("apply", "true")));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("syncsStarted"));
        assertTrue(result.produced().stream().anyMatch(p -> p.kind().equals("sync-run")));
        verify(integrations, times(1)).startSync(any(), any(), any(), any());
    }

    @Test
    void applyIsGatedOnExplicitOptIn() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(integration(INTEGRATION, "CONNECTED", "FAILED", 0, 2, false,
                        Timestamp.from(NOW))));

        AgentResult result = agent.run(ctx(Map.of()));

        assertEquals("0", result.meta().get("syncsStarted"));
        verify(integrations, never()).startSync(any(), any(), any(), any());
    }

    @Test
    void applyDoesNotTouchHealthySystems() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(integration(INTEGRATION, "CONNECTED", "SUCCESS", 0, 0, false,
                        Timestamp.from(NOW))));

        agent.run(ctx(Map.of("apply", "true")));

        verify(integrations, never()).startSync(any(), any(), any(), any());
    }

    @Test
    void applyReenqueuesFailedWebhookDeliveries() {
        when(jdbc.queryForList(contains("from webhook_endpoints"), any(Object[].class)))
                .thenReturn(List.of(endpoint(Timestamp.from(NOW), 5)));
        when(jdbc.update(contains("set status = 'PENDING'"), any(Object[].class))).thenReturn(2);

        AgentResult result = agent.run(ctx(Map.of("apply", "true")));

        assertTrue(result.ok());
        assertEquals("2", result.meta().get("deliveriesReenqueued"));
        assertTrue(result.produced().stream().anyMatch(p -> p.kind().equals("webhook-redelivery")));
        verify(jdbc, times(1)).update(contains("set status = 'PENDING'"), any(Object[].class));
    }

    @Test
    void scopedComparisonLooksOnlyAtTheNamedIntegration() {
        when(jdbc.queryForList(contains("from integrations"), any(Object[].class)))
                .thenReturn(List.of(integration(INTEGRATION, "CONNECTED", "FAILED", 0, 2, false,
                        Timestamp.from(NOW))));

        AgentResult result = agent.run(ctx(Map.of("integrationId", INTEGRATION.toString())));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("integrationsChecked"));
        assertEquals("0", result.meta().get("webhooksChecked"));
        verify(jdbc, never()).queryForList(contains("from webhook_endpoints"), any(Object[].class));
        verify(jdbc, never()).queryForList(contains("group by endpoint_id"), any(Object[].class));
    }

    @Test
    void reportsInvalidIntegrationIdHonestly() {
        AgentResult result = agent.run(ctx(Map.of("integrationId", "keine-uuid")));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("UUID"));
    }
}
