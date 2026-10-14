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
import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * A1: the notice deadline of a contract comes close.
 *
 * <p>Only a deadline a person has confirmed is called a deadline. One the platform read but nobody confirmed is reported
 * as unconfirmed, at the lowest severity, so that it is not mistaken for a legal date. The Radar never calculates a legal
 * deadline itself: it reads the date stored on the contract.</p>
 */
@Component
public class NoticeDeadlineRule implements RadarRule {

    static final int VERSION = 1;

    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    public NoticeDeadlineRule(JdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public Description describe() {
        return new Description("A1", VERSION, "CONTRACT_RISK",
                "Aktive Verträge mit gespeichertem Kündigungsfrist-Datum und dem Vermerk, ob eine Person die Vertragsdaten bestätigt hat",
                "Täglich und bei jeder Prüfung: das Datum liegt innerhalb der eingestellten Tage",
                "Bestätigt: MEDIUM, HIGH innerhalb der eingestellten engeren Tage. Unbestätigt: INFO",
                "Die Frist für die Kündigung oder Nichtverlängerung des Vertrags kommt näher. Ohne Bestätigung durch eine Person ist das Datum nur gelesen und keine geprüfte Frist.",
                List.of("Belege öffnen", "Bestätigen", "Zurückstellen", "Verantwortlichen zuweisen", "Vorgang anlegen"),
                "Das Datum ist verstrichen, der Vertrag gekündigt oder das Datum geändert; der Radar schließt den Befund dann selbst",
                "Das Datum des Vertrags ändert sich nach einer Entscheidung");
    }

    @Override
    public boolean sweepIsComplete() {
        return true;
    }

    @Override
    public List<Draft> onSweep(UUID tenantId) {
        int warnDays = settings.getInt("radar.contract.notice_warning_days", 60);
        int highDays = settings.getInt("radar.contract.notice_high_days", 14);
        List<Draft> drafts = new ArrayList<>();
        for (Map<String, Object> contract : jdbc.queryForList("""
                select id, title, counterparty_contact_id, case_id, source_document_id, notice_deadline_date,
                       (notice_deadline_date - current_date) as days_left, data_verified_at is not null as confirmed
                from contracts
                where tenant_id = ? and deleted_at is null and status = 'ACTIVE' and notice_deadline_date is not null
                  and notice_deadline_date >= current_date and notice_deadline_date <= current_date + ?::int
                order by notice_deadline_date
                """, tenantId, warnDays)) {
            UUID id = (UUID) contract.get("id");
            int days = ((Number) contract.get("days_left")).intValue();
            boolean confirmed = Boolean.TRUE.equals(contract.get("confirmed"));
            RadarSeverity severity = !confirmed ? RadarSeverity.INFO : days <= highDays ? RadarSeverity.HIGH : RadarSeverity.MEDIUM;
            String date = String.valueOf(contract.get("notice_deadline_date"));
            List<Evidence> evidence = new ArrayList<>();
            if (contract.get("source_document_id") != null) {
                evidence.add(new Evidence("PRIMARY", (UUID) contract.get("source_document_id"), null, "Kündigungsfrist " + date, null, null));
            }
            for (UUID clause : jdbc.queryForList("""
                    select id from contract_clauses where contract_id = ? and tenant_id = ? and clause_type = 'NOTICE_PERIOD'
                    order by sort_order limit 1
                    """, UUID.class, id, tenantId)) {
                evidence.add(new Evidence("CONTEXT", null, null, null, null, clause));
            }
            drafts.add(new Draft("A1:" + id, "CONTRACT_RISK", "A1", VERSION,
                    confirmed ? "Kündigungsfrist nähert sich: " + contract.get("title")
                            : "Kündigungsfrist (unbestätigt): " + contract.get("title"),
                    (confirmed ? "Die bestätigte Frist endet am " : "Gelesen wurde ein Datum, das noch niemand bestätigt hat: ")
                            + date + " (in " + days + " Tagen)."
                            + (confirmed ? "" : " Bitte die Vertragsdaten prüfen, bevor darauf gebaut wird."),
                    severity, confirmed ? 1.0 : 0.5, (UUID) contract.get("case_id"), (UUID) contract.get("counterparty_contact_id"), id,
                    List.of(confirmed ? "NOTICE_DEADLINE_APPROACHING" : "DEADLINE_UNCONFIRMED"),
                    List.of(Map.of("action", "OPEN_CONTRACT", "label", "Vertrag öffnen")), evidence,
                    RadarRule.fingerprint(id, date, confirmed, severity)));
        }
        return drafts;
    }
}
