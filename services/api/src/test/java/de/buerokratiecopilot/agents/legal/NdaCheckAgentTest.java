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
import org.springframework.jdbc.core.RowMapper;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;

class NdaCheckAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final NdaCheckAgent agent = new NdaCheckAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void checksTheNamedClauseGroupsAndFilesAReviewTask() {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", documentId);
        document.put("original_filename", "nda.pdf");
        document.put("extracted_text", "Die Vertraulichkeit gilt für 3 Jahre. "
                + "Gerichtsstand ist Berlin. Eine Vertragsstrafe ist nicht vorgesehen. "
                + "Ausnahmen gelten für gesetzlich vorgeschriebene Offenlegungen.");
        document.put("summary", "");
        when(jdbc.queryForList(contains("from documents"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(document));
        when(jdbc.query(contains("case_documents"), any(RowMapper.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of());
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("hasConfidentiality", "true")
                .containsEntry("hasJurisdiction", "true")
                .containsEntry("risk", "NIEDRIG");
        assertThat(result.summary()).contains("keine Rechtsberatung");
    }

    @Test
    void skipsWithoutExtractedText() {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", documentId);
        document.put("original_filename", "scan.pdf");
        document.put("extracted_text", "");
        document.put("summary", "");
        when(jdbc.queryForList(contains("from documents"), any(Object.class), any(Object.class)))
                .thenReturn(List.of(document));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("kein extrahierter Text");
    }

    @Test
    void flagsAMissingConfidentialityClause() {
        NdaCheckAgent.Analysis analysis = NdaCheckAgent.analyse("Laufzeit 2 Jahre. "
                + "Gerichtsstand München.");

        assertThat(analysis.hasConfidentiality()).isFalse();
        assertThat(analysis.findings()).anyMatch(f -> f.contains("Geheimhaltungspflicht"));
    }
}
