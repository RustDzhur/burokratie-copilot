package de.buerokratiecopilot.agents.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import de.buerokratiecopilot.privacy.DataSubjectService;
import de.buerokratiecopilot.retention.RetentionService;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Working behaviour of the GDPR guard: it finds personal data, plans deletions by retention
 * rules and never deletes anything itself.
 */
@ExtendWith(MockitoExtension.class)
class GdprGuardAgentTest {

    @Mock
    private DataSubjectService dataSubjects;
    @Mock
    private RetentionService retention;
    @Mock
    private ComplianceQueries queries;

    private GdprGuardAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agent = new GdprGuardAgent(dataSubjects, retention, queries);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    private Map<String, Object> documentFacts(UUID id, boolean legalHold, boolean verified) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("id", id);
        facts.put("original_filename", "kontoauszug.pdf");
        facts.put("deleted_at", null);
        facts.put("legal_hold", legalHold);
        facts.put("retention_policy_id", UUID.randomUUID());
        facts.put("policy_code", "BANK");
        facts.put("requires_verification", !verified);
        return facts;
    }

    @Test
    void marksDocumentAsDeletionCandidateButDoesNotDelete() {
        UUID documentId = UUID.randomUUID();
        when(queries.documentRetentionFacts(any(), any())).thenReturn(documentFacts(documentId, false, true));
        when(retention.dueForDeletion(any())).thenReturn(List.of(Map.of("id", documentId)));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("assessment")).isEqualTo("DELETE_CANDIDATE");
        assertThat(result.meta().get("deletionExecuted")).isEqualTo("false");
        verify(retention, never()).execute(any(), any(), any());
        verify(retention, never()).schedule(any(), any(), any(), any(), any());
        verify(retention, never()).placeLegalHold(any(), any(), any(), any());
        verify(retention, never()).liftLegalHold(any(), any(), any(), any());
    }

    @Test
    void reportsLegalHoldAsBlocked() {
        UUID documentId = UUID.randomUUID();
        when(queries.documentRetentionFacts(any(), any())).thenReturn(documentFacts(documentId, true, true));

        AgentResult result = agent.run(ctx(Map.of("documentId", documentId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("assessment")).isEqualTo("LEGAL_HOLD");
        assertThat(result.summary()).contains("Legal Hold");
    }

    @Test
    void buildsContactInventoryWithCandidatesAndBlocks() {
        UUID contactId = UUID.randomUUID();
        UUID dueDocument = UUID.randomUUID();
        UUID heldDocument = UUID.randomUUID();
        when(queries.contactExists(any(), any())).thenReturn(true);
        when(queries.contactCounts(any(), any())).thenReturn(Map.of(
                "identifiers", 2, "documents", 2, "documentsUnderLegalHold", 1,
                "contracts", 1, "invoices", 0, "cases", 0, "mailMessages", 5));
        when(queries.contactDocumentRetention(any(), any())).thenReturn(List.of(
                Map.of("id", dueDocument, "original_filename", "alt.pdf", "legal_hold", false, "policy_code", "RECH"),
                Map.of("id", heldDocument, "original_filename", "beweis.pdf", "legal_hold", true, "policy_code", "VERTR")));
        when(retention.dueForDeletion(any())).thenReturn(List.of(Map.of("id", dueDocument)));

        AgentResult result = agent.run(ctx(Map.of("contactId", contactId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("deletionCandidates")).isEqualTo("1");
        assertThat(result.meta().get("deletionBlocked")).isEqualTo("1");
        assertThat(result.meta().get("candidateIds")).isEqualTo(dueDocument.toString());
        assertThat(result.meta().get("deletionExecuted")).isEqualTo("false");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("dataInventory", "deletionCandidate", "deletionBlocked");
    }

    @Test
    void findsContactsThroughThePrivacyModule() {
        when(dataSubjects.search(any(), any())).thenReturn(List.of(
                Map.of("id", UUID.randomUUID(), "display_name", "Max Mustermann")));

        AgentResult result = agent.run(ctx(Map.of("query", "mustermann")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("hits")).isEqualTo("1");
    }

    @Test
    void skipsWhenSearchTermIsTooShort() {
        when(dataSubjects.search(any(), any()))
                .thenThrow(new ApiException(ErrorCode.INVALID_REQUEST, "Der Suchbegriff muss mindestens zwei Zeichen haben"));

        AgentResult result = agent.run(ctx(Map.of("query", "a")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("mindestens zwei Zeichen");
    }

    @Test
    void skipsHonestlyWithoutInputs() {
        AgentResult result = agent.run(ctx(Map.of()));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("contactId").contains("documentId").contains("query");
    }
}
