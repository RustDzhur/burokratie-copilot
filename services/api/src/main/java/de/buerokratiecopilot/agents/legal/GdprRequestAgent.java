package de.buerokratiecopilot.agents.legal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.privacy.DataSubjectService;

/**
 * {@code legal-gdpr-request} — DSGVO-Anfrage.
 *
 * <p>Access and export are executed because the privacy module can produce them from records it
 * already holds; both are journaled in {@code data_subject_requests}. Rectification and erasure are
 * deliberately <strong>not</strong> executed: the run prepares a plan and names what would change,
 * so a person decides. Erasure is refused outright while a linked document is under legal hold.</p>
 *
 * <p>The agent works through {@link DataSubjectService}, which enforces the contact's tenant and
 * writes the audit entry. It never touches contact rows itself.</p>
 */
@Component
public class GdprRequestAgent implements Agent {

    /** The request kinds the privacy module knows. */
    public enum Kind {
        ACCESS, EXPORT, RECTIFICATION, ERASURE
    }

    private static final Map<String, String> COUNT_LABELS = Map.of(
            "identifiers", "Kennungen",
            "documents", "Dokumente",
            "documentsUnderLegalHold", "Dokumente unter Legal Hold",
            "contracts", "Verträge",
            "invoices", "Rechnungen",
            "cases", "Vorgänge",
            "mailMessages", "E-Mails");

    private final DataSubjectService dataSubjects;

    public GdprRequestAgent(DataSubjectService dataSubjects) {
        this.dataSubjects = dataSubjects;
    }

    @Override
    public String id() {
        return "legal-gdpr-request";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String rawContact = context.input("contactId");
        if (rawContact == null || rawContact.isBlank()) {
            return AgentResult.skipped("Ohne Kontaktbezug lässt sich keine DSGVO-Anfrage bearbeiten.");
        }
        UUID contactId;
        try {
            contactId = UUID.fromString(rawContact.strip());
        } catch (IllegalArgumentException e) {
            return AgentResult.failed("Die Kontakt-ID ist keine gültige UUID: " + rawContact);
        }
        Kind kind = parseKind(context.input("kind"));
        if (kind == null) {
            return AgentResult.failed("Unbekannte Anfrageart. Zulässig sind ACCESS, EXPORT, "
                    + "RECTIFICATION und ERASURE.");
        }
        return switch (kind) {
            case ACCESS -> access(context, contactId);
            case EXPORT -> export(context, contactId);
            case RECTIFICATION -> rectificationPlan(context, contactId);
            case ERASURE -> erasurePlan(context, contactId);
        };
    }

