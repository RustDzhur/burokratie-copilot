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

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.shared.storage.BlobStore;

class MailAcceptAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID mailId = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private DocumentService documentService;
    private BlobStore blobStore;
    private MailAcceptAgent agent;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        documentService = mock(DocumentService.class);
        blobStore = mock(BlobStore.class);
        agent = new MailAcceptAgent(jdbc, documentService, blobStore);
        when(jdbc.queryForList(anyString(), any(UUID.class), any(UUID.class)))
                .thenReturn(List.of(Map.of(
                        "id", mailId, "direction", "INBOUND",
                        "from_name", "Stadtwerke",
                        "from_address", "abrechnung@stadtwerke.example")));
    }

    private AgentContext ctx(Map<String, String> inputs) {
        Map<String, String> all = new java.util.HashMap<>(inputs);
        all.putIfAbsent("mailId", mailId.toString());
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, all);
    }

    @Test
    void acceptsAttachmentAsDocumentThroughTheStandardPath() {
        UUID attachmentId = UUID.randomUUID();
        UUID blobId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(UUID.class)))
                .thenReturn(List.of(Map.of(
                        "id", attachmentId, "filename", "rechnung.pdf",
                        "mime_type", "application/pdf", "blob_id", blobId)));
        when(jdbc.queryForObject(anyString(), eq(String.class), any(UUID.class)))
                .thenReturn("tenant/" + tenantId + "/" + blobId);
        when(blobStore.retrieve(anyString()))
                .thenReturn(new ByteArrayInputStream("PDF-BYTES".getBytes()));

        UUID documentId = UUID.randomUUID();
        Document document = new Document(documentId, tenantId, "rechnung.pdf",
                "storage-key", "application/pdf", 9, "hash", Document.Source.EMAIL);
        when(documentService.receive(any(DocumentService.UploadRequest.class)))
                .thenReturn(new DocumentService.ReceivedDocument(document, false, null));

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("accepted"));
        assertTrue(result.produced().stream()
                .anyMatch(p -> p.kind().equals("document")
                        && p.ref().equals(documentId.toString())));
        verify(documentService).receive(any(DocumentService.UploadRequest.class));
    }

    @Test
    void skipsMailWithoutAttachments() {
        when(jdbc.queryForList(anyString(), any(UUID.class))).thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine Anhänge"));
        verify(documentService, never()).receive(any(DocumentService.UploadRequest.class));
    }

    @Test
    void treatsAlreadyLinkedAttachmentAsDoneWithoutRegisteringAgain() {
        UUID documentId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(UUID.class)))
                .thenReturn(List.of(Map.of(
                        "id", UUID.randomUUID(), "filename", "rechnung.pdf",
                        "mime_type", "application/pdf", "document_id", documentId)));

        AgentResult result = agent.run(ctx(Map.of()));

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("alreadyLinked"));
        assertEquals("0", result.meta().get("accepted"));
        verify(documentService, never()).receive(any(DocumentService.UploadRequest.class));
    }

    @Test
    void failsWhenMailDoesNotExist() {
        when(jdbc.queryForList(anyString(), any(UUID.class), any(UUID.class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("nicht gefunden"));
    }
}
