package de.buerokratiecopilot.agents.compliance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.legal.LegalService;
import de.buerokratiecopilot.settings.RuleSettingsService;

/**
 * {@code compliance-check} — Compliance-Prüfung.
 *
 * <p>Checks a document, a case or the workspace itself against requirements that are documented in
 * the platform: retention rules must exist, be legally grounded and confirmed by a person, closed
 * cases must carry an outcome, and the imprint must be complete (§ 5 DDG). A violation is reported,
 * never repaired: this agent is a measuring instrument, not an execution engine.</p>
 */
@Component
public class ComplianceCheckAgent implements Agent {

    /** One evaluated requirement. {@code status} is PASS, FAIL or INFO. */
    record Check(String code, String status, String message) {
    }

    private final LegalService legal;
    private final RuleSettingsService ruleSettings;
    private final ComplianceQueries queries;
    private final ObjectMapper json;

    public ComplianceCheckAgent(LegalService legal, RuleSettingsService ruleSettings,
                                ComplianceQueries queries, ObjectMapper json) {
        this.legal = legal;
        this.ruleSettings = ruleSettings;
        this.queries = queries;
        this.json = json;
    }

    @Override
    public String id() {
        return "compliance-check";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String documentIdRaw = context.input("documentId");
        if (documentIdRaw != null && !documentIdRaw.isBlank()) {
            UUID documentId = parseUuid(documentIdRaw);
            if (documentId == null) {
                return AgentResult.skipped("documentId ist keine gültige UUID");
            }
            Map<String, Object> document = queries.documentFacts(context.tenantId(), documentId);
            if (document == null) {
                return AgentResult.skipped("Dokument nicht gefunden");
            }
            return checkDocument(context, document,
                    queries.documentRetentionFacts(context.tenantId(), documentId));
        }
        String caseIdRaw = context.input("caseId");
        if (caseIdRaw != null && !caseIdRaw.isBlank()) {
            UUID caseId = parseUuid(caseIdRaw);
            if (caseId == null) {
                return AgentResult.skipped("caseId ist keine gültige UUID");
            }
            Map<String, Object> caseFacts = queries.caseFacts(context.tenantId(), caseId);
            if (caseFacts == null) {
                return AgentResult.skipped("Verfahren nicht gefunden");
            }
            return checkCase(context, caseFacts);
        }
        return checkWorkspace(context);
    }

    private AgentResult checkDocument(AgentContext context, Map<String, Object> document,
                                      Map<String, Object> retentionFacts) {
        if (document.get("deleted_at") != null) {
            return AgentResult.skipped("Dokument ist bereits gelöscht — keine Prüfung nötig");
        }
        UUID documentId = (UUID) document.get("id");
        List<Check> checks = new ArrayList<>();

        boolean hasPolicy = document.get("retention_policy_id") != null;
        checks.add(hasPolicy
                ? new Check("RETENTION_POLICY", "PASS", "Eine Aufbewahrungsregel ist zugeordnet")
                : new Check("RETENTION_POLICY", "FAIL", "Keine Aufbewahrungsregel zugeordnet — die Plattform erfindet keine Frist"));

        boolean policyVerified = Boolean.FALSE.equals(retentionFacts.get("requires_verification"));
        checks.add(!hasPolicy
                ? new Check("POLICY_VERIFIED", "SKIP", "Ohne Regel nichts zu bestätigen")
                : (policyVerified
                        ? new Check("POLICY_VERIFIED", "PASS", "Die Aufbewahrungsregel ist durch eine Person bestätigt")
                        : new Check("POLICY_VERIFIED", "FAIL", "Die Aufbewahrungsregel ist noch nicht bestätigt; eine unbestätigte Frist löscht nichts")));

        String legalBasis = retentionFacts.get("legal_basis") == null ? null
                : String.valueOf(retentionFacts.get("legal_basis"));
        checks.add(!hasPolicy
                ? new Check("POLICY_LEGAL_BASIS", "SKIP", "Ohne Regel keine Rechtsgrundlage")
                : ((legalBasis == null || legalBasis.isBlank())
                        ? new Check("POLICY_LEGAL_BASIS", "FAIL", "Der Regel fehlt die Rechtsgrundlage")
                        : new Check("POLICY_LEGAL_BASIS", "PASS", "Die Rechtsgrundlage ist dokumentiert")));

        String processingStatus = String.valueOf(document.get("processing_status"));
        String reviewStatus = String.valueOf(document.get("review_status"));
        checks.add("REVIEW_REQUIRED".equals(processingStatus) && "NOT_REQUIRED".equals(reviewStatus)
                ? new Check("REVIEW_CONSISTENCY", "FAIL",
                        "Widerspruch: das Dokument verlangt eine Prüfung, hat aber keinen Prüfstatus")
                : new Check("REVIEW_CONSISTENCY", "PASS", "Prüfstatus und Verarbeitungsstatus sind konsistent"));

        if (Boolean.TRUE.equals(document.get("legal_hold"))) {
            checks.add(new Check("LEGAL_HOLD", "INFO",
                    "Das Dokument steht unter Legal Hold — Löschungen sind gesperrt, bis eine Person den Hold aufhebt"));
        }
        else {
            checks.add(new Check("LEGAL_HOLD", "PASS", "Kein Legal Hold"));
        }
        if (document.get("archived_at") != null) {
            checks.add(new Check("ARCHIVED", "INFO", "Das Dokument ist archiviert"));
        }
        return finish(context, "document", documentId.toString(), checks);
    }

