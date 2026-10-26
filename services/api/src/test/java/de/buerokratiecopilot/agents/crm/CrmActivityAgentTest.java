package de.buerokratiecopilot.agents.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.cases.CaseEventRepository;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;

class CrmActivityAgentTest {

    private final CaseRepository cases = mock(CaseRepository.class);
    private final CaseEventRepository events = mock(CaseEventRepository.class);
    private final CrmStore store = mock(CrmStore.class);
    private final CrmActivityAgent agent = new CrmActivityAgent(cases, events, store);

    @Test
    void recordsACallOnTheCaseTimeline() {
        UUID tenant = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        when(cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenant))
                .thenReturn(Optional.of(mock(Case.class)));
        CaseEvent saved = new CaseEvent(UUID.randomUUID(), tenant, caseId,
                CaseEvent.EventType.NOTE_ADDED, actor, "Rückruf wegen Angebot");
        when(events.save(any())).thenReturn(saved);

        AgentResult result = agent.run(new AgentContext(tenant, actor, "manual", null,
                Map.of("caseId", caseId.toString(), "type", "CALL",
                        "subject", "Rückruf wegen Angebot")));

        assertThat(result.ok()).isTrue();
        ArgumentCaptor<CaseEvent> captor = ArgumentCaptor.forClass(CaseEvent.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(CaseEvent.EventType.NOTE_ADDED);
        assertThat(captor.getValue().getActorUserId()).isEqualTo(actor);
        @SuppressWarnings("unchecked")
        Map<String, Object> activity = (Map<String, Object>) captor.getValue().getMetadata()
                .get("activity");
        assertThat(activity).containsEntry("type", "CALL")
                .containsEntry("subject", "Rückruf wegen Angebot");
        assertThat(result.meta()).containsKey("caseEventId");
    }

    @Test
    void mapsOutboundEmailToCorrespondenceSent() {
        UUID tenant = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        when(cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenant))
                .thenReturn(Optional.of(mock(Case.class)));
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual", null,
                Map.of("caseId", caseId.toString(), "type", "EMAIL_OUT",
                        "subject", "Angebot verschickt")));

        assertThat(result.ok()).isTrue();
        ArgumentCaptor<CaseEvent> captor = ArgumentCaptor.forClass(CaseEvent.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getEventType())
                .isEqualTo(CaseEvent.EventType.CORRESPONDENCE_SENT);
    }

    @Test
    void doesNotDuplicateAnActivityWithTheSameExternalKey() {
        UUID tenant = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID existingEventId = UUID.randomUUID();
        when(store.findCaseEventByExternalKey(tenant, "message-42"))
                .thenReturn(Optional.of(existingEventId));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual", null,
                Map.of("caseId", caseId.toString(), "type", "EMAIL_IN",
                        "subject", "Rückmeldung", "externalKey", "message-42")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("existing", "true");
        verify(events, never()).save(any());
    }

    @Test
    void failsHonestlyWithoutSubjectAndTarget() {
        AgentResult result = agent.run(new AgentContext(UUID.randomUUID(), UUID.randomUUID(),
                "manual", null, Map.of("type", "CALL")));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Betreff");

        AgentResult noTarget = agent.run(new AgentContext(UUID.randomUUID(), UUID.randomUUID(),
                "manual", null, Map.of("type", "CALL", "subject", "Rückruf")));
        assertThat(noTarget.ok()).isFalse();
        assertThat(noTarget.summary()).contains("caseId");
    }
}
