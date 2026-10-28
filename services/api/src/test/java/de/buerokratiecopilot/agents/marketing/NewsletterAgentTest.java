package de.buerokratiecopilot.agents.marketing;

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

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class NewsletterAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final NewsletterAgent agent = new NewsletterAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    private Map<String, Object> contact(String email, boolean customer) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("display_name", "Marta Beispiel");
        row.put("email", email);
        row.put("is_customer", customer);
        row.put("metadata", "{}");
        return row;
    }

    @Test
    void assemblesADraftAndFlagsMissingConsentInsteadOfSending() {
        when(jdbc.queryForList(contains("from contacts"), any(Object[].class)))
                .thenReturn(List.of(contact("marta@example.de", true)));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("campaignName", "Herbst", "audienceSegment", "alle")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("recipients", "1")
                .containsEntry("consentVerified", "false")
                .containsEntry("sent", "false");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("generated_document", "newsletter-recipient");
    }

    @Test
    void skipsWhenTheSegmentHasNoAddressableContact() {
        when(jdbc.queryForList(contains("from contacts"), any(Object[].class)))
                .thenReturn(List.of(contact(null, false)));

        AgentResult result = agent.run(ctx(Map.of("campaignName", "Herbst", "audienceSegment", "alle")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Kontakte mit E-Mail");
    }

    @Test
    void filtersCustomersAndDeduplicatesAddresses() {
        List<NewsletterAgent.Recipient> recipients = NewsletterAgent.selectRecipients(List.of(
                contact("a@example.de", true), contact("A@example.de", true),
                contact("b@example.de", false)), "kunden");

        assertThat(recipients).hasSize(1);
        assertThat(recipients.getFirst().email()).isEqualTo("a@example.de");
    }
}
