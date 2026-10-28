package de.buerokratiecopilot.agents.documents;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.fields.FieldValueParser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * {@code doc-redact} — Anonymisierung.
 *
 * <p>Finds personal data by patterns — IBAN (checksum-verified), e-mail, telephone numbers and
 * addresses — and hands back a redaction plan: every position with its page and the surrounding
 * text. Nothing is cut or changed: {@code mode=PLAN_ONLY} is the whole contract, a confirmation
 * by a person is what turns a plan into redacted content, and this agent never does that part.</p>
 */
@Component
public class DocRedactAgent implements Agent {

    private final DocumentRepository documents;
    private final DocumentPageRepository pages;
    private final ObjectMapper mapper;

    public DocRedactAgent(DocumentRepository documents, DocumentPageRepository pages,
                          ObjectMapper mapper) {
        this.documents = documents;
        this.pages = pages;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "doc-redact";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID documentId = parseUuid(context.input("documentId"));
        if (documentId == null) {
            return AgentResult.skipped("Keine documentId angegeben; es gibt keinen Text zu prüfen.");
        }
        return RequestContext.runAs(context.tenantId(), () -> plan(context, documentId));
    }

    private AgentResult plan(AgentContext context, UUID documentId) {
        Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, context.tenantId())
                .orElse(null);
        if (document == null) {
            return AgentResult.failed(
                    "Dokument " + documentId + " wurde nicht gefunden (oder gehört einem anderen Tenant).");
        }

        List<DocumentPage> documentPages = pages.findByDocumentIdOrderByPageNumberAsc(documentId);
        List<RedactionPlanner.Finding> findings = new ArrayList<>();
        if (!documentPages.isEmpty()) {
            for (DocumentPage page : documentPages) {
                if (page.getTextContent() != null && !page.getTextContent().isBlank()) {
                    findings.addAll(RedactionPlanner.find(page.getTextContent(), page.getPageNumber()));
                }
            }
        }
        else if (document.getExtractedText() != null && !document.getExtractedText().isBlank()) {
            findings.addAll(RedactionPlanner.find(document.getExtractedText(), 0));
        }

        if (documentPages.isEmpty() && (document.getExtractedText() == null
                || document.getExtractedText().isBlank())) {
            return AgentResult.skipped(
                    "Das Dokument trägt keinen Text: es gibt nichts zu schwärzen.");
        }

        String findingsJson;
        try {
            findingsJson = mapper.writeValueAsString(findings);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            findingsJson = "[]";
        }

        Map<String, Long> byType = new LinkedHashMap<>();
        for (RedactionPlanner.Finding finding : findings) {
            byType.merge(finding.type(), 1L, Long::sum);
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("documentId", document.getId().toString());
        meta.put("findingsCount", String.valueOf(findings.size()));
        meta.put("findings", findingsJson);
        meta.put("byType", String.valueOf(byType));

        String summary = findings.isEmpty()
                ? "Keine personenbezogenen Daten nach den Mustern (IBAN, E-Mail, Telefon, Adresse) gefunden."
                : findings.size() + " Schwärzungsstelle(n) geplant: "
                        + String.join(", ", byType.entrySet().stream()
                                .map(entry -> entry.getValue() + "× " + entry.getKey()).toList())
                        + ". Es wurde nichts verändert.";

        return AgentResult.done(summary,
                List.of(new AgentResult.Produced("redaction-plan", document.getId().toString(),
                        findings.size() + " Schwärzungsstellen (nur Plan, nichts geändert)")),
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
