package de.buerokratiecopilot.agents.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class OfferDraftAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final OfferDraftAgent agent = new OfferDraftAgent(jdbc, new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void calculatesNetDiscountAndGrossAndDoesNotSend() {
        when(jdbc.query(contains("from contacts"), any(org.springframework.jdbc.core.RowMapper.class), any(Object.class), any(Object.class))).thenReturn(List.of("Marta Beispiel"));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        String items = "[{\"description\":\"Beratung\",\"quantity\":2,\"unitPrice\":100.00}]";

        AgentResult result = agent.run(ctx(Map.of("contactId", UUID.randomUUID().toString(),
                "items", items, "discountPercent", "10", "taxRatePercent", "19",
                "validUntil", "2026-12-01")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("net", "200.00")
                .containsEntry("gross", "214.20")
                .containsEntry("sent", "false");
    }

    @Test
    void rejectsADiscountAboveTheConfiguredMaximum() {
        when(jdbc.query(contains("from contacts"), any(org.springframework.jdbc.core.RowMapper.class), any(Object.class), any(Object.class))).thenReturn(List.of("Marta Beispiel"));
        String items = "[{\"description\":\"Beratung\",\"quantity\":1,\"unitPrice\":100.00}]";

        AgentResult result = agent.run(ctx(Map.of("contactId", UUID.randomUUID().toString(),
                "items", items, "discountPercent", "20")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("außerhalb");
    }

    @Test
    void skipsWithoutPricedPositions() {
        when(jdbc.query(contains("from contacts"), any(org.springframework.jdbc.core.RowMapper.class), any(Object.class), any(Object.class))).thenReturn(List.of("Marta Beispiel"));

        AgentResult result = agent.run(ctx(Map.of("contactId", UUID.randomUUID().toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("items");
    }

    @Test
    void calculatesWithoutTaxWhenNoRateIsGiven() {
        OfferDraftAgent.Offer offer = OfferDraftAgent.calculate(List.of(
                        new OfferDraftAgent.Item("X", BigDecimal.ONE, new BigDecimal("100.00"))),
                BigDecimal.ZERO, null);

        assertThat(offer.net()).isEqualByComparingTo("100.00");
        assertThat(offer.gross()).isNull();
    }
}
