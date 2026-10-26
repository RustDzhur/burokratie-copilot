package de.buerokratiecopilot.agents.crm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.contacts.identifiers.ContactIdentifierService;
import de.buerokratiecopilot.contacts.identifiers.IdentifierKind;

/**
 * {@code crm-contact} — Kontaktpflege.
 *
 * <p>Enriches a contact with data extracted from correspondence and documents, without
 * creating duplicates. Empty columns are filled; columns that already carry a value are left
 * alone unless the run explicitly says {@code overwrite}. E-mail and phone additionally become
 * identifiers of the contact, where the identifiers module itself refuses the value when it
 * already belongs to somebody else — that clash is reported, not fatal. Facts (key=value pairs)
 * and notes land in the contact metadata under {@code enrichment}, where a repeated run merges
 * into the same map instead of piling up copies.</p>
 */
@Component
public class CrmContactAgent implements Agent {

    private final CrmStore store;
    private final ContactIdentifierService identifiers;

    public CrmContactAgent(CrmStore store, ContactIdentifierService identifiers) {
        this.store = store;
        this.identifiers = identifiers;
    }

    @Override
    public String id() {
        return "crm-contact";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID tenantId = context.tenantId();
        UUID actor = context.actorUserId();
        boolean overwrite = parseBool(context.input("overwrite"), false);

        String contactIdRaw = clean(context.input("contactId"));
        UUID contactId = null;
        if (contactIdRaw != null) {
            try {
                contactId = UUID.fromString(contactIdRaw);
            }
            catch (IllegalArgumentException e) {
                return AgentResult.failed("Kontaktpflege: ungültige contactId: " + contactIdRaw);
            }
        }
        if (contactId == null) {
            String email = clean(context.input("email"));
            contactId = email == null ? null
                    : store.findContactByEmail(tenantId, email).orElse(null);
        }
        if (contactId == null) {
            return AgentResult.skipped("Kontaktpflege: kein Kontakt gefunden — weder contactId "
                    + "noch eine bekannte E-Mail.");
        }

        Optional<Map<String, Object>> rowOpt = store.findContactRow(tenantId, contactId);
        if (rowOpt.isEmpty()) {
            return AgentResult.skipped("Kontaktpflege: Kontakt " + contactId
                    + " existiert nicht oder wurde gelöscht.");
        }
        Map<String, Object> row = rowOpt.get();

        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        fillColumn(tenantId, contactId, row, "first_name", context.input("firstName"),
                overwrite, updated, skipped);
        fillColumn(tenantId, contactId, row, "last_name", context.input("lastName"),
                overwrite, updated, skipped);
        fillColumn(tenantId, contactId, row, "organization_name", context.input("organizationName"),
                overwrite, updated, skipped);
        fillColumn(tenantId, contactId, row, "mobile", context.input("mobile"),
                overwrite, updated, skipped);
        fillColumn(tenantId, contactId, row, "website", context.input("website"),
                overwrite, updated, skipped);
        fillColumn(tenantId, contactId, row, "preferred_language", context.input("language"),
                overwrite, updated, skipped);

        String phone = clean(context.input("phone"));
        if (phone != null && fillColumn(tenantId, contactId, row, "phone", phone, overwrite,
                updated, skipped)) {
            registerIdentifier(tenantId, contactId, IdentifierKind.PHONE, phone, actor,
                    updated, skipped);
        }
        String email = clean(context.input("email"));
        String existingEmail = stringValue(row.get("email"));
        if (email != null && !email.equalsIgnoreCase(existingEmail)) {
            // Die Primäradresse wird nie überschrieben; eine neue Adresse wird als Kennung
            // registriert, wo das Identifikatoren-Modul Dubletten ablehnt.
            registerIdentifier(tenantId, contactId, IdentifierKind.EMAIL, email, actor,
                    updated, skipped);
        }

        if (parseBool(context.input("customer"), false)
                && !Boolean.TRUE.equals(row.get("is_customer"))) {
            store.markCustomer(tenantId, contactId);
            row.put("is_customer", true);
            updated.add("is_customer");
        }

        Map<String, Object> enrichment = new LinkedHashMap<>(asMap(metadataOf(row).get("enrichment")));
        Map<String, Object> facts = new LinkedHashMap<>(asMap(enrichment.get("facts")));
        int factsAdded = 0;
        for (Map.Entry<String, String> fact : parseFacts(context.input("facts")).entrySet()) {
            Object existing = facts.get(fact.getKey());
            if (existing == null) {
                facts.put(fact.getKey(), fact.getValue());
                factsAdded++;
            }
            else if (!String.valueOf(existing).equals(fact.getValue())) {
                if (overwrite) {
                    facts.put(fact.getKey(), fact.getValue());
                    factsAdded++;
                }
                else {
                    skipped.add("fact:" + fact.getKey() + " (weicht ab, kein overwrite)");
                }
            }
            // gleicher Wert: nichts zu tun
        }
        if (factsAdded > 0) {
            enrichment.put("facts", facts);
        }

        String note = clean(context.input("note"));
        if (note != null) {
            List<Object> notes = new ArrayList<>(asList(enrichment.get("notes")));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("at", context.now().toString());
            entry.put("text", note);
            entry.put("agent", "crm-contact");
            notes.add(entry);
            enrichment.put("notes", notes);
        }

        if (!enrichment.isEmpty()) {
            enrichment.put("lastUpdated", context.now().toString());
            store.mergeContactMetadata(tenantId, contactId, Map.of("enrichment", enrichment));
            updated.add("enrichment");
        }

        if (updated.isEmpty()) {
            String reason = skipped.isEmpty() ? "keine neuen Daten übergeben"
                    : "alle Felder bereits gefüllt oder übersprungen";
            return AgentResult.skipped("Kontaktpflege: nichts zu ergänzen — " + reason + ".");
        }

        context.addProduced("contact", contactId.toString(), "Kontakt ergänzt");
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("contactId", contactId.toString());
        meta.put("updated", String.join(",", updated));
        meta.put("skipped", String.join(",", skipped));
        return AgentResult.done("Kontakt ergänzt (" + String.join(", ", updated) + ").",
                context.produced(), meta);
    }

