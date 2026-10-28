package de.buerokratiecopilot.documents.classification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The fields a document type reads the deadline it states from (spec §9.3, §13.2).
 *
 * <p>The values come from the type's scenario ({@code document_type_scenarios.due_field_keys}), which is the same
 * configuration the orchestrator later uses to turn the date on the paper into a deadline. Asking it here keeps
 * the two answers from drifting apart: the classifier and the deadline stage agree on where the date is.</p>
 *
 * <p><strong>Why the classifier needs it.</strong> Two types that are almost equally well supported are only a
 * reason to ask a person when the answer changes the work. The promise the product makes for a letter with a date
 * on it is a deadline; two types that read that deadline from the same fields put the same date in the same place,
 * whatever label the drawer gets. Two types that read it from different fields do not — a Mahnung states a
 * {@code Frist}, a Zahlungserinnerung a {@code Frist} and a {@code Zahlungsziel} — and then a person has to decide
 * which of them the letter is.</p>
 */
@Component
class StatedDeadlineFields {

    private final JdbcTemplate jdbc;

    StatedDeadlineFields(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The deadline fields of one document type: those of its own tenant when the tenant configured some, otherwise
     * the shared ones. Empty when the type has no scenario or no deadline field — which is a fact, not an error.
     */
    Set<String> of(UUID tenantId, String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            return Set.of();
        }
        List<String> configured = jdbc.query("""
                select due_field_keys from document_type_scenarios
                 where document_type_code = ?
                   and (tenant_id is null or tenant_id = ?)
                   and due_field_keys is not null
                   and btrim(due_field_keys) <> ''
                 order by tenant_id nulls last
                 limit 1
                """, (rs, row) -> rs.getString(1), typeCode, tenantId);
        if (configured.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String key : configured.get(0).split(",")) {
            String cleaned = key.strip();
            if (!cleaned.isEmpty()) {
                keys.add(cleaned.toLowerCase(Locale.GERMAN));
            }
        }
        return keys;
    }
}
