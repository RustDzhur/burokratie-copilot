package de.buerokratiecopilot.agents.compliance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.privacy.DataSubjectService;
import de.buerokratiecopilot.retention.RetentionService;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * {@code gdpr-guard} — DSGVO-Schutz.
 *
 * <p>Finds the personal data held about a contact (through the privacy module), evaluates whether
 * linked documents may already be deleted under their retention rules (through the retention
 * module) and writes a deletion plan. <strong>The plan is the end of this agent's authority:</strong>
 * erasing a contact or deleting a document stays a separate act by a person through the privacy /
 * deletion / retention modules, and a document under legal hold is always reported as blocked.</p>
 */
@Component
public class GdprGuardAgent implements Agent {

    private final DataSubjectService dataSubjects;
    private final RetentionService retention;
    private final ComplianceQueries queries;

    public GdprGuardAgent(DataSubjectService dataSubjects, RetentionService retention,
                          ComplianceQueries queries) {
        this.dataSubjects = dataSubjects;
        this.retention = retention;
        this.queries = queries;
    }

    @Override
    public String id() {
        return "gdpr-guard";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String documentIdRaw = context.input("documentId");
        if (documentIdRaw != null && !documentIdRaw.isBlank()) {
            UUID documentId = parseUuid(documentIdRaw);
            if (documentId == null) {
                return AgentResult.skipped("documentId ist keine gültige UUID");
            }
            return assessDocument(context, documentId);
        }
        String contactIdRaw = context.input("contactId");
        if (contactIdRaw != null && !contactIdRaw.isBlank()) {
            UUID contactId = parseUuid(contactIdRaw);
            if (contactId == null) {
                return AgentResult.skipped("contactId ist keine gültige UUID");
            }
            return assessContact(context, contactId);
        }
        String query = context.input("query");
        if (query != null && !query.isBlank()) {
            return search(context, query);
        }
        return AgentResult.skipped(
                "Keine Eingabe: contactId, documentId oder query angeben, damit etwas zu prüfen ist");
    }

    /** Whether one document may be deleted under its retention rules — an assessment, never a deletion. */
    private AgentResult assessDocument(AgentContext context, UUID documentId) {
        Map<String, Object> facts = queries.documentRetentionFacts(context.tenantId(), documentId);
        if (facts == null) {
            return AgentResult.skipped("Dokument nicht gefunden");
        }

        String assessment;
        String detail;
        if (facts.get("deleted_at") != null) {
            assessment = "ALREADY_DELETED";
            detail = "Das Dokument ist bereits gelöscht";
        }
        else if (Boolean.TRUE.equals(facts.get("legal_hold"))) {
            assessment = "LEGAL_HOLD";
            detail = "Legal Hold sperrt die Löschung; zuerst muss eine Person den Hold aufheben";
        }
        else if (facts.get("retention_policy_id") == null) {
            assessment = "NO_POLICY";
            detail = "Keine Aufbewahrungsregel zugeordnet — ohne Regel wird nichts gelöscht";
        }
        else if (Boolean.TRUE.equals(facts.get("requires_verification"))) {
            assessment = "POLICY_NOT_VERIFIED";
            detail = "Die Aufbewahrungsregel ist nicht bestätigt — eine unbestätigte Frist löscht nichts";
        }
        else if (dueIds(context.tenantId()).contains(documentId)) {
            assessment = "DELETE_CANDIDATE";
            detail = "Die Aufbewahrungsfrist ist abgelaufen; die Löschung braucht die Bestätigung einer Person";
        }
        else {
            assessment = "NOT_YET_DUE";
            detail = "Die Aufbewahrungsfrist läuft noch";
        }

        context.addProduced("deletionAssessment", documentId.toString(),
                (String) facts.get("original_filename") + ": " + assessment);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("assessment", assessment);
        meta.put("documentId", documentId.toString());
        meta.put("detail", detail);
        meta.put("deletionExecuted", "false");
        return AgentResult.done("Löschreife bewertet: " + assessment + " — " + detail,
                context.produced(), meta);
    }

    /** The personal data held about a contact, with a deletion plan for the linked documents. */
    private AgentResult assessContact(AgentContext context, UUID contactId) {
        if (!queries.contactExists(context.tenantId(), contactId)) {
            return AgentResult.skipped("Kontakt nicht gefunden");
        }
        Map<String, Object> counts = queries.contactCounts(context.tenantId(), contactId);
        List<Map<String, Object>> documents = queries.contactDocumentRetention(context.tenantId(), contactId);
        Set<UUID> dueIds = dueIds(context.tenantId());

        int candidates = 0;
        int blocked = 0;
        List<String> candidateIds = new ArrayList<>();
        for (Map<String, Object> document : documents) {
            UUID documentId = (UUID) document.get("id");
            if (Boolean.TRUE.equals(document.get("legal_hold"))) {
                blocked++;
                context.addProduced("deletionBlocked", documentId.toString(),
                        (String) document.get("original_filename") + ": Legal Hold");
            }
            else if (dueIds.contains(documentId)) {
                candidates++;
                candidateIds.add(documentId.toString());
                context.addProduced("deletionCandidate", documentId.toString(),
                        (String) document.get("original_filename") + ": Frist abgelaufen (Regel "
                                + document.get("policy_code") + ")");
            }
        }

        long kindsWithData = counts.values().stream()
                .filter(value -> ((Number) value).intValue() > 0)
                .count();
        context.addProduced("dataInventory", contactId.toString(),
                "DSGVO-Bestand: " + kindsWithData + " Kategorien, " + documents.size() + " Dokumente");

        Map<String, String> meta = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : counts.entrySet()) {
            meta.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        meta.put("linkedDocuments", String.valueOf(documents.size()));
        meta.put("deletionCandidates", String.valueOf(candidates));
        meta.put("deletionBlocked", String.valueOf(blocked));
        meta.put("candidateIds", String.join(",", candidateIds));
        meta.put("deletionExecuted", "false");

        String summary = candidates > 0
                ? "DSGVO-Prüfung: " + candidates + " Dokumente sind löschreif und " + blocked
                        + " durch Legal Hold gesperrt — Löschungen nur nach Bestätigung einer Person"
                : "DSGVO-Prüfung: keine löschreifen Dokumente"
                        + (blocked > 0 ? ", " + blocked + " durch Legal Hold gesperrt" : "");
        return AgentResult.done(summary, context.produced(), meta);
    }

    /** Finds contacts by a search term through the privacy module (read-only). */
    private AgentResult search(AgentContext context, String query) {
        List<Map<String, Object>> results;
        try {
            results = dataSubjects.search(context.tenantId(), query);
        }
        catch (ApiException ex) {
            return AgentResult.skipped(ex.getMessage());
        }
        for (Map<String, Object> contact : results) {
            context.addProduced("personalDataHit", String.valueOf(contact.get("id")),
                    String.valueOf(contact.get("display_name")));
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("hits", String.valueOf(results.size()));
        meta.put("query", query.strip());
        return AgentResult.done("Suche nach personenbezogenen Daten: " + results.size() + " Treffer für „"
                + query.strip() + "“", context.produced(), meta);
    }

    /** The documents whose retention period has expired under a verified policy (retention module). */
    private Set<UUID> dueIds(UUID tenantId) {
        return retention.dueForDeletion(tenantId).stream()
                .map(row -> (UUID) row.get("id"))
                .collect(Collectors.toSet());
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
