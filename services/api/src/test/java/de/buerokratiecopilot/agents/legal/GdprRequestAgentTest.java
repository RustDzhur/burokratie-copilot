package de.buerokratiecopilot.agents.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.legal.GdprRequestAgent.Kind;
import de.buerokratiecopilot.privacy.DataSubjectService;

/**
 * Verifies that {@code legal-gdpr-request} executes access and export, prepares plans for the other
 * kinds, and never erases or rectifies on its own.
 */
@ExtendWith(MockitoExtension.class)
class GdprRequestAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONTACT = UUID.randomUUID();

    @Mock
    private DataSubjectService dataSubjects;

    @InjectMocks
    private GdprRequestAgent agent;

    private AgentContext context(String kind, Map<String, String> extra) {
        Map<String, String> inputs = new java.util.LinkedHashMap<>();
        inputs.put("contactId", CONTACT.toString());
        if (kind != null) {
            inputs.put("kind", kind);
        }
        inputs.putAll(extra);
        return new AgentContext(TENANT, USER, "manual", Instant.parse("2026-10-26T10:00:00Z"),
                inputs);
    }

    @Test
    void accessUsesTheOverviewAndRecordsTheRequest() {
        when(dataSubjects.overview(TENANT, USER, CONTACT))
                .thenReturn(Map.of("documents", 2, "contracts", 1));

        AgentResult result = agent.run(context("access", Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("kind")).isEqualTo("ACCESS");
        assertThat(result.meta().get("documents")).isEqualTo("2");
        assertThat(result.summary()).contains("Auskunft");
        verify(dataSubjects).overview(TENANT, USER, CONTACT);
        verify(dataSubjects, never()).erase(any(), any(), any(), any());
    }

    @Test
    void exportCreatesTheCopyAndCountsWhatIsInIt() {
        when(dataSubjects.export(TENANT, USER, CONTACT)).thenReturn(Map.of(
                "contact", Map.of("display_name", "Firma"),
                "documents", java.util.List.of(Map.of("id", UUID.randomUUID())),
                "contracts", java.util.List.of()));

        AgentResult result = agent.run(context("export", Map.of()));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("kind")).isEqualTo("EXPORT");
        assertThat(result.meta().get("documents")).isEqualTo("1");
        assertThat(result.produced()).extracting(p -> p.kind()).contains("gdpr-export");
    }

    @Test
    void erasureWithLegalHoldIsBlockedAndNothingIsDeleted() {
        when(dataSubjects.inventory(TENANT, CONTACT))
                .thenReturn(Map.of("documentsUnderLegalHold", 1, "documents", 3));

        AgentResult result = agent.run(context("erasure", Map.of("reason", "Kundenwunsch")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Legal Hold");
        verify(dataSubjects).inventory(TENANT, CONTACT);
        verify(dataSubjects, never()).erase(any(), any(), any(), any());
    }

    @Test
    void erasurePlanIsPreparedButNotExecuted() {
        when(dataSubjects.inventory(TENANT, CONTACT))
                .thenReturn(Map.of("documentsUnderLegalHold", 0, "documents", 2, "invoices", 1));

        AgentResult result = agent.run(context("erasure", Map.of("reason", "Kundenwunsch")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("requiresApproval")).isEqualTo("true");
        assertThat(result.summary()).contains("freigabepflichtig");
        verify(dataSubjects, never()).erase(any(), any(), any(), any());
    }

    @Test
    void rectificationRejectsUnknownFieldsBeforeTouchingData() {
        AgentResult result = agent.run(context("rectification", Map.of("fields", "email,gehalt")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("gehalt");
        verify(dataSubjects, never()).inventory(any(), any());
    }

    @Test
    void rectificationWithoutFieldsIsSkippedHonestly() {
        AgentResult result = agent.run(context("rectification", Map.of()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Felder");
    }

    @Test
    void unknownKindAndMissingContactAreRejected() {
        assertThat(agent.run(context("loeschen", Map.of())).ok()).isFalse();
        verify(dataSubjects, never()).overview(any(), any(), any());

        AgentResult missing = agent.run(new AgentContext(TENANT, USER, "manual", Instant.now(),
                Map.of("kind", "ACCESS")));
        assertThat(missing.ok()).isFalse();
        assertThat(missing.summary()).contains("Kontaktbezug");
    }

    @Test
    void kindParsingIsCaseInsensitiveAndDefaultsToAccess() {
        assertThat(GdprRequestAgent.parseKind(null)).isEqualTo(Kind.ACCESS);
        assertThat(GdprRequestAgent.parseKind(" export ")).isEqualTo(Kind.EXPORT);
        assertThat(GdprRequestAgent.parseKind("ERASURE")).isEqualTo(Kind.ERASURE);
        assertThat(GdprRequestAgent.parseKind("delete")).isNull();
    }

    @Test
    void describeListsOnlyNonZeroCounts() {
        assertThat(GdprRequestAgent.describe(Map.of("documents", 2, "contracts", 0)))
                .isEqualTo("Dokumente: 2");
        assertThat(GdprRequestAgent.describe(Map.of())).isEqualTo("keine verknüpften Daten");
    }
}
