package de.buerokratiecopilot.agents.hospitality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;

class NoShowAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final OutboundMessageService outbound = mock(OutboundMessageService.class);
    private final NoShowAgent agent = new NoShowAgent(jdbc, workspace, outbound);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    private Map<String, Object> reservationRow(UUID id, String at) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("title", "Reservierung: Marta");
        row.put("metadata", "{\"reservationAt\":\"" + at + "\",\"reservationPartySize\":4,"
                + "\"guestName\":\"Marta\",\"guestEmail\":\"marta@example.de\"}");
        return row;
    }

    @Test
    void preparesAFollowupAndADraftWithoutSending() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForList(contains("from tasks"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(reservationRow(id, "2026-10-20T19:00:00")));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));
        when(outbound.draft(any(OutboundMessageService.DraftRequest.class)))
                .thenReturn(UUID.randomUUID());

        AgentResult result = agent.run(ctx(Map.of("reservationId", id.toString(),
                "channel", "EMAIL")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("draftCreated", "true")
                .containsEntry("sent", "false");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("task", "email_draft");
    }

    @Test
    void waitsForTheGracePeriod() {
        UUID id = UUID.randomUUID();
        String soon = LocalDateTime.now(java.time.ZoneId.of("Europe/Berlin"))
                .plusMinutes(30).withNano(0).toString();
        when(jdbc.queryForList(contains("from tasks"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(reservationRow(id, soon)));

        AgentResult result = agent.run(ctx(Map.of("reservationId", id.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("noch nicht abgelaufen");
    }

    @Test
    void skipsAnUnknownReservation() {
        UUID id = UUID.randomUUID();
        when(jdbc.queryForList(contains("from tasks"), any(Object.class), any(Object.class))).thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of("reservationId", id.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht vorhanden");
    }
}
