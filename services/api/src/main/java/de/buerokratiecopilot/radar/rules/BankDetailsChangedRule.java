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
 * B3: the invoice asks to be paid into an account the sender has not used before.
 *
 * <p>Built on the check the invoice module already makes ({@code bank_details_changed}). The finding does not say the
 * invoice is fraudulent, it says the account is new and that a person should look before paying.</p>
 */
@Component
public class BankDetailsChangedRule implements RadarRule {

    static final int VERSION = 1;

    private final JdbcTemplate jdbc;

    public BankDetailsChangedRule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Description describe() {
        return new Description("B3", VERSION, "INVOICE_ANOMALY",
                "Kontoverbindung (IBAN) der Rechnung und die bisher bekannten Konten des Absenders",
                "Eine Rechnung wurde verarbeitet und ihre IBAN ist beim Absender bisher unbekannt, obwohl er schon bekannt war",
                "HIGH, weil eine unbemerkte Kontoänderung teuer ist",
                "Die Rechnung nennt ein Konto, auf das dieser Absender bisher nicht gezahlt wurde. Lieferanten wechseln die Bank; bevor gezahlt wird, sollte die Änderung über einen bekannten Weg bestätigt werden.",
                List.of("Belege öffnen", "Bestätigen", "Ablehnen", "Zurückstellen", "Verantwortlichen zuweisen"),
                "Eine Person bestätigt die neue Kontoverbindung oder lehnt den Befund ab",
                "Die Rechnung wird mit einer anderen IBAN erneut gelesen");
    }

    @Override
    public List<Draft> onDocument(UUID tenantId, UUID documentId) {
        List<Draft> drafts = new ArrayList<>();
        for (Map<String, Object> invoice : jdbc.queryForList("""
                select id, contact_id, iban, invoice_number from invoices
                where tenant_id = ? and document_id = ? and direction = 'INCOMING' and deleted_at is null
                  and bank_details_changed and iban is not null
                """, tenantId, documentId)) {
            UUID id = (UUID) invoice.get("id");
            drafts.add(new Draft("B3:" + id, "INVOICE_ANOMALY", "B3", VERSION,
                    "Neue Kontoverbindung auf der Rechnung",
                    "Die Rechnung nennt die IBAN " + invoice.get("iban") + ", die für diesen Absender bisher nicht bekannt war.",
                    RadarSeverity.HIGH, 1.0, null, (UUID) invoice.get("contact_id"), null,
                    List.of("BANK_DETAILS_NOT_SEEN_BEFORE"),
                    List.of(Map.of("action", "CONFIRM_BANK_DETAILS", "label", "Kontoänderung beim Absender bestätigen lassen")),
                    List.of(new Evidence("PRIMARY", documentId, null, "IBAN " + invoice.get("iban"), null, null)),
                    RadarRule.fingerprint(id, invoice.get("iban"))));
        }
        return drafts;
    }
}