    /** Parses the requested kind; a blank value means access, an unknown one is rejected. */
    static Kind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return Kind.ACCESS;
        }
        try {
            return Kind.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private AgentResult access(AgentContext context, UUID contactId) {
        Map<String, Object> counts = dataSubjects.overview(context.tenantId(),
                context.actorUserId(), contactId);
        context.addProduced("gdpr-access", contactId.toString(),
                "Auskunft erteilt: " + describe(counts));
        return AgentResult.done("Auskunft zur Person erstellt und im Anfragejournal dokumentiert ("
                        + describe(counts) + ").",
                context.produced(), meta("ACCESS", contactId, counts));
    }

    private AgentResult export(AgentContext context, UUID contactId) {
        Map<String, Object> bundle = dataSubjects.export(context.tenantId(),
                context.actorUserId(), contactId);
        Map<String, Object> sizes = new LinkedHashMap<>();
        for (String key : List.of("identifiers", "documents", "contracts", "invoices", "cases")) {
            sizes.put(key, size(bundle.get(key)));
        }
        context.addProduced("gdpr-export", contactId.toString(),
                "Auskunftskopie erstellt: " + describe(sizes));
        return AgentResult.done("Auskunftskopie erstellt und im Anfragejournal dokumentiert ("
                        + describe(sizes) + ").",
                context.produced(), meta("EXPORT", contactId, sizes));
    }

    private AgentResult rectificationPlan(AgentContext context, UUID contactId) {
        String rawFields = context.input("fields");
        if (rawFields == null || rawFields.isBlank()) {
            return AgentResult.skipped("Für eine Berichtigung müssen die zu ändernden Felder "
                    + "benannt sein; es wurde nichts geändert.");
        }
        List<String> fields = new ArrayList<>();
        for (String field : rawFields.split(",")) {
            String candidate = field.strip();
            if (candidate.isEmpty()) {
                continue;
            }
            if (!DataSubjectService.CORRECTABLE.contains(candidate)) {
                return AgentResult.failed("Dieses Feld kann nicht berichtigt werden: " + candidate);
            }
            fields.add(candidate);
        }
        if (fields.isEmpty()) {
            return AgentResult.skipped("Es wurden keine berichtigungsfähigen Felder angegeben.");
        }
        Map<String, Object> counts = dataSubjects.inventory(context.tenantId(), contactId);
        context.addProduced("gdpr-plan", contactId.toString(),
                "Berichtigungsplan für " + String.join(", ", fields));
        Map<String, String> meta = meta("RECTIFICATION", contactId, counts);
        meta.put("fields", String.join(",", fields));
        meta.put("requiresApproval", "true");
        return AgentResult.done("Berichtigungsplan für " + String.join(", ", fields)
                        + " vorbereitet; die Änderung wird erst nach Freigabe ausgeführt.",
                context.produced(), meta);
    }

    private AgentResult erasurePlan(AgentContext context, UUID contactId) {
        String reason = context.input("reason");
        if (reason == null || reason.isBlank()) {
            return AgentResult.skipped("Ohne Begründung wird nichts gelöscht; es wurde kein Plan "
                    + "erstellt.");
        }
        Map<String, Object> counts = dataSubjects.inventory(context.tenantId(), contactId);
        int held = count(counts, "documentsUnderLegalHold");
        if (held > 0) {
            context.addProduced("gdpr-blocked", contactId.toString(),
                    "Löschung gesperrt: " + held + " Dokument(e) unter Legal Hold");
            Map<String, String> meta = meta("ERASURE", contactId, counts);
            meta.put("blocked", "true");
            return AgentResult.skipped("Die Löschung ist gesperrt: mindestens ein verknüpftes "
                    + "Dokument steht unter Legal Hold. Der Vorgang wurde nur dokumentiert.");
        }
        context.addProduced("gdpr-plan", contactId.toString(),
                "Löschplan: Stammdaten und Kennungen, Geschäftsunterlagen bleiben aufbewahrt");
        Map<String, String> meta = meta("ERASURE", contactId, counts);
        meta.put("requiresApproval", "true");
        meta.put("retained", String.valueOf(count(counts, "documents") + count(counts, "contracts")
                + count(counts, "invoices") + count(counts, "cases")));
        return AgentResult.done("Löschplan erstellt: Stammdaten und Kennungen können entfernt "
                        + "werden, Geschäftsunterlagen bleiben nach den Aufbewahrungsregeln bestehen. "
                        + "Die Ausführung ist freigabepflichtig.",
                context.produced(), meta);
    }

    private static Map<String, String> meta(String kind, UUID contactId, Map<String, Object> counts) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("kind", kind);
        meta.put("contactId", contactId.toString());
        for (String key : COUNT_LABELS.keySet()) {
            meta.put(key, String.valueOf(count(counts, key)));
        }
        return meta;
    }

    static String describe(Map<String, Object> counts) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> label : COUNT_LABELS.entrySet()) {
            int value = count(counts, label.getKey());
            if (value > 0) {
                parts.add(label.getValue() + ": " + value);
            }
        }
        return parts.isEmpty() ? "keine verknüpften Daten" : String.join(", ", parts);
    }

    private static int count(Map<String, Object> counts, String key) {
        Object value = counts.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static int size(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }
}
