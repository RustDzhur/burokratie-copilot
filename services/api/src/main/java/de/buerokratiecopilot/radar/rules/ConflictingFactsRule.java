package de.buerokratiecopilot.radar.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.radar.RadarFindingService.Draft;
import de.buerokratiecopilot.radar.RadarFindingService.Evidence;
import de.buerokratiecopilot.radar.RadarRule;
import de.buerokratiecopilot.radar.RadarSeverity;

/**
 * E1: two documents of the same kind that carry the same reference state different dates or amounts.
 *
 * <p>Only documents of one type are compared: a reminder legitimately names another deadline and an amount with fees than the
 * invoice it refers to, but two invoices, two price notices or two notices of one file number should agree. A differing date is
 * rated higher than a differing amount, because a deadline that is wrong in one of the two documents can be missed. Invoice
 * amounts under one number are left to rule B2, which looks at the booked invoices. The rule states the two values and where
 * they stand; which one is right it does not decide.</p>
 */
@Component
public class ConflictingFactsRule implements RadarRule {

    static final int VERSION = 1;
    private static final Set<String> REFERENCES = Set.of("Rechnungsnummer", "Aktenzeichen");
    private static final Set<String> DATES = Set.of("Frist", "Zahlungsziel");
    private static final Set<String> AMOUNTS = Set.of("Betrag", "Brutto", "OffenerBetrag");

    private final JdbcTemplate jdbc;

    public ConflictingFactsRule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Description describe() {
        return new Description("E1", VERSION, "CONFLICTING_FACTS",
                "Aktenzeichen oder Rechnungsnummer, Fälligkeit/Frist und Betrag aus zwei Dokumenten derselben Art",
                "Zwei Dokumente derselben Art tragen dasselbe Aktenzeichen oder dieselbe Rechnungsnummer und nennen verschiedene Daten oder Beträge",
                "HIGH bei abweichender Frist oder Fälligkeit, MEDIUM bei abweichendem Betrag",
                "Zwei Dokumente zur selben Angelegenheit widersprechen sich. Welches stimmt, entscheidet die Regel nicht; beide Stellen werden gezeigt.",
                List.of("Belege öffnen", "Bestätigen", "Ablehnen", "Zurückstellen", "Verantwortlichen zuweisen"),
                "Eine Person bestätigt den Widerspruch oder lehnt den Befund ab",
                "Eines der Dokumente wird mit anderem Wert erneut gelesen oder korrigiert");
    }

    @Override
    public List<Draft> onDocument(UUID tenantId, UUID documentId) {
        List<Draft> drafts = new ArrayList<>();
        List<Map<String, Object>> mine = jdbc.queryForList("""
                select e.field_key, e.normalized_value, e.source_snippet, d.document_type_id
                from document_extractions e join documents d on d.id = e.document_id
                where e.document_id = ? and d.tenant_id = ? and d.deleted_at is null and d.document_type_id is not null
                  and e.normalized_value is not null and e.normalized_value <> ''
                """, documentId, tenantId);
        for (Map<String, Object> reference : mine) {
            if (!REFERENCES.contains((String) reference.get("field_key"))) {
                continue;
            }
            for (Map<String, Object> other : jdbc.queryForList("""
                    select d.id from documents d join document_extractions e on e.document_id = d.id
                    where d.tenant_id = ? and d.id <> ? and d.deleted_at is null and d.document_type_id = ?
                      and e.field_key = ? and upper(trim(e.normalized_value)) = upper(trim(?))
                    """, tenantId, documentId, reference.get("document_type_id"), reference.get("field_key"),
                    reference.get("normalized_value"))) {
                UUID otherId = (UUID) other.get("id");
                for (Map<String, Object> fact : mine) {
                    String key = (String) fact.get("field_key");
                    boolean date = DATES.contains(key);
                    boolean amount = AMOUNTS.contains(key);
                    if (!date && !amount) {
                        continue;
                    }
                    List<Map<String, Object>> theirs = jdbc.queryForList("""
                            select normalized_value, source_snippet from document_extractions
                            where document_id = ? and field_key = ? and normalized_value is not null and normalized_value <> ''
                            """, otherId, key);
                    for (Map<String, Object> their : theirs) {
                        String a = String.valueOf(fact.get("normalized_value"));
                        String b = String.valueOf(their.get("normalized_value"));
                        if (a.equals(b) || (amount && sameNumber(a, b))) {
                            continue;
                        }
                        String first = documentId.compareTo(otherId) < 0 ? documentId.toString() : otherId.toString();
                        String second = documentId.compareTo(otherId) < 0 ? otherId.toString() : documentId.toString();
                        String label = date ? "Frist/Fälligkeit" : "Betrag";
                        drafts.add(new Draft("E1:" + first + ":" + second + ":" + key, "CONFLICTING_FACTS", "E1", VERSION,
                                "Widerspruch: " + label + " zu " + reference.get("normalized_value"),
                                "Zwei Dokumente zu " + reference.get("normalized_value") + " nennen verschiedene Werte für " + key
                                        + ": " + a + " und " + b + ".",
                                date ? RadarSeverity.HIGH : RadarSeverity.MEDIUM, 1.0, null, null, null,
                                List.of(date ? "DIFFERENT_DATE" : "DIFFERENT_AMOUNT", "SAME_REFERENCE"),
                                List.of(Map.of("action", "COMPARE_DOCUMENTS", "label", "Beide Dokumente vergleichen")),
                                List.of(new Evidence("PRIMARY", documentId, null, snippet(fact, key, a), null, null),
                                        new Evidence("COMPARISON", otherId, null, snippet(their, key, b), null, null)),
                                RadarRule.fingerprint(first, second, key, a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a)));
                    }
                }
            }
        }
        return drafts;
    }

    private static boolean sameNumber(String a, String b) {
        try {
            return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)) == 0;
        }
        catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String snippet(Map<String, Object> row, String key, String value) {
        Object text = row.get("source_snippet");
        return text == null || String.valueOf(text).isBlank() ? key + " " + value : String.valueOf(text);
    }
}