    /** Fills a column only when it is empty, unless the run explicitly overwrites. */
    private boolean fillColumn(UUID tenantId, UUID contactId, Map<String, Object> row, String column,
            String raw, boolean overwrite, List<String> updated, List<String> skipped) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.strip();
        Object current = row.get(column);
        if (current != null && !String.valueOf(current).isBlank() && !overwrite) {
            skipped.add(column + " (bereits gefüllt)");
            return false;
        }
        store.setContactColumn(tenantId, contactId, column, value);
        row.put(column, value);
        updated.add(column);
        return true;
    }

    /** Registers a new identifier; a clash with another contact is reported, not fatal. */
    private boolean registerIdentifier(UUID tenantId, UUID contactId, IdentifierKind kind, String raw,
            UUID actor, List<String> updated, List<String> skipped) {
        try {
            identifiers.add(tenantId, contactId, kind, raw, actor);
            updated.add("identifier:" + kind);
            return true;
        }
        catch (RuntimeException e) {
            skipped.add("identifier:" + kind + " (" + e.getMessage() + ")");
            return false;
        }
    }

    /** Facts arrive as {@code key=value} pairs separated by {@code ;} or newlines. */
    private static Map<String, String> parseFacts(String raw) {
        Map<String, String> facts = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return facts;
        }
        for (String part : raw.split("[;\n]")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).strip();
            String value = trimmed.substring(eq + 1).strip();
            if (!key.isEmpty() && !value.isEmpty()) {
                facts.put(key, value);
            }
        }
        return facts;
    }

    private static boolean parseBool(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.strip().toLowerCase(java.util.Locale.ROOT);
        return "true".equals(value) || "1".equals(value) || "ja".equals(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> metadataOf(Map<String, Object> row) {
        Object metadata = row.get("metadata");
        return metadata instanceof Map ? (Map<String, Object>) metadata : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
