package de.buerokratiecopilot.agents.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class ReviewResponseAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final ReviewResponseAgent agent = new ReviewResponseAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void draftsAnAnswerToAngryFeedbackWithoutPublishingIt() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("reviewText",
                "Unverschämt, nie wieder! Katastrophe.", "rating", "1", "platform", "Google")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("classification", "BERECHTIGTER_AERGER")
                .containsEntry("published", "false");
        assertThat(result.summary()).contains("nichts veröffentlicht");
    }

    @Test
    void usesInvoiceFactsWhenAReferenceIsGiven() {
        when(jdbc.queryForList(contains("from invoices"), any(Object.class), any(Object.class))).thenReturn(List.of(
                Map.of("invoice_number", "RE-2026-001",
                        "issue_date", java.sql.Date.valueOf("2026-10-01"),
                        "gross_amount", new java.math.BigDecimal("119.00"),
                        "currency", "EUR")));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("reviewText",
                "Die Lieferung kam zu spät, sehr ärgerlich.", "rating", "2", "platform", "Google",
                "orderReference", "RE-2026-001")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("classification", "SACHLICHE_KRITIK");
    }

    @Test
    void skipsWithoutReviewText() {
        AgentResult result = agent.run(ctx(Map.of("rating", "4", "platform", "Google")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("reviewText");
    }

    @Test
    void aHighRatingWithoutComplaintIsPraise() {
        assertThat(ReviewResponseAgent.classify("Sehr zufrieden, gerne wieder", 5).kind())
                .isEqualTo("LOB");
        assertThat(ReviewResponseAgent.classify("Falsche Lieferung erhalten", 2).kind())
                .isEqualTo("SACHLICHE_KRITIK");
    }
}
