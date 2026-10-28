package de.buerokratiecopilot.agents.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/** {@code knowledge-search-answer}: cited extract or an honest "not found". */
class SearchAnswerAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private JdbcTemplate jdbc;
    private SearchAnswerAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        agent = new SearchAnswerAgent(jdbc);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void answersWithCitedExcerptsAndAConfidence() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                row("FA-1", "bescheid.pdf", 0.08, "Die <<Frist>> beträgt vier Wochen."),
                row("FA-2", "merkblatt.pdf", 0.06, "Die <<Frist>> ist im Bescheid genannt.")));

        AgentResult result = agent.run(context(Map.of("question", "Welche Frist gilt?")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("sources", "2")
                .containsEntry("confidence", "HIGH");
        assertThat(result.summary()).doesNotContain("<<").doesNotContain(">>");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .containsExactly("citation", "citation");
    }

    @Test
    void refusesWhenNothingMatchesInsteadOfGuessing() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        AgentResult result = agent.run(context(Map.of("question", "Gibt es eine Regelung?")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_SOURCE");
        assertThat(result.summary()).contains("keine belegte Fundstelle");
        assertThat(result.produced()).isEmpty();
    }

    @Test
    void refusesWithoutAQuestion() {
        AgentResult result = agent.run(context(Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_INPUT");
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void confidenceReflectsTheEvidenceNotThePhrasing() {
        assertThat(SearchAnswerAgent.confidence(0, 0)).isEqualTo("NONE");
        assertThat(SearchAnswerAgent.confidence(2, 0.08)).isEqualTo("HIGH");
        assertThat(SearchAnswerAgent.confidence(1, 0.08)).isEqualTo("MEDIUM");
        assertThat(SearchAnswerAgent.confidence(1, 0.001)).isEqualTo("LOW");
    }

    private static Map<String, Object> row(String number, String filename, double rank,
                                           String snippet) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("original_filename", filename);
        row.put("document_number", number);
        row.put("type_code", "bescheid");
        row.put("category", "GOVERNMENT");
        row.put("rank", rank);
        row.put("snippet", snippet);
        return row;
    }
}
