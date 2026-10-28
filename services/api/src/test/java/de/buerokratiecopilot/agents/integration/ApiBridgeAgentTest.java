package de.buerokratiecopilot.agents.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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

class ApiBridgeAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID ENDPOINT = UUID.randomUUID();
    private static final UUID EXISTING_EVENT = UUID.randomUUID();

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final ApiBridgeAgent agent = new ApiBridgeAgent(jdbc, audit, new ObjectMapper());

    @BeforeEach
    void setUp() {
        when(jdbc.queryForList(contains("from webhook_endpoints"), any(Object[].class)))
                .thenReturn(List.of(endpointRow()));
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(TENANT, ACTOR, "manual", Instant.now(), inputs);
    }

    private Map<String, Object> endpointRow() {
        return Map.of("id", ENDPOINT, "name", "Fachverfahren", "url", "https://example.org/hook");
    }

    @Test
    void transfersPayloadToTheOnlyActiveWebhookEndpointAndLogsIt() {
        when(jdbc.queryForList(contains("from webhook_events"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.update(contains("insert into webhook_events"), any(Object[].class))).thenReturn(1);

        AgentResult result = agent.run(ctx(Map.of(
                "sourceSystem", "platform",
                "entityType", "invoice",
                "entityId", UUID.randomUUID().toString(),
                "payload", "{\"amount\":42}")));

        assertTrue(result.ok());
        assertTrue(result.summary().contains("eingereiht"));
        assertEquals(1, result.produced().size());
        assertEquals("transfer", result.produced().getFirst().kind());
        assertNotNull(result.meta().get("webhookEventId"));
        verify(jdbc, times(1)).update(contains("insert into webhook_events"), any(Object[].class));
        // Every transfer is written to the audit trail.
        verify(audit, times(1)).record(any());
    }

    @Test
    void repeatedTransferWithSameKeyIsNotStoredTwice() {
        when(jdbc.queryForList(contains("from webhook_events"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", EXISTING_EVENT)));

        AgentResult result = agent.run(ctx(Map.of(
                "entityType", "invoice",
                "payload", "{\"amount\":42}",
                "idempotencyKey", "retry-1")));

        assertTrue(result.ok());
        assertTrue(result.summary().contains("Bereits übertragen"));
        assertEquals("true", result.meta().get("duplicate"));
        assertTrue(result.produced().isEmpty());
        verify(jdbc, never()).update(contains("insert into webhook_events"), any(Object[].class));
    }

    @Test
    void refusesHonestlyWhenNoEndpointExists() {
        when(jdbc.queryForList(contains("from webhook_endpoints"), any(Object[].class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of(
                "entityType", "invoice", "payload", "{}")));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("kein aktiver"));
        verify(jdbc, never()).update(contains("insert into webhook_events"), any(Object[].class));
    }

    @Test
    void refusesWhenSeveralEndpointsCouldBeMeant() {
        when(jdbc.queryForList(contains("from webhook_endpoints"), any(Object[].class)))
                .thenReturn(List.of(endpointRow(), Map.of("id", UUID.randomUUID(), "name", "Zweitens",
                        "url", "https://example.org/zwei")));

        AgentResult result = agent.run(ctx(Map.of(
                "entityType", "invoice", "payload", "{}")));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("eindeutig"));
    }

    @Test
    void routesToNamedEndpointWhenSeveralExist() {
        UUID second = UUID.randomUUID();
        when(jdbc.queryForList(contains("from webhook_endpoints"), any(Object[].class)))
                .thenReturn(List.of(endpointRow(), Map.of("id", second, "name", "Zweitens",
                        "url", "https://example.org/zwei")));
        when(jdbc.queryForList(contains("from webhook_events"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.update(contains("insert into webhook_events"), any(Object[].class))).thenReturn(1);

        AgentResult result = agent.run(ctx(Map.of(
                "entityType", "invoice", "payload", "{}", "endpointName", "Zweitens")));

        assertTrue(result.ok());
        assertEquals(second.toString(), result.meta().get("endpointId"));
    }

    @Test
    void refusesProviderTargetHonestlyWhenNoTransferPathExists() {
        AgentResult result = agent.run(ctx(Map.of(
                "entityType", "invoice", "payload", "{}", "targetSystem", "BANK_FINTS")));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keinen Übertragungsweg"));
        verify(jdbc, never()).update(contains("insert into webhook_events"), any(Object[].class));
    }

    @Test
    void routingRulesApplyWhenNoTargetGiven() {
        // payment.matched routes to BANK_FINTS by the manifest rules, and that path is refused honestly.
        AgentResult result = agent.run(ctx(Map.of(
                "entityType", "payment.matched", "payload", "{}")));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("BANK_FINTS"));
    }

    @Test
    void skipsWhenEntityTypeOrPayloadMissing() {
        AgentResult withoutType = agent.run(ctx(Map.of("payload", "{}")));
        assertFalse(withoutType.ok());
        assertTrue(withoutType.summary().contains("entityType"));

        AgentResult withoutPayload = agent.run(ctx(Map.of("entityType", "invoice")));
        assertFalse(withoutPayload.ok());
        assertTrue(withoutPayload.summary().contains("payload"));
    }

    @Test
    void rejectsPayloadThatIsNotJson() {
        AgentResult result = agent.run(ctx(Map.of(
                "entityType", "invoice", "payload", "kein json")));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("JSON"));
        verify(jdbc, never()).update(contains("insert into webhook_events"), any(Object[].class));
    }
}
