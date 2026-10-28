package de.buerokratiecopilot.agents.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.audit.query.AuditQueryService;

/**
 * Working behaviour of the audit trail: events of an object become a structured export that
 * answers who did what when.
 */
@ExtendWith(MockitoExtension.class)
class AuditTrailAgentTest {

    @Mock
    private AuditQueryService auditQueries;

    private AuditTrailAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agent = new AuditTrailAgent(auditQueries, new ObjectMapper());
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    private Map<String, Object> event(String action, String result, String category, String actorId,
                                      String actorType, String createdAt) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("action", action);
        event.put("result", result);
        event.put("category", category);
        event.put("actorUserId", actorId);
        event.put("actorType", actorType);
        event.put("createdAt", createdAt);
        return event;
    }

    @Test
    void assemblesExportWithStatisticsAndActors() {
        UUID documentId = UUID.randomUUID();
        when(auditQueries.events(eq(tenantId), isNull(), isNull(), isNull(), isNull(),
                eq("document"), eq(documentId), any()))
                .thenReturn(List.of(
                        event("VIEWED", "SUCCESS", "DOCUMENT_ACCESS", "user-1", "USER", "2026-10-25T10:00:00Z"),
                        event("DELETED", "SUCCESS", "DOCUMENT", "user-2", "USER", "2026-10-24T09:00:00Z"),
                        event("ACCESS_DENIED", "DENIED", "DOCUMENT_ACCESS", "user-3", "USER", "2026-10-23T08:00:00Z")));

        AgentResult result = agent.run(ctx(Map.of(
                "entityType", "Document", "entityId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("totalEvents")).isEqualTo("3");
        assertThat(result.meta().get("entityType")).isEqualTo("document");
        Map<String, Object> export = readExport(result);
        assertThat(export.get("totalEvents")).isEqualTo(3);
        assertThat(export.get("byResult")).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsEntry("SUCCESS", 2).containsEntry("DENIED", 1);
        });
        assertThat(export.get("byCategory")).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsEntry("DOCUMENT_ACCESS", 2).containsEntry("DOCUMENT", 1);
        });
        assertThat(export.get("actors")).isInstanceOfSatisfying(List.class, list -> {
            assertThat(list).containsExactly("user-1", "user-2", "user-3");
        });
        assertThat(export.get("newestEventAt")).isEqualTo("2026-10-25T10:00:00Z");
        assertThat(export.get("oldestEventAt")).isEqualTo("2026-10-23T08:00:00Z");
    }

    @Test
    void emptyTrailIsAnHonestReport() {
        UUID documentId = UUID.randomUUID();
        when(auditQueries.events(any(), any(), any(), any(), any(), anyString(), any(), any()))
                .thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of(
                "entityType", "document", "entityId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("totalEvents")).isEqualTo("0");
        assertThat(result.summary()).contains("0 Ereignisse");
    }

    @Test
    void skipsHonestlyForInvalidEntityId() {
        AgentResult result = agent.run(ctx(Map.of("entityType", "document", "entityId", "keine-uuid")));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("UUID");
    }

    @Test
    void skipsHonestlyWithoutEntityType() {
        AgentResult result = agent.run(ctx(Map.of("entityId", UUID.randomUUID().toString())));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("entityType");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readExport(AgentResult result) {
        try {
            return new ObjectMapper().readValue(result.meta().get("exportJson"), Map.class);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