    private AgentResult checkCase(AgentContext context, Map<String, Object> caseFacts) {
        UUID caseId = (UUID) caseFacts.get("id");
        List<Check> checks = new ArrayList<>();

        Object outcome = caseFacts.get("outcome");
        if (caseFacts.get("closed_at") != null && (outcome == null || String.valueOf(outcome).isBlank())) {
            checks.add(new Check("CASE_CLOSE_OUTCOME", "FAIL",
                    "Das Verfahren ist geschlossen, aber ohne Ergebnis — der Abschluss braucht ein dokumentiertes Ergebnis"));
        }
        else {
            checks.add(new Check("CASE_CLOSE_OUTCOME", "PASS", "Der Abschluss trägt ein Ergebnis"));
        }
        if (caseFacts.get("counterparty_contact_id") == null) {
            checks.add(new Check("CASE_COUNTERPARTY", "INFO", "Das Verfahren hat keinen Gegenseiten-Kontakt zugeordnet"));
        }
        else {
            checks.add(new Check("CASE_COUNTERPARTY", "PASS", "Gegenseite ist zugeordnet"));
        }
        return finish(context, "case", caseId.toString(), checks);
    }

    private AgentResult checkWorkspace(AgentContext context) {
        List<Check> checks = new ArrayList<>();

        List<String> missing = legal.missing();
        if (missing.isEmpty()) {
            checks.add(new Check("WS_IMPRINT", "PASS", "Impressum ist vollständig (§ 5 DDG)"));
        }
        else {
            for (String field : missing) {
                checks.add(new Check("WS_IMPRINT", "FAIL",
                        "Impressumsangabe fehlt: " + imprintLabel(field) + " (§ 5 DDG)"));
            }
        }

        List<Map<String, Object>> unverified = queries.unverifiedPolicies(context.tenantId());
        if (unverified.isEmpty()) {
            checks.add(new Check("WS_RETENTION_VERIFIED", "PASS", "Alle Aufbewahrungsregeln sind bestätigt"));
        }
        else {
            for (Map<String, Object> policy : unverified) {
                checks.add(new Check("WS_RETENTION_VERIFIED", "FAIL",
                        "Aufbewahrungsregel „" + policy.get("code") + "“ ist noch nicht durch eine Person bestätigt"));
            }
        }

        Boolean radarEnabled = radarEnabled(context.tenantId());
        if (radarEnabled == null) {
            checks.add(new Check("WS_RADAR", "PASS", "Bürokratie-Radar ist aktiv (Standard)"));
        }
        else if (radarEnabled) {
            checks.add(new Check("WS_RADAR", "PASS", "Bürokratie-Radar ist aktiv"));
        }
        else {
            checks.add(new Check("WS_RADAR", "INFO",
                    "Bürokratie-Radar ist in den Einstellungen deaktiviert — Risiken werden nicht automatisch gemeldet"));
        }
        return finish(context, "workspace", context.tenantId().toString(), checks);
    }

    /** The effective {@code radar.enabled} value from the rule settings, or {@code null} when not configured. */
    private Boolean radarEnabled(UUID tenantId) {
        for (Map<String, Object> item : ruleSettings.list(tenantId)) {
            if ("radar.enabled".equals(item.get("key")) && item.get("value") instanceof Boolean enabled) {
                return enabled;
            }
        }
        return null;
    }

    private AgentResult finish(AgentContext context, String targetType, String targetRef, List<Check> checks) {
        List<Check> reported = checks.stream()
                .filter(check -> !"SKIP".equals(check.status()))
                .toList();
        long violations = reported.stream().filter(check -> "FAIL".equals(check.status())).count();
        long passed = reported.stream().filter(check -> "PASS".equals(check.status())).count();
        long notes = reported.stream().filter(check -> "INFO".equals(check.status())).count();

        for (Check check : reported) {
            if ("FAIL".equals(check.status())) {
                context.addProduced("complianceViolation", targetRef, check.code() + ": " + check.message());
            }
        }
        context.addProduced("complianceReport", targetRef,
                "Compliance-Prüfung für " + targetType + ": " + violations + " verletzt, " + passed + " erfüllt, "
                        + notes + " Hinweise");

        String summary = violations == 0
                ? "Compliance-Prüfung für " + targetType + ": alle " + passed + " Anforderungen erfüllt"
                        + (notes > 0 ? ", " + notes + " Hinweise" : "")
                : "Compliance-Prüfung für " + targetType + ": " + violations + " Anforderungen verletzt, " + passed
                        + " erfüllt" + (notes > 0 ? ", " + notes + " Hinweise" : "");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("targetType", targetType);
        meta.put("targetRef", targetRef);
        meta.put("checked", String.valueOf(reported.size()));
        meta.put("passed", String.valueOf(passed));
        meta.put("violations", String.valueOf(violations));
        meta.put("notes", String.valueOf(notes));
        try {
            meta.put("reportJson", json.writeValueAsString(reported.stream()
                    .map(check -> Map.of("code", check.code(), "status", check.status(), "message", check.message()))
                    .toList()));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            meta.put("reportJson", "[]");
        }
        return AgentResult.done(summary, context.produced(), meta);
    }

    private static String imprintLabel(String field) {
        return switch (field) {
            case "name" -> "Name des Anbieters";
            case "address" -> "Anschrift";
            case "email" -> "E-Mail-Adresse";
            case "phone" -> "zweiter schneller Kontaktweg (Telefon)";
            case "disputeResolution" -> "Hinweis zur Streitbeilegung";
            default -> field;
        };
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
