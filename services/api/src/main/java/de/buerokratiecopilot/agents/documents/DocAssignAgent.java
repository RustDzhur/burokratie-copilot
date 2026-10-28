package de.buerokratiecopilot.agents.documents;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentVersionRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.documents.matching.MatchingService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * {@code doc-assign} — Zuordnung &amp; Ablage.
 *
 * <p>Assigns a document to the case or contact it belongs to, by the platform's own matching
 * rules ({@link MatchingService}): cases linked by their reference, contacts by identifiers.
 * A proposal above the threshold is linked automatically with {@code link_origin='RULE'}; the
 * rest stay proposals a person decides on. A {@code caseId} input links the document to that case
 * explicitly. Both paths are idempotent: a link that already exists is not created a second time.</p>
 */
@Component
public class DocAssignAgent implements Agent {

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final MatchingService matching;
    private final CaseRepository cases;
    private final JdbcTemplate jdbc;

    public DocAssignAgent(DocumentRepository documents, DocumentVersionRepository versions,
                          MatchingService matching, CaseRepository cases, JdbcTemplate jdbc) {
        this.documents = documents;
        this.versions = versions;
        this.matching = matching;
        this.cases = cases;
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "doc-assign";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID documentId = parseUuid(context.input("documentId"));
        if (documentId == null) {
            return AgentResult.skipped("Keine documentId angegeben; es gibt nichts zuzuordnen.");
        }
        return RequestContext.runAs(context.tenantId(), () -> assign(context, documentId));
    }

    private AgentResult assign(AgentContext context, UUID documentId) {
        Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, context.tenantId())
                .orElse(null);
        if (document == null) {
            return AgentResult.failed(
                    "Dokument " + documentId + " wurde nicht gefunden (oder gehört einem anderen Tenant).");
        }

        List<AgentResult.Produced> produced = new ArrayList<>();
        Map<String, String> meta = new LinkedHashMap<>();

        // 1. An explicit caseId is a decision, not a proposal: link it, once.
        int explicitLinked = 0;
        UUID explicitCaseId = parseUuid(context.input("caseId"));
        if (explicitCaseId != null) {
            boolean caseExists = cases.findByIdAndTenantIdAndDeletedAtIsNull(explicitCaseId,
                    context.tenantId()).isPresent();
            if (!caseExists) {
                return AgentResult.failed(
                        "Vorgang " + explicitCaseId + " wurde nicht gefunden; das Dokument wurde nicht zugeordnet.");
            }
            int inserted = jdbc.update("""
                    insert into case_documents (id, tenant_id, case_id, document_id, link_origin, confidence,
                                                note, linked_by, confirmed_at)
                    values (gen_random_uuid(), ?, ?, ?, 'RULE', 1.0,
                            'Zuordnung per Agent doc-assign (manueller Lauf)', ?, now())
                    on conflict (case_id, document_id) do nothing
                    """, context.tenantId(), explicitCaseId, documentId, context.actorUserId());
            explicitLinked = inserted;
            if (inserted > 0) {
                produced.add(new AgentResult.Produced("case-link", explicitCaseId.toString(),
                        "Dokument dem Vorgang zugeordnet"));
            }
            meta.put("explicitCaseId", explicitCaseId.toString());
        }

        // 2. The rule-based proposals: case reference, contract number, contact identifiers.
        int proposals = 0;
        int autoLinked = 0;
        String threshold = "";
        DocumentVersion version = versions.findFirstByDocumentIdOrderByVersionDesc(document.getId())
                .orElse(null);
        if (version != null) {
            MatchingService.Outcome outcome = matching.match(document, version,
                    document.getSenderContactId());
            proposals = outcome.proposals().size();
            autoLinked = outcome.autoLinked();
            threshold = BigDecimal.valueOf(outcome.threshold()).setScale(2, RoundingMode.HALF_UP)
                    .toPlainString();
            for (MatchingService.Proposal proposal : outcome.proposals()) {
                produced.add(new AgentResult.Produced("match-proposal",
                        proposal.targetId().toString(), proposal.rationale()));
            }
            if (autoLinked > 0) {
                produced.add(new AgentResult.Produced("case-link", document.getId().toString(),
                        autoLinked + " Vorgang/Vorgänge automatisch verknüpft"));
            }
        }

        meta.put("proposals", String.valueOf(proposals));
        meta.put("autoLinked", String.valueOf(autoLinked));
        meta.put("explicitLinked", String.valueOf(explicitLinked));
        meta.put("autoLinkThreshold", threshold);

        if (produced.isEmpty()) {
            return AgentResult.skipped(
                    "Keine Zuordnungen gefunden: weder Regeln noch caseId verbinden das Dokument mit "
                            + "einem Vorgang oder Kontakt.");
        }
        return AgentResult.done(
                (autoLinked > 0 ? autoLinked + " Vorgang/Vorgänge automatisch verknüpft, " : "")
                        + proposals + " Zuordnungs-Vorschlag/Vorschläge"
                        + (explicitLinked > 0 ? ", " + explicitLinked + " explizit verknüpft" : ""),
                produced, meta);
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
