package de.buerokratiecopilot.radar.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.radar.RadarFindingService.Draft;
import de.buerokratiecopilot.radar.RadarFindingService.Evidence;
import de.buerokratiecopilot.radar.RadarRule;
import de.buerokratiecopilot.radar.RadarSeverity;

/**
 * B2: two different documents carry the same invoice number of the same sender.
 *
 * <p>Identical files are already recognised on upload (they are copies, not new documents). This rule finds what that check
 * cannot: a second scan or a re-sent invoice with the same number. With an equal amount it is rated higher than with a
 * different one, because a different amount under one number may be a correction.</p>
 */
@Component
public class InvoiceDuplicateRule implements RadarRule {

    static final int VERSION = 1;

    private final JdbcTemplate jdbc;

    public InvoiceDuplicateRule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Description describe() {
        return new Description("B2", VERSION, "INVOICE_ANOMALY",
                "Rechnungsnummer, Absender (Kontakt) und Betrag zweier verschiedener Dokumente",
                "Eine Rechnung wurde verarbeitet und ein anderes Dokument desselben Absenders trägt dieselbe Rechnungsnummer",
                "HIGH bei gleichem Betrag, MEDIUM bei abweichendem Betrag",
                "Dieselbe Rechnungsnummer desselben Absenders kommt in zwei Dokumenten vor. Es kann eine doppelt gesendete oder doppelt gescannte Rechnung sein, bei abweichendem Betrag auch eine Korrektur.",
                List.of("Belege öffnen", "Bestätigen", "Ablehnen", "Zurückstellen", "Verantwortlichen zuweisen"),
                "Eine Person bestätigt das Duplikat oder lehnt den Befund ab",
                "Eines der Dokumente wird mit anderem Betrag oder anderer Nummer erneut gelesen");
    }

    @Override
    public List<Draft> onDocument(UUID tenantId, UUID documentId) {
        List<Draft> drafts = new ArrayList<>();
        for (Map<String, Object> mine : jdbc.queryForList("""
                select id, contact_id, invoice_number, gross_amount, currency from invoices
                where tenant_id = ? and document_id = ? and direction = 'INCOMING' and deleted_at is null
                  and contact_id is not null and invoice_number is not null and trim(invoice_number) <> ''
                """, tenantId, documentId)) {
            for (Map<String, Object> other : jdbc.queryForList("""
                    select id, document_id, gross_amount, currency from invoices
                    where tenant_id = ? and contact_id = ? and id <> ? and direction = 'INCOMING' and deleted_at is null
                      and upper(trim(invoice_number)) = upper(trim(?)) and document_id is not null and document_id <> ?
                    """, tenantId, mine.get("contact_id"), mine.get("id"), mine.get("invoice_number"), documentId)) {
                UUID a = (UUID) mine.get("id");
                UUID b = (UUID) other.get("id");
                String first = a.compareTo(b) < 0 ? a.toString() : b.toString();
                String second = a.compareTo(b) < 0 ? b.toString() : a.toString();
                boolean sameAmount = mine.get("gross_amount") != null && other.get("gross_amount") != null
                        && ((java.math.BigDecimal) mine.get("gross_amount")).compareTo((java.math.BigDecimal) other.get("gross_amount")) == 0
                        && String.valueOf(mine.get("currency")).equals(String.valueOf(other.get("currency")));
                List<String> reasons = sameAmount
                        ? List.of("SAME_INVOICE_NUMBER", "SAME_AMOUNT") : List.of("SAME_INVOICE_NUMBER", "DIFFERENT_AMOUNT");
                drafts.add(new Draft("B2:" + first + ":" + second, "INVOICE_ANOMALY", "B2", VERSION,
                        "Mögliches Duplikat: Rechnungsnummer " + mine.get("invoice_number"),
                        "Zwei Dokumente desselben Absenders tragen die Rechnungsnummer " + mine.get("invoice_number")
                                + (sameAmount ? " und denselben Betrag." : " mit abweichendem Betrag."),
                        sameAmount ? RadarSeverity.HIGH : RadarSeverity.MEDIUM, 1.0, null, (UUID) mine.get("contact_id"), null,
                        reasons, List.of(Map.of("action", "REVIEW_DUPLICATE", "label", "Beide Dokumente vergleichen")),
                        List.of(new Evidence("PRIMARY", documentId, null, "Rechnungsnummer " + mine.get("invoice_number"), null, null),
                                new Evidence("COMPARISON", (UUID) other.get("document_id"), null,
                                        "Rechnungsnummer " + mine.get("invoice_number"), null, null)),
                        RadarRule.fingerprint(first, second, sameAmount)));
            }
        }
        return drafts;
    }
}
