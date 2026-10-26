package de.buerokratiecopilot.agents.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.legal.LegalService;
import de.buerokratiecopilot.settings.RuleSettingsService;

/**
 * Working behaviour of the compliance check: real requirement data in, an honest report out.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceCheckAgentTest {

    @Mock
    private LegalService legal;
    @Mock
    private RuleSettingsService ruleSettings;
    @Mock
    private ComplianceQueries queries;

    private ComplianceCheckAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agent = new ComplianceCheckAgent(legal, ruleSettings, queries, new ObjectMapper());
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    private Map<String, Object> documentFacts(UUID id, boolean legalHold, UUID retentionPolicyId) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("id", id);
        facts.put("original_filename", "rechnung.pdf");
        facts.put("document_date", java.sql.Date.valueOf("2024-01-15"));
        facts.put("received_at", java.sql.Timestamp.from(java.time.Instant.parse("2024-01-20T10:00:00Z")));
        facts.put("deleted_at", null);
        facts.put("archived_at", null);
        facts.put("legal_hold", legalHold);
        facts.put("retention_policy_id", retentionPolicyId);
        facts.put("processing_status", "REVIEW_REQUIRED");
        facts.put("review_status", "NOT_REQUIRED");
        return facts;
    }

    private Map<String, Object> retentionFacts(UUID id, String policyCode, boolean verified, String legalBasis) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("id", id);
        facts.put("original_filename", "rechnung.pdf");
        facts.put("retention_policy_id", UUID.randomUUID());
        facts.put("policy_code", policyCode);
        facts.put("retention_period_months", 120);
        facts.put("retention_trigger", "DOCUMENT_DATE");
        facts.put("legal_basis", legalBasis);
        facts.put("requires_verification", !verified);
        return facts;
    }

    @Test
    void reportsViolationsForUnpolicedUnverifiedDocument() {
        UUID documentId = UUID.randomUUID();
        when(queries.documentFacts(any(), any())).thenReturn(documentFacts(documentId, false, UUID.randomUUID()));
        when(queries.documentRetentionFacts(any(), any()))
                .thenReturn(retentionFacts(documentId, "RECH", false, "§ 147 AO"));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("violations")).isEqualTo("2"); // POLICY_VERIFIED, REVIEW_CONSISTENCY
        assertThat(result.meta().get("passed")).isEqualTo("3"); // RETENTION_POLICY, POLICY_LEGAL_BASIS, LEGAL_HOLD
        assertThat(result.summary()).contains("verletzt");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("complianceViolation", "complianceReport");
    }

    @Test
    void reportsCleanDocumentWithVerifiedPolicy() {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> facts = documentFacts(documentId, false, UUID.randomUUID());
        facts.put("processing_status", "COMPLETED");
        facts.put("review_status", "NOT_REQUIRED");
        when(queries.documentFacts(any(), any())).thenReturn(facts);
        when(queries.documentRetentionFacts(any(), any()))
                .thenReturn(retentionFacts(documentId, "RECH", true, "§ 147 AO"));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("violations")).isEqualTo("0");
        assertThat(result.summary()).contains("alle").contains("erfüllt");
    }

    @Test
    void workspaceCheckFindsMissingImprintAndUnverifiedPolicy() {
        when(legal.missing()).thenReturn(List.of("name", "phone"));
        when(queries.unverifiedPolicies(any())).thenReturn(List.of(Map.of(
                "id", UUID.randomUUID(), "code", "VERTR", "retention_period_months", 36, "legal_basis", "§ 257 HGB")));
        when(ruleSettings.list(any())).thenReturn(List.of(Map.of("key", "radar.enabled", "value", true)));

        AgentResult result = agent.run(ctx(Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("violations")).isEqualTo("3"); // 2 imprint fields + 1 policy
        assertThat(result.meta().get("targetType")).isEqualTo("workspace");
    }

    @Test
    void skipsHonestlyWhenDocumentNotFound() {
        when(queries.documentFacts(any(), any())).thenReturn(null);

        AgentResult result = agent.run(ctx(Map.of("documentId", UUID.randomUUID().toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht gefunden");
    }

    @Test
    void skipsHonestlyForInvalidUuid() {
        AgentResult result = agent.run(ctx(Map.of("documentId", "keine-uuid")));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("UUID");
    }

    @Test
    void skipsHonestlyForDeletedDocument() {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> facts = documentFacts(documentId, false, null);
        facts.put("deleted_at", java.sql.Timestamp.from(java.time.Instant.now()));
        when(queries.documentFacts(any(), any())).thenReturn(facts);

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("gelöscht");
    }
}
