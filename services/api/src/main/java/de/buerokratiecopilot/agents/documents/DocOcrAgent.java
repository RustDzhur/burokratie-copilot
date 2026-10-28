package de.buerokratiecopilot.agents.documents;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.ocr.OcrEngine;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * {@code doc-ocr} — OCR-Texterkennung.
 *
 * <p>Prepares the text of a document whose pages carry none: with an OCR engine available, the
 * existing processing pipeline is advanced (text extraction plus recognition of the pages without
 * a text layer). When OCR is switched off and the deployment accepted that knowingly
 * ({@code BC_ALLOW_NO_OCR=true}), the agent refuses honestly and says what that means for the
 * document — it never pretends a scan was read. Pages that already carry text need no OCR, and
 * the agent says so instead of inventing work.</p>
 */
@Component
public class DocOcrAgent implements Agent {

    private final DocumentRepository documents;
    private final DocumentPageRepository pages;
    private final OcrEngine engine;
    private final DocumentProcessingPipeline pipeline;
    private final Environment environment;

    public DocOcrAgent(DocumentRepository documents, DocumentPageRepository pages, OcrEngine engine,
                       DocumentProcessingPipeline pipeline, Environment environment) {
        this.documents = documents;
        this.pages = pages;
        this.engine = engine;
        this.pipeline = pipeline;
        this.environment = environment;
    }

    @Override
    public String id() {
        return "doc-ocr";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID documentId = parseUuid(context.input("documentId"));
        if (documentId == null) {
            return AgentResult.skipped("Keine documentId angegeben; es gibt keinen Text zu erkennen.");
        }
        return RequestContext.runAs(context.tenantId(), () -> recognise(context, documentId));
    }

    private AgentResult recognise(AgentContext context, UUID documentId) {
        Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, context.tenantId())
                .orElse(null);
        if (document == null) {
            return AgentResult.failed(
                    "Dokument " + documentId + " wurde nicht gefunden (oder gehört einem anderen Tenant).");
        }

        List<DocumentPage> documentPages = pages.findByDocumentIdOrderByPageNumberAsc(documentId);
        boolean needsOcr = documentPages.isEmpty()
                || documentPages.stream().anyMatch(DocumentPage::isEmpty);
        if (!needsOcr) {
            return AgentResult.skipped(
                    "Keine OCR nötig: alle " + documentPages.size()
                            + " Seite(n) tragen bereits eine Textebene.");
        }

        boolean allowNoOcr = environment.getProperty("BC_ALLOW_NO_OCR", Boolean.class, false);
        if (!engine.isAvailable()) {
            if (allowNoOcr) {
                return AgentResult.skipped(
                        "OCR ist in diesem Deployment deaktiviert (BC_ALLOW_NO_OCR=true): "
                                + (documentPages.isEmpty() ? "das Dokument hat keine Seiten mit Text"
                                        : "Seiten ohne Textebene werden nicht gelesen")
                                + ". Das Dokument bleibt für die manuelle Prüfung stehen; es wird "
                                + "nicht behauptet, der Scan sei gelesen worden.");
            }
            return AgentResult.failed(
                    "OCR-Engine nicht verfügbar ("
                            + engine.name() + ") und BC_ALLOW_NO_OCR ist nicht gesetzt: "
                            + "Seiten ohne Textebene können nicht gelesen werden. OCR aktivieren "
                            + "oder BC_ALLOW_NO_OCR=true setzen, um das wissentlich zu akzeptieren.");
        }

        // The engine is there: the existing pipeline prepares the text (extraction and OCR of the
        // pages without a text layer). It is idempotent — steps already completed are not repeated.
        DocumentProcessingPipeline.AdvanceResult result = pipeline.advance(documentId);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("engine", engine.name());
        meta.put("documentId", documentId.toString());
        meta.put("status", result.status().name());
        meta.put("executedSteps", String.join(", ",
                result.executed().stream().map(Enum::name).toList()));

        if (!result.progressed()) {
            return AgentResult.failed("Die Textaufbereitung kam nicht voran: " + result.stopReason());
        }
        return AgentResult.done(
                "Textaufbereitung angestoßen: Schritte " + String.join(", ",
                        result.executed().stream().map(Enum::name).toList())
                        + " ausgeführt, Dokumentstatus jetzt " + result.status() + ".",
                List.of(new AgentResult.Produced("ocr-prep", documentId.toString(),
                        "Textaufbereitung über den bestehenden Pipeline-Lauf")),
                meta);
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
