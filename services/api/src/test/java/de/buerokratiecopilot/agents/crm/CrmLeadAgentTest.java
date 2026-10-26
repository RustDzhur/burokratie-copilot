package de.buerokratiecopilot.agents.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.contacts.ContactService;

class CrmLeadAgentTest {

    private final ContactService contacts = mock(ContactService.class);
    private final CrmStore store = mock(CrmStore.class);
    private final CrmLeadAgent agent = new CrmLeadAgent(contacts, store);

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "manual", null, inputs);
    }

    @Test
    void createsContactWithSourceAndLeadMarker() {
        UUID contactId = UUID.randomUUID();
        when(store.findContactByExternalKey(any(), any())).thenReturn(Optional.empty());
        when(store.findContactByEmail(any(), any())).thenReturn(Optional.empty());
        when(contacts.create(any(), any(), any())).thenReturn(Map.of("id", contactId));

        AgentResult result = agent.run(ctx(Map.of(
                "name", "Marta Beispiel", "email", "marta@example.de",
                "source", "website-form", "externalKey", "submission-42")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("created", "true")
                .containsEntry("source", "website-form")
                .containsEntry("stage", "NEW");
        assertThat(result.produced()).extracting(p -> p.kind()).contains("contact", "lead");
        verify(store).mergeContactMetadata(any(), eq(contactId), any());
    }

    @Test
    void doesNotDuplicateOnKnownEmail() {
        UUID existingId = UUID.randomUUID();
        when(store.findContactByEmail(any(), any())).thenReturn(Optional.of(existingId));

        AgentResult result = agent.run(ctx(Map.of(
                "name", "Marta Beispiel", "email", "marta@example.de")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("existing", "true")
                .containsEntry("matchedBy", "email");
        verify(contacts, never()).create(any(), any(), any());
    }

    @Test
    void doesNotDuplicateOnExternalKey() {
        UUID existingId = UUID.randomUUID();
        when(store.findContactByExternalKey(any(), any())).thenReturn(Optional.of(existingId));

        AgentResult result = agent.run(ctx(Map.of(
                "name", "Marta Beispiel", "externalKey", "submission-42")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("existing", "true")
                .containsEntry("matchedBy", "externalKey");
        verify(contacts, never()).create(any(), any(), any());
    }

    @Test
    void failsHonestlyWithoutName() {
        AgentResult result = agent.run(ctx(Map.of("email", "marta@example.de")));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Name");
    }
}
