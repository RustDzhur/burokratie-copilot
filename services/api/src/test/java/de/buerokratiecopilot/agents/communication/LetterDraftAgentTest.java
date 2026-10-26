package de.buerokratiecopilot.agents.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.templates.GeneratedDocumentService;
import de.buerokratiecopilot.templates.TemplateService;

class LetterDraftAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();
    private TemplateService templateService;
    private GeneratedDocumentService generatedDocumentService;
    private JdbcTemplate jdbc;
    private LetterDraftAgent agent;

    @BeforeEach
    void setUp() {
        templateService = mock(TemplateService.class);
        generatedDocumentService = mock(GeneratedDocumentService.class);
        jdbc = mock(JdbcTemplate.class);
        agent = new LetterDraftAgent(templateService, generatedDocumentService, jdbc);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        Map<String, String> all = new java.util.HashMap<>(inputs);
        all.putIfAbsent("templateCode", "Mahnung");
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, all);
    }

    private void templateFound() {
        when(templateService.list(eq(tenantId), eq("Mahnung"), eq(List.of("PUBLISHED")),
                any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(Map.of(
                        "id", templateId, "code", "Mahnung", "category", "LETTER",
                        "status", "PUBLISHED"))));
    }

    @Test
    void generatesAndRecordsDocumentFromTemplate() {
        templateFound();
        when(templateService.generate(eq(tenantId), eq(templateId), any()))
                .thenReturn(new TemplateService.Generated(null, "Zahlungserinnerung",
                        "<p>Sehr geehrte Damen und Herren, ...</p>", List.of("Kunde.Name")));
        when(jdbc.queryForList(anyString(), any(UUID.class), any(UUID.class), any(),
                any(String.class))).thenReturn(List.of());
        UUID documentId = UUID.randomUUID();
        when(generatedDocumentService.record(eq(tenantId),
                any(GeneratedDocumentService.Request.class), any(UUID.class)))
                .thenReturn(new GeneratedDocumentService.Stored(documentId, "GENERATED",
                        "Zahlungserinnerung", "PDF", List.of()));

        AgentResult result = agent.run(ctx(Map.of(
                "title", "Zahlungserinnerung",
                "field.Kunde.Name", "Max Mustermann")));

        assertTrue(result.ok());
        assertEquals(documentId.toString(), result.meta().get("documentId"));
        assertTrue(result.produced().stream()
                .anyMatch(p -> p.kind().equals("generated_document")
                        && p.ref().equals(documentId.toString())));
        verify(generatedDocumentService).record(eq(tenantId),
                any(GeneratedDocumentService.Request.class), any(UUID.class));
    }

    @Test
    void skipsWhenTemplateDoesNotExist() {
        when(templateService.list(eq(tenantId), eq("Mahnung"), eq(List.of("PUBLISHED")),
                any(PageRequest.class))).thenReturn(Page.empty());

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("nicht gefunden"));
        verify(generatedDocumentService, never()).record(any(), any(), any());
    }

    @Test
    void reportsExistingDocumentInsteadOfGeneratingAgain() {
        templateFound();
        UUID documentId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(UUID.class), any(UUID.class), any(),
                any(String.class))).thenReturn(List.of(Map.of("id", documentId)));

        AgentResult result = agent.run(ctx(Map.of("title", "Zahlungserinnerung")));

        assertTrue(result.ok());
        assertEquals("true", result.meta().get("duplicate"));
        assertEquals(documentId.toString(), result.meta().get("documentId"));
        verify(templateService, never()).generate(any(), any(), any());
        verify(generatedDocumentService, never()).record(any(), any(), any());
    }
}
