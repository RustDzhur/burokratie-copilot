package de.buerokratiecopilot.radar.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * B1: an invoice asks for another amount than the previous invoice of the same sender.
 *
 * <p>A different amount is a question, not an error: prices change, usage changes. The finding says what differs, with
 * both invoices, and leaves the judgement to a person. If the contract names a price, it is shown next to the amounts.</p>
 */
@Component
public class InvoiceAmountChangedRule implements RadarRule {

    static final int VERSION = 1;

    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    public InvoiceAmountChangedRule(JdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public Description describe() {
        return new Description("B1", VERSION, "INVOICE_ANOMALY",
                "Betrag der Rechnung, Absender, letzte frühere Rechnung desselben Absenders in derselben Währung; optional Vertragspreis",
                "Eine Rechnung wurde verarbeitet und ihr Betrag weicht um mehr als den eingestellten Prozentsatz vom letzten Betrag ab",
                "MEDIUM; HIGH ab dem eingestellten höheren Prozentsatz",
                "Der Betrag dieser Rechnung weicht vom letzten Rechnungsbetrag desselben Absenders ab. Das kann berechtigt sein (Preis- oder Mengenänderung); der Befund ist keine Aussage über einen Fehler.",
                List.of("Belege öffnen", "Bestätigen", "Ablehnen", "Zurückstellen", "Verantwortlichen zuweisen", "Vorgang anlegen"),
                "Eine Person schließt den Befund ab, zum Beispiel weil die Änderung vereinbart war",
                "Die Rechnung wird mit einem anderen Betrag erneut gelesen");
    }

    @Override
    public List<Draft> onDocument(UUID tenantId, UUID documentId) {
        List<Map<String, Object>> invoices = jdbc.queryForList("""
                select id, contact_id, contract_id, currency, gross_amount, invoice_number,
                       coalesce(issue_date, created_at::date) as reference_date, created_at
                from invoices
                where tenant_id = ? and document_id = ? and direction = 'INCOMING' and deleted_at is null
                  and duplicate_of_invoice_id is null and gross_amount is not null and contact_id is not null
                """, tenantId, documentId);
        List<Draft> drafts = new ArrayList<>();
        for (Map<String, Object> invoice : invoices) {
            List<Map<String, Object>> previous = jdbc.queryForList("""
                    select p.id, p.gross_amount, p.document_id, p.invoice_number
                    from invoices p
                    where p.tenant_id = ? and p.contact_id = ? and p.id <> ? and p.currency = ? and p.direction = 'INCOMING'
                      and p.deleted_at is null and p.duplicate_of_invoice_id is null and p.gross_amount is not null
                      and p.gross_amount > 0
                      and (coalesce(p.issue_date, p.created_at::date), p.created_at)
                          < (?::date, ?::timestamptz)
                    order by coalesce(p.issue_date, p.created_at::date) desc, p.created_at desc limit 1
                    """, tenantId, invoice.get("contact_id"), invoice.get("id"), invoice.get("currency"),
                    invoice.get("reference_date"), invoice.get("created_at"));
            if (previous.isEmpty()) {
                continue;
            }
            BigDecimal now = (BigDecimal) invoice.get("gross_amount");
            BigDecimal before = (BigDecimal) previous.get(0).get("gross_amount");
            BigDecimal percent = now.subtract(before).abs().multiply(BigDecimal.valueOf(100))
                    .divide(before, 1, RoundingMode.HALF_UP);
            if (percent.doubleValue() <= settings.getDouble("radar.invoice.amount_change_percent", 10)) {
                continue;
            }
            RadarSeverity severity = percent.doubleValue() >= settings.getDouble("radar.invoice.amount_change_high_percent", 50)
                    ? RadarSeverity.HIGH : RadarSeverity.MEDIUM;
            String direction = now.compareTo(before) > 0 ? "höher" : "niedriger";
            String currency = String.valueOf(invoice.get("currency"));

            List<String> reasons = new ArrayList<>(List.of("AMOUNT_DIFFERS_FROM_PREVIOUS_INVOICE"));
            StringBuilder description = new StringBuilder("Der Betrag ").append(now.toPlainString()).append(' ').append(currency)
                    .append(" ist ").append(percent.toPlainString()).append(" % ").append(direction)
                    .append(" als der letzte Betrag ").append(before.toPlainString()).append(' ').append(currency).append('.');
            List<Evidence> evidence = new ArrayList<>();
            evidence.add(new Evidence("PRIMARY", documentId, null, "Rechnungsbetrag " + now.toPlainString() + " " + currency, null, null));
            if (previous.get(0).get("document_id") != null) {
                evidence.add(new Evidence("COMPARISON", (UUID) previous.get(0).get("document_id"), null,
                        "Letzter Betrag " + before.toPlainString() + " " + currency, null, null));
            }
            UUID contractId = (UUID) invoice.get("contract_id");
            if (contractId != null) {
                for (Map<String, Object> contract : jdbc.queryForList("""
                        select cost_amount, cost_interval from contracts where id = ? and tenant_id = ? and cost_amount is not null
                        """, contractId, tenantId)) {
                    BigDecimal price = (BigDecimal) contract.get("cost_amount");
                    description.append(" Der Vertrag nennt ").append(price.toPlainString()).append(' ').append(currency);
                    if (contract.get("cost_interval") != null) {
                        description.append(" (").append(contract.get("cost_interval")).append(')');
                    }
                    description.append('.');
                    if (price.compareTo(now) != 0) {
                        reasons.add("AMOUNT_DIFFERS_FROM_CONTRACT_PRICE");
                    }
                    for (UUID clause : jdbc.queryForList("""
                            select id from contract_clauses where contract_id = ? and tenant_id = ? and clause_type = 'PRICE'
                            order by sort_order limit 1
                            """, UUID.class, contractId, tenantId)) {
                        evidence.add(new Evidence("CONTEXT", null, null, null, null, clause));
                    }
                }
            }
            UUID invoiceId = (UUID) invoice.get("id");
            drafts.add(new Draft("B1:" + invoiceId, "INVOICE_ANOMALY", "B1", VERSION,
                    "Rechnungsbetrag weicht vom letzten Betrag ab", description.toString(), severity,
                    1.0, null, (UUID) invoice.get("contact_id"), contractId, reasons, List.of(
                            Map.of("action", "REVIEW_INVOICE", "label", "Rechnung mit der letzten vergleichen"),
                            Map.of("action", "ASK_SENDER", "label", "Beim Absender nachfragen")),
                    evidence, RadarRule.fingerprint(invoiceId, before.toPlainString(), now.toPlainString(), previous.get(0).get("id"))));
        }
        return drafts;
    }
}
