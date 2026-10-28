package de.buerokratiecopilot.agents.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.CaseService;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;

class CrmDealAgentTest {

    private final CaseRepository cases = mock(CaseRepository.class);
    private final CaseService caseService = mock(CaseService.class);
    private final CrmStore store = mock(CrmStore.class);
    private final CrmDealAgent agent = new CrmDealAgent(cases, caseService, store);

    private Case dealCase(String stage, String lastMovedAt) {
        Map<String, Object> deal = new java.util.LinkedHashMap<>();
        deal.put("stage", stage);
        if (lastMovedAt != null) {
            deal.put("lastMovedAt", lastMovedAt);
        }
        Case caseRecord = mock(Case.class);
        when(caseRecord.getId()).thenReturn(UUID.randomUUID());
        when(caseRecord.getMetadata()).thenReturn(Map.of("deal", deal));
        when(caseRecord.getCreatedAt()).thenReturn(Instant.now());
        return caseRecord;
    }

    @Test
    void movesDealOnEventAndRecordsTheTimeline() {
        UUID tenant = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        Case caseRecord = dealCase("NEW", null);
        when(cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenant))
                .thenReturn(Optional.of(caseRecord));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual",
                Instant.now(), Map.of("caseId", caseId.toString(), "event", "CONTACTED")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("moved", "1").containsEntry("stalled", "0");
        assertThat(result.produced()).extracting(p -> p.kind()).contains("deal-stage");
        verify(store).mergeCaseMetadata(eq(tenant), eq(caseId), any());
        verify(caseService).record(eq(caseRecord), eq(CaseEvent.EventType.NOTE_ADDED), any(),
                anyString(), any());
    }

    @Test
    void eventWithoutRuleIsHonestlySkipped() {
        UUID tenant = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        Case caseRecord = dealCase("CONTACTED", null);
        when(cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenant))
                .thenReturn(Optional.of(caseRecord));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual",
                Instant.now(), Map.of("caseId", caseId.toString(), "event", "CONTACTED")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("keine Bewegung");
        verify(store, never()).mergeCaseMetadata(any(), any(), any());
    }

    @Test
    void marksAStalledDealAndFlagsTheTransitionOnly() {
        UUID tenant = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        String longAgo = Instant.now().minus(Duration.ofDays(20)).toString();
        Case caseRecord = dealCase("PROPOSAL", longAgo);
        when(cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenant))
                .thenReturn(Optional.of(caseRecord));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual",
                Instant.now(), Map.of("caseId", caseId.toString())));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("stalled", "1").containsEntry("moved", "0");
        assertThat(result.produced()).extracting(p -> p.kind()).contains("deal-stall");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(store).mergeCaseMetadata(eq(tenant), eq(caseId), captor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> deal = (Map<String, Object>) captor.getValue().get("deal");
        assertThat(deal).containsEntry("stalled", true).containsEntry("stage", "PROPOSAL");
    }

    @Test
    void wonDealMarksTheCounterpartyAsCustomer() {
        UUID tenant = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID counterparty = UUID.randomUUID();
        Case caseRecord = dealCase("PROPOSAL", null);
        when(caseRecord.getCounterpartyContactId()).thenReturn(counterparty);
        when(cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenant))
                .thenReturn(Optional.of(caseRecord));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual",
                Instant.now(), Map.of("caseId", caseId.toString(), "event", "CONTRACT_SIGNED")));

        assertThat(result.ok()).isTrue();
        verify(store).markCustomer(tenant, counterparty);
        assertThat(result.produced()).extracting(p -> p.kind()).contains("contact");
    }
}
