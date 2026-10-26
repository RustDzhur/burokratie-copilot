package de.buerokratiecopilot.agents.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.contacts.identifiers.ContactIdentifierService;
import de.buerokratiecopilot.contacts.identifiers.IdentifierKind;

class CrmContactAgentTest {

    private final CrmStore store = mock(CrmStore.class);
    private final ContactIdentifierService identifiers = mock(ContactIdentifierService.class);
    private final CrmContactAgent agent = new CrmContactAgent(store, identifiers);

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "manual", null, inputs);
    }

    private Map<String, Object> contactRow(UUID id, String phone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("display_name", "Marta Beispiel");
        row.put("email", "marta@example.de");
        row.put("phone", phone);
        row.put("mobile", null);
        row.put("first_name", null);
        row.put("last_name", null);
        row.put("organization_name", null);
        row.put("website", null);
        row.put("preferred_language", null);
        row.put("is_customer", false);
        row.put("metadata", Map.of());
        return row;
    }

    @Test
    void fillsEmptyColumnsAndLeavesFilledOnesAlone() {
        UUID tenant = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(store.findContactRow(tenant, contactId))
                .thenReturn(Optional.of(contactRow(contactId, "0151 1234567")));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual", null,
                Map.of("contactId", contactId.toString(), "firstName", "Marta",
                        "phone", "0176 999999")));

        assertThat(result.ok()).isTrue();
        // first_name ist leer und wird gefüllt; phone ist bereits gefüllt und bleibt unangetastet.
        verify(store).setContactColumn(eq(tenant), eq(contactId), eq("first_name"), eq("Marta"));
        verify(store, never()).setContactColumn(eq(tenant), eq(contactId), eq("phone"), any());
        verify(identifiers, never()).add(any(), any(), any(), any(), any());
        assertThat(result.meta()).containsKey("updated");
        assertThat(result.meta().get("skipped")).contains("phone");
    }

    @Test
    void mergesFactsAndNoteIntoEnrichmentMetadata() {
        UUID tenant = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(store.findContactRow(tenant, contactId))
                .thenReturn(Optional.of(contactRow(contactId, null)));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual", null,
                Map.of("contactId", contactId.toString(),
                        "facts", "position=Geschäftsführerin;branche=Beratung",
                        "note", "Aus der Anfrage vom 12.10.")));

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(store).mergeContactMetadata(eq(tenant), eq(contactId), captor.capture());
        Map<String, Object> merge = captor.getValue();
        assertThat(merge).containsKey("enrichment");
        @SuppressWarnings("unchecked")
        Map<String, Object> enrichment = (Map<String, Object>) merge.get("enrichment");
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) enrichment.get("facts");
        assertThat(facts).containsEntry("position", "Geschäftsführerin")
                .containsEntry("branche", "Beratung");
        assertThat(enrichment).containsKey("notes");
    }

    @Test
    void overwriteReplacesAFilledValue() {
        UUID tenant = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(store.findContactRow(tenant, contactId))
                .thenReturn(Optional.of(contactRow(contactId, "0151 1234567")));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual", null,
                Map.of("contactId", contactId.toString(), "phone", "0176 999999",
                        "overwrite", "true")));

        assertThat(result.ok()).isTrue();
        verify(store).setContactColumn(eq(tenant), eq(contactId), eq("phone"), eq("0176 999999"));
        verify(identifiers).add(eq(tenant), eq(contactId), eq(IdentifierKind.PHONE),
                eq("0176 999999"), any());
    }

    @Test
    void skipsHonestlyWhenContactIsUnknown() {
        UUID tenant = UUID.randomUUID();
        when(store.findContactByEmail(tenant, "unbekannt@example.de")).thenReturn(Optional.empty());

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual", null,
                Map.of("email", "unbekannt@example.de", "firstName", "Marta")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("kein Kontakt gefunden");
        verify(store, never()).setContactColumn(any(), any(), any(), any());
    }

    @Test
    void registersANewEmailAsIdentifierWithoutTouchingThePrimaryAddress() {
        UUID tenant = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        when(store.findContactRow(tenant, contactId))
                .thenReturn(Optional.of(contactRow(contactId, null)));

        AgentResult result = agent.run(new AgentContext(tenant, UUID.randomUUID(), "manual", null,
                Map.of("contactId", contactId.toString(), "email", "marta.neu@example.de")));

        assertThat(result.ok()).isTrue();
        verify(identifiers).add(eq(tenant), eq(contactId), eq(IdentifierKind.EMAIL),
                eq("marta.neu@example.de"), any());
    }
}
