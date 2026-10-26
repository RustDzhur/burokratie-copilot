package de.buerokratiecopilot.agents.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.retention.RetentionService;

/**
 * Working behaviour of the retention agent: deadlines are calculated from the documented
 * retention rules, candidates are listed, and nothing is ever scheduled or executed here.
 */
@ExtendWith(MockitoExtension.class)
class RetentionAgentTest {

    @Mock
    private RetentionService retention;
    @Mock
    private ComplianceQueries queries;

    private RetentionAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agent = new RetentionAgent(retention, queries);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    private Map<String, Object> facts(UUID id, String trigger, Integer months, boolean verified,
                                      boolean legalHold, Object documentDate) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("id", id);
        facts.put("original_filename", "rechnung.pdf");
        facts.put("deleted_at", null);
        facts.put("legal_hold", legalHold);
        facts.put("retention_policy_id", UUID.randomUUID());
        facts.put("policy_code", "RECH");
        facts.put("retention_period_months", months);
        facts.put("retention_trigger", trigger);
        facts.put("legal_basis", "§ 147 AO");
        facts.put("requires_verification", !verified);
        facts.put("document_date", documentDate);
        facts.put("received_at", null);
        return facts;
    }

    @Test
    void computesExpiredDeadlineFromDocumentDate() {
        UUID documentId = UUID.randomUUID();
        when(queries.documentRetentionFacts(any(), any()))
                .thenReturn(facts(documentId, "DOCUMENT_DATE", 12, true, false,
                        Date.valueOf("2020-01-01")));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("status")).isEqualTo("EXPIRED");
        assertThat(result.meta().get("deadline")).isEqualTo("2021-01-01");
        assertThat(result.meta().get("deletionExecuted")).isEqualTo("false");
        assertThat(result.summary()).contains("2021-01-01");
        verify(retention, never()).execute(any(), any(), any());
        verify(retention, never()).schedule(any(), any(), any(), any(), any());
    }

    @Test
    void computesFutureDeadlineAsOk() {
        UUID documentId = UUID.randomUUID();
        when(queries.documentRetentionFacts(any(), any()))
                .thenReturn(facts(documentId, "DOCUMENT_DATE", 120, true, false,
                        Date.valueOf(LocalDate.now().minusMonths(1))));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("status")).isEqualTo("OK");
    }

    @Test
    void legalHoldBlocksTheDeadline() {
        UUID documentId = UUID.randomUUID();
        when(queries.documentRetentionFacts(any(), any()))
                .thenReturn(facts(documentId, "DOCUMENT_DATE", 12, true, true,
                        Date.valueOf("2020-01-01")));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("status")).isEqualTo("BLOCKED");
    }

    @Test
    void unverifiedPolicyNeverCountsAsExpired() {
        UUID documentId = UUID.randomUUID();
        when(queries.documentRetentionFacts(any(), any()))
                .thenReturn(facts(documentId, "DOCUMENT_DATE", 12, false, false,
                        Date.valueOf("2020-01-01")));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("status")).isEqualTo("UNVERIFIED");
    }

    @Test
    void skipsHonestlyForManualTrigger() {
        UUID documentId = UUID.randomUUID();
        when(queries.documentRetentionFacts(any(), any()))
                .thenReturn(facts(documentId, "MANUAL", 12, true, false, Date.valueOf("2020-01-01")));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("manuelle Frist");
    }

    @Test
    void skipsHonestlyWithoutPolicy() {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> facts = facts(documentId, "DOCUMENT_DATE", 12, true, false, Date.valueOf("2020-01-01"));
        facts.put("retention_policy_id", null);
        when(queries.documentRetentionFacts(any(), any())).thenReturn(facts);

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Aufbewahrungsregel");
    }

    @Test
    void sweepListsCandidatesUpcomingAndHoldsWithoutActing() {
        UUID dueOne = UUID.randomUUID();
        UUID dueTwo = UUID.randomUUID();
        when(retention.dueForDeletion(any())).thenReturn(List.of(
                Map.of("id", dueOne, "original_filename", "alt1.pdf", "policy_code", "RECH"),
                Map.of("id", dueTwo, "original_filename", "alt2.pdf", "policy_code", "VERTR")));
        when(queries.upcomingDeletions(any(), anyInt())).thenReturn(List.of(
                Map.of("id", UUID.randomUUID(), "original_filename", "bald.pdf")));
        when(retention.underLegalHold(any())).thenReturn(List.of(
                Map.of("id", UUID.randomUUID(), "original_filename", "beweis.pdf")));

        AgentResult result = agent.run(ctx(Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("deletionCandidates")).isEqualTo("2");
        assertThat(result.meta().get("upcoming")).isEqualTo("1");
        assertThat(result.meta().get("legalHolds")).isEqualTo("1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .containsExactly("deletionCandidate", "deletionCandidate");
        verify(retention, never()).execute(any(), any(), any());
        verify(retention, never()).schedule(any(), any(), any(), any(), any());
    }

    @Test
    void emptySweepIsAnHonestReport() {
        when(retention.dueForDeletion(any())).thenReturn(List.of());
        when(queries.upcomingDeletions(any(), anyInt())).thenReturn(List.of());
        when(retention.underLegalHold(any())).thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).contains("keine Löschkandidaten");
    }
}
