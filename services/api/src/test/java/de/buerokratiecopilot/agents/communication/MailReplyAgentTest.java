package de.buerokratiecopilot.agents.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.mail.MailListService;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.templates.TemplateService;

class MailReplyAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID mailId = UUID.randomUUID();
    private MailListService mailListService;
    private TemplateService templateService;
    private OutboundMessageService outboundMessageService;
    private MailReplyAgent agent;

    @BeforeEach
    void setUp() {
        mailListService = mock(MailListService.class);
        templateService = mock(TemplateService.class);
        outboundMessageService = mock(OutboundMessageService.class);
        agent = new MailReplyAgent(mailListService, templateService, outboundMessageService);
        when(mailListService.detail(eq(tenantId), eq(mailId))).thenReturn(Map.of(
                "id", mailId.toString(),
                "subject", "Rechnung RE-2026-0042",
                "from_name", "Stadtwerke",
                "from_address", "abrechnung@stadtwerke.example",
                "direction", "INBOUND",
                "body", "Sehr geehrte Damen und Herren, anbei die Rechnung."));
    }

    private AgentContext ctx(Map<String, String> inputs) {
        Map<String, String> all = new java.util.HashMap<>(inputs);
        all.putIfAbsent("mailId", mailId.toString());
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, all);
    }

    @Test
    void draftsReplyFromMatchingTemplate() {
        UUID templateId = UUID.randomUUID();
        Page<Map<String, Object>> page = new PageImpl<>(List.of(Map.of(
                "id", templateId, "code", "Mahnung-Antwort", "category", "LETTER",
                "status", "PUBLISHED")));
        when(templateService.list(eq(tenantId), any(String.class), eq(List.of("PUBLISHED")),
                any(PageRequest.class))).thenReturn(page);
        when(templateService.generate(eq(tenantId), eq(templateId), any()))
                .thenReturn(new TemplateService.Generated(null,
                        "AW: Rechnung RE-2026-0042", "Sehr geehrte Damen und Herren, ...",
                        List.of("Betreff")));
        UUID draftId = UUID.randomUUID();
        when(outboundMessageService.draft(any(OutboundMessageService.DraftRequest.class)))
                .thenReturn(draftId);

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        assertEquals(draftId.toString(), result.meta().get("draftId"));
        ArgumentCaptor<OutboundMessageService.DraftRequest> captor =
                ArgumentCaptor.forClass(OutboundMessageService.DraftRequest.class);
        verify(outboundMessageService).draft(captor.capture());
        OutboundMessageService.DraftRequest request = captor.getValue();
        assertEquals("abrechnung@stadtwerke.example", request.toAddresses().getFirst());
        assertEquals("AW: Rechnung RE-2026-0042", request.subject());
        assertEquals(mailId, request.inReplyToMailId());
        assertTrue(request.idempotencyKey().contains(mailId.toString()));
    }

    @Test
    void draftsRuleBasedAcknowledgmentWithoutTemplate() {
        when(templateService.list(eq(tenantId), any(String.class), eq(List.of("PUBLISHED")),
                any(PageRequest.class))).thenReturn(Page.empty());
        UUID draftId = UUID.randomUUID();
        when(outboundMessageService.draft(any(OutboundMessageService.DraftRequest.class)))
                .thenReturn(draftId);

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        ArgumentCaptor<OutboundMessageService.DraftRequest> captor =
                ArgumentCaptor.forClass(OutboundMessageService.DraftRequest.class);
        verify(outboundMessageService).draft(captor.capture());
        assertTrue(captor.getValue().bodyText().contains("vielen Dank für Ihre Nachricht"));
        assertTrue(captor.getValue().subject().startsWith("AW: "));
        verify(templateService, never()).generate(any(), any(), any());
    }

    @Test
    void skipsDraftsInsteadOfAnsweringThem() {
        when(mailListService.detail(eq(tenantId), eq(mailId))).thenReturn(Map.of(
                "id", mailId.toString(), "subject", "Entwurf", "direction", "DRAFT"));

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("Entwurf"));
        verify(outboundMessageService, never()).draft(any(OutboundMessageService.DraftRequest.class));
    }

    @Test
    void failsWhenMailDoesNotExist() {
        when(mailListService.detail(eq(tenantId), eq(mailId)))
                .thenThrow(ApiException.notFound("Mail"));

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("nicht gefunden"));
    }
}
