package de.buerokratiecopilot.templates;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.responses.DraftValidation;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Whether a generated letter is ready to be sent (spec §15).
 *
 * <p><strong>Why this exists.</strong> {@code DraftValidation} could already say whether a prepared letter was
 * sendable — which requisites are missing, which statements nobody stands behind — and nothing called it. A
 * letter could be generated with unresolved placeholders and despatched, and the check that exists to prevent
 * exactly that was dead code with two tests.</p>
 *
 * <p>What it reads from the record: the values the template was filled with are the requisites, and anything
 * the platform could not resolve is a statement traceable to nothing — which is what
 * {@code FactSource.MODEL} means, and why such a statement is never treated as confirmed. The
 * {@link DraftValidation.Draft} is built here rather than stored, because the check must run against the
 * letter as it stands now, not as it stood when somebody wrote a row.</p>
 */
@Service
public class DraftReadinessService {

    private final JdbcTemplate jdbc;
    /**
     * The check itself is not a bean: it is built from the required-field list a document type declares, and
     * there is no such list here. The structural minimum is the honest default - it refuses a letter with no
     * subject, no body and unconfirmed statements, without inventing requisites for a type.
     */
    private final DraftValidation validation = DraftValidation.withStructuralMinimumOnly();

    public DraftReadinessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Checks a generated document and reports what stands in the way of sending it.
     *
     * <p>A document that was never rendered has nothing to check and says so, rather than being reported as
     * ready — "not produced yet" and "ready to send" are different states and only one of them is safe.</p>
     */
    @Transactional(readOnly = true)
    public DraftValidation.Result readiness(UUID tenantId, UUID generatedDocumentId) {
        var row = jdbc.query("""
                select title, coalesce(body_html, '') as body, status, field_values, unresolved_fields
                  from generated_documents
                 where id = ? and tenant_id = ?
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new Object[] {rs.getString("title"), rs.getString("body"), rs.getString("status"),
                    rs.getString("field_values"), rs.getString("unresolved_fields")};
        }, generatedDocumentId, tenantId);

        if (row == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Dieses Dokument gibt es nicht");
        }

        List<DraftValidation.Requisite> requisites = new ArrayList<>();
        for (Map.Entry<String, Object> entry : fields((String) row[3]).entrySet()) {
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            // A field the template wanted is a requisite; whether it is required is the template's decision
            // and is not recorded here, so it is reported as present or absent rather than as required.
            requisites.add(new DraftValidation.Requisite(entry.getKey(), entry.getKey(), value, false));
        }

        List<DraftValidation.Fact> facts = new ArrayList<>();
        for (String unresolved : unresolved((String) row[4])) {
            // Traceable to nothing is exactly what an unresolved placeholder is.
            facts.add(new DraftValidation.Fact("Offener Platzhalter: " + unresolved,
                    DraftValidation.FactSource.MODEL, null, false));
        }

        DraftValidation.Draft draft = new DraftValidation.Draft(null, (String) row[0],
                (String) row[1], requisites, facts);
        return validation.validate(draft);
    }

    private static Map<String, Object> fields(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new java.util.LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // An unreadable field map means the record is damaged; reporting "nothing filled in" would be a
            // claim the platform cannot support.
            throw new ApiException(ErrorCode.CONFLICT,
                    "Die Feldwerte dieses Dokuments sind nicht lesbar");
        }
        return Map.of();
    }

    private static List<String> unresolved(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
            if (parsed instanceof List<?> list) {
                List<String> result = new ArrayList<>();
                list.forEach(item -> result.add(String.valueOf(item)));
                return result;
            }
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Die offenen Felder dieses Dokuments sind nicht lesbar");
        }
        return List.of();
    }
}
