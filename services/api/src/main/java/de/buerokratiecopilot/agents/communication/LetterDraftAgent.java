package de.buerokratiecopilot.agents.communication;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.templates.GeneratedDocumentService;
import de.buerokratiecopilot.templates.TemplateService;

/**
 * {@code letter-draft} — Briefentwurf.
 *
 * <p>Produces a standard letter from a published template with the values the caller supplies
 * ({@code field.*} inputs) and keeps the result in the generated-documents module, attached to the
 * case or contact the caller names. The templates module is the one that renders — and refuses:
 * an unknown placeholder or a missing required field blocks generation, so a half-filled letter
 * can never be filed by this agent.
 *
 * <p>Idempotent per template, case and title: a re-run finds the already generated document and
 * reports it instead of writing a second one.</p>
 */
@Component
public class LetterDraftAgent implements Agent {

    private final TemplateService templateService;
    private final GeneratedDocumentService generatedDocumentService;
    private final JdbcTemplate jdbc;

    public LetterDraftAgent(TemplateService templateService,
                            GeneratedDocumentService generatedDocumentService, JdbcTemplate jdbc) {
        this.templateService = templateService;
        this.generatedDocumentService = generatedDocumentService;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "letter-draft";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String code = context.input("templateCode");
        if (code == null || code.isBlank()) {
            return AgentResult.skipped("Keine Vorlage benannt: 'templateCode' fehlt.");
        }

        UUID templateId = findTemplate(context.tenantId(), code.strip());
        if (templateId == null) {
            return AgentResult.skipped(
                    "Vorlage „" + code.strip() + "“ nicht gefunden oder nicht veröffentlicht.");
        }

        UUID caseId = parseUuid(context.input("caseId"));
        UUID contactId = parseUuid(context.input("contactId"));
        Map<String, String> values = fieldValues(context);
        String title = context.input("title");
        if (title == null || title.isBlank()) {
            title = "Schreiben " + code.strip() + " " + LocalDate.now();
        }

        UUID existing = existingDocument(context.tenantId(), templateId, caseId, title);
        if (existing != null) {
            context.addProduced("generated_document", existing.toString(),
                    "Bereits erzeugtes Dokument wiedergefunden");
            return AgentResult.done("Brief wurde bereits erzeugt und nicht erneut angelegt.",
                    context.produced(), Map.of("documentId", existing.toString(), "duplicate", "true"));
        }

        TemplateService.Generated generated;
        try {
            generated = templateService.generate(context.tenantId(), templateId, values);
        }
        catch (ApiException ex) {
            return AgentResult.failed("Vorlage nicht ausfüllbar: " + ex.getMessage());
        }

        GeneratedDocumentService.Stored stored = generatedDocumentService.record(
                context.tenantId(),
                new GeneratedDocumentService.Request(templateId, null, caseId,
                        caseId != null ? "CASE" : (contactId != null ? "CONTACT" : null),
                        caseId != null ? caseId : contactId, title.strip(), "PDF",
                        generated.body(), new java.util.HashMap<>(values), null),
                context.actorUserId());

        context.addProduced("generated_document", stored.id().toString(),
                "Brief „" + title.strip() + "“ erzeugt (" + stored.status() + ")");
        return AgentResult.done(
                "Brief aus Vorlage „" + code.strip() + "“ erzeugt und abgelegt ("
                        + stored.status() + ").",
                context.produced(),
                Map.of("documentId", stored.id().toString(), "status", stored.status(),
                        "templateId", templateId.toString()));
    }

    /** The published template with the given code, if it exists in this tenant. */
    private UUID findTemplate(UUID tenantId, String code) {
        try {
            Page<Map<String, Object>> candidates = templateService.list(tenantId, code,
                    List.of("PUBLISHED"), null, PageRequest.of(0, 10));
            for (Map<String, Object> row : candidates.getContent()) {
                if (code.equalsIgnoreCase(String.valueOf(row.get("code")))) {
                    Object id = row.get("id");
                    return id instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(id));
                }
            }
        }
        catch (ApiException ex) {
            return null;
        }
        return null;
    }

    /** {@code field.*} inputs as the value map the renderer consumes. */
    private Map<String, String> fieldValues(AgentContext context) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        context.inputs().forEach((key, value) -> {
            if (key.startsWith("field.") && value != null) {
                values.put(key.substring("field.".length()), value);
            }
        });
        return values;
    }

    /** The document already generated for this template, case and title. */
    private UUID existingDocument(UUID tenantId, UUID templateId, UUID caseId, String title) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id from generated_documents
                where tenant_id = ? and template_id = ?
                  and coalesce(case_id::text, '') = coalesce(?::text, '')
                  and title = ?
                order by generated_at desc
                limit 1
                """, tenantId, templateId, caseId, title);
        if (rows.isEmpty()) {
            return null;
        }
        Object id = rows.getFirst().get("id");
        return id instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(id));
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
