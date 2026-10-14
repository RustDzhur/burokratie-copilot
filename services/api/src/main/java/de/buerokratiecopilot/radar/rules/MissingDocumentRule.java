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

/** C1: an open case lacks a document its type requires. The requirement list is data of the case type, not code. */
@Component
public class MissingDocumentRule implements RadarRule {

    static final int VERSION = 1;

    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    public MissingDocumentRule(JdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public Description describe() {
        return new Description("C1", VERSION, "MISSING_INFORMATION",
                "Offene Vorgänge, die Pflichtdokumente ihres Vorgangstyps (Daten des Typs) und die mit dem Vorgang verknüpften Dokumente",
                "Täglich und bei jeder Prüfung: ein Vorgang ist seit der eingestellten Zahl von Tagen offen und für eine Pflichtanforderung ist kein Dokument des geforderten Typs verknüpft",
                "LOW",
                "Für diesen Vorgang fehlt ein Dokument, das sein Typ verlangt. Es ist keine Aussage darüber, ob der Vorgang scheitert.",
                List.of("Belege öffnen", "Bestätigen", "Zurückstellen", "Verantwortlichen zuweisen"),
                "Das fehlende Dokument wird verknüpft oder der Vorgang abgeschlossen; der Radar schließt den Befund dann selbst",
                "Eine Pflichtanforderung kommt hinzu oder ein verknüpftes Dokument wird entfernt");
    }

    @Override
    public boolean sweepIsComplete() {
        return true;
    }

    @Override
    public List<Draft> onSweep(UUID tenantId) {
        int afterDays = settings.getInt("radar.case.missing_document_after_days", 3);
        List<Draft> drafts = new ArrayList<>();
        for (Map<String, Object> theCase : jdbc.queryForList("""
                select c.id, c.reference, c.title, c.counterparty_contact_id, c.case_type_id
                from cases c
                where c.tenant_id = ? and c.deleted_at is null and c.status not in ('COMPLETED', 'CLOSED')
                  and c.case_type_id is not null and c.opened_at <= now() - (?::int * interval '1 day')
                """, tenantId, afterDays)) {
            UUID caseId = (UUID) theCase.get("id");
            List<Map<String, Object>> missing = jdbc.queryForList("""
                    select r.requirement_key, coalesce(r.label ->> 'de', r.requirement_key) as label
                    from case_type_document_requirements r
                    where r.case_type_id = ? and r.is_mandatory and r.document_type_id is not null
                      and not exists (select 1 from case_documents cd join documents d on d.id = cd.document_id
                                      where cd.case_id = ? and d.deleted_at is null and d.document_type_id = r.document_type_id)
                    order by r.sort_order, r.requirement_key
                    """, theCase.get("case_type_id"), caseId);
            if (missing.isEmpty()) {
                continue;
            }
            List<String> labels = missing.stream().map(row -> (String) row.get("label")).toList();
            List<Evidence> evidence = new ArrayList<>();
            for (UUID documentId : jdbc.queryForList("""
                    select d.id from case_documents cd join documents d on d.id = cd.document_id
                    where cd.case_id = ? and d.deleted_at is null order by cd.created_at limit 5
                    """, UUID.class, caseId)) {
                evidence.add(new Evidence("CONTEXT", documentId, null, null, null, null));
            }
            drafts.add(new Draft("C1:" + caseId, "MISSING_INFORMATION", "C1", VERSION,
                    "Fehlendes Dokument im Vorgang " + theCase.get("reference"),
                    "Für den Vorgang „" + theCase.get("title") + "“ fehlt: " + String.join(", ", labels) + ".",
                    RadarSeverity.LOW, 1.0, caseId, (UUID) theCase.get("counterparty_contact_id"), null,
                    List.of("MANDATORY_DOCUMENT_MISSING"),
                    List.of(Map.of("action", "OPEN_CASE", "label", "Vorgang öffnen")), evidence,
                    RadarRule.fingerprint(caseId, missing.stream().map(row -> row.get("requirement_key")).toList())));
        }
        return drafts;
    }
}
