package de.buerokratiecopilot.agents.hr;

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

class RecruitingScreeningAgentTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentWorkspace workspace = mock(AgentWorkspace.class);
    private final RecruitingScreeningAgent agent = new RecruitingScreeningAgent(jdbc, workspace);
    private final UUID tenant = UUID.randomUUID();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenant, UUID.randomUUID(), "manual", Instant.now(), inputs);
    }

    @Test
    void createsAShortlistAndInterviewTasksWithoutRejectingAnyone() {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", documentId);
        row.put("original_filename", "lebenslauf-marta.pdf");
        row.put("extracted_text", "Java Spring Boot PostgreSQL Erfahrung, Projektleitung");
        row.put("summary", "");
        when(jdbc.queryForList(contains("from documents"), any(Object[].class)))
                .thenReturn(List.of(row));
        when(workspace.ensureDocument(any(), any(), any(), any(), any(), any(), anyMap(), any()))
                .thenReturn(new AgentWorkspace.DocumentRef(UUID.randomUUID(), true));
        when(workspace.ensureTask(any(), any(), any(), any(), any(), any(), any(), anyMap()))
                .thenReturn(new AgentWorkspace.TaskRef(UUID.randomUUID(), true));

        AgentResult result = agent.run(ctx(Map.of("jobId", UUID.randomUUID().toString(),
                "applicationDocuments", documentId.toString(),
                "mustHaveCriteria", "java,spring,postgresql")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("candidates", "1")
                .containsEntry("shortlisted", "1")
                .containsEntry("autoRejected", "0");
    }

    @Test
    void skipsWithoutDocuments() {
        AgentResult result = agent.run(ctx(Map.of("jobId", UUID.randomUUID().toString(),
                "mustHaveCriteria", "java")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Bewerbungsdokumente");
    }

    @Test
    void scoresMustAndNiceCriteriaSeparately() {
        RecruitingScreeningAgent.Candidate candidate = RecruitingScreeningAgent.score(
                UUID.randomUUID(), "cv.pdf", "java spring boot postgresql",
                List.of("java", "spring", "kubernetes"), List.of("postgresql", "aws"));

        assertThat(candidate.mustMatched()).isEqualTo(2);
        assertThat(candidate.niceMatched()).isEqualTo(1);
        assertThat(candidate.openQuestions()).anyMatch(q -> q.contains("kubernetes"));
    }
}
