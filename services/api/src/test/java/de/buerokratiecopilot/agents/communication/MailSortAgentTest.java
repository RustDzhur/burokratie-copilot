package de.buerokratiecopilot.agents.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

class MailSortAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private MailSortAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        agent = new MailSortAgent(jdbc);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    @Test
    void classifiesInvoiceMailAndSuggestsFinanceChannel() {
        AgentResult result = agent.run(ctx(Map.of(
                "subject", "Rechnung RE-2026-0042 Stadtwerke",
                "bodyText", "Sehr geehrte Damen und Herren, anbei erhalten Sie unsere Rechnung.",
                "from", "abrechnung@stadtwerke.example")));

        assertTrue(result.ok());
        assertEquals("INVOICES", result.meta().get("category"));
        assertEquals("finance", result.meta().get("channel"));
        assertTrue(result.produced().stream()
                .anyMatch(p -> p.kind().equals("classification")
                        && p.ref().equals("INVOICES")));
    }

    @Test
    void marksSuspiciousMailAndRefusesAutomaticProcessing() {
        AgentResult result = agent.run(ctx(Map.of(
                "subject", "Wichtige Mitteilung",
                "bodyText", "Ihr Konto wurde gesperrt. Bitte bestätigen Sie Ihre Daten sofort.",
                "from", "support@bank.example")));

        assertTrue(result.ok());
        assertEquals("SUSPICIOUS", result.meta().get("category"));
        assertEquals("true", result.meta().get("suspicious"));
        assertEquals("security", result.meta().get("channel"));
        assertTrue(result.summary().contains("Verdächtige"));
    }

    @Test
    void suggestsCaseFromReferenceQuotedInSubject() {
        UUID caseId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(UUID.class), any(String.class)))
                .thenReturn(List.of(Map.of("id", caseId)));

        AgentResult result = agent.run(ctx(Map.of(
                "subject", "V-2026-0042 Vertragsunterlagen",
                "bodyText", "anbei die Unterlagen.",
                "from", "post@amt.example")));

        assertTrue(result.ok());
        assertEquals(caseId.toString(), result.meta().get("suggestedCaseId"));
    }

    @Test
    void reportsHonestlyWhenNoMailDataGiven() {
        AgentResult result = agent.run(ctx(Map.of()));
        assertFalse(result.ok());
        assertTrue(result.summary().contains("Keine Maildaten"));
    }
}
