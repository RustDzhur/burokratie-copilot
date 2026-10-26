package de.buerokratiecopilot.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.documents.DocDetectAgent;

class DocDetectAgentTest {

    private final DocDetectAgent agent = new DocDetectAgent();

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "manual", null, inputs);
    }

    @Test
    void detectsInvoiceFromFileName() {
        AgentResult result = agent.run(ctx(Map.of(
                "fileName", "Rechnung_RE-2026-0042_Stadtwerke.pdf", "mimeType", "application/pdf")));
        assertTrue(result.ok());
        assertEquals("invoice", result.meta().get("detectedType"));
        assertEquals("finance", result.meta().get("channel"));
    }

    @Test
    void detectsContractFromTextHint() {
        AgentResult result = agent.run(ctx(Map.of(
                "fileName", "scan_001.pdf", "mimeType", "application/pdf",
                "textHint", "Mietvertrag Wohnung")));
        assertTrue(result.ok());
        assertEquals("contract", result.meta().get("detectedType"));
        assertEquals("legal", result.meta().get("channel"));
    }

    @Test
    void reportsHonestlyWhenUnknown() {
        AgentResult result = agent.run(ctx(Map.of(
                "fileName", "notes.docx", "mimeType", "application/msword")));
        assertFalse(result.ok());
        assertTrue(result.summary().contains("nicht erkannt"));
    }
}
