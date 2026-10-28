package de.buerokratiecopilot.agents.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class NpsAgentTest {

    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final NpsAgent agent = new NpsAgent(new ObjectMapper(), workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    private String responses(int count, int score) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"score\":").append(score)
                    .append(",\"comment\":\"Lieferung war zu spät\"}");
        }
        return json.append(']').toString();
    }

    @Test
    void evaluatesEnoughResponsesAndFilesAMeasureTask() {
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("surveyPeriod", "2026-10",
                "minResponses", "3", "responses", responses(5, 3))));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("responses", "5")
                .containsEntry("nps", "-100")
                .containsEntry("sent", "false");
    }

    @Test
    void refusesToPublishAnNpsOnTooFewAnswers() {
        AgentResult result = agent.run(ctx(Map.of("surveyPeriod", "2026-10",
                "minResponses", "10", "responses", responses(4, 9))));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Mindestzahl");
    }

    @Test
    void separatesPromotersPassivesAndDetractors() {
        NpsAgent.Result result = NpsAgent.evaluate(List.of(
                new NpsAgent.Response(10, "Alles gut", null, null),
                new NpsAgent.Response(8, null, null, null),
                new NpsAgent.Response(2, "Preis zu teuer", null, null)));

        assertThat(result.promoters()).isEqualTo(1);
        assertThat(result.passives()).isEqualTo(1);
        assertThat(result.detractors()).isEqualTo(1);
        assertThat(result.nps()).isEqualTo(0);
        assertThat(result.themes()).extracting(NpsAgent.Theme::theme).contains("Preis");
    }
}
