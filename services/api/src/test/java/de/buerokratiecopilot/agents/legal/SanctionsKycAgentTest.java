package de.buerokratiecopilot.agents.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class SanctionsKycAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final SanctionsKycAgent agent = new SanctionsKycAgent(jdbc, new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void reportsAHitForHumanReviewAndDoesNotBlock() {
        UUID contactId = UUID.randomUUID();
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("id", contactId);
        contact.put("display_name", "Müller & Söhne GmbH");
        contact.put("organization_name", "Müller & Söhne GmbH");
        contact.put("first_name", null);
        contact.put("last_name", null);
        contact.put("country_code", "DE");
        when(jdbc.queryForList(contains("from contacts"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(contact));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        when(workspace.notify(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        String candidates = """
                [{"name":"Mueller und Soehne","country":"DE","list":"EU-Sanktionen","type":"ENTITY"}]
                """;

        AgentResult result = agent.run(ctx(Map.of("contactId", contactId.toString(),
                "candidates", candidates, "threshold", "0.8")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("hits", "1")
                .containsEntry("blocked", "false");
        assertThat(result.summary()).contains("keine Sperre");
    }

    @Test
    void skipsHonestlyWithoutASuppliedList() {
        UUID contactId = UUID.randomUUID();
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("id", contactId);
        contact.put("display_name", "Marta Beispiel");
        contact.put("organization_name", null);
        contact.put("first_name", "Marta");
        contact.put("last_name", "Beispiel");
        contact.put("country_code", "DE");
        when(jdbc.queryForList(contains("from contacts"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(contact));

        AgentResult result = agent.run(ctx(Map.of("contactId", contactId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Sanktions-/PEP-Liste");
    }

    @Test
    void normalisesLegalFormsAndUmlauts() {
        assertThat(SanctionsKycAgent.similarity("Müller & Söhne GmbH", "Mueller Soehne"))
                .isGreaterThan(0.7);
    }
}
