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

/** D1: a task is past its due date and still open. Its date is the stored one; the Radar invents none. */
@Component
public class OverdueTaskRule implements RadarRule {

    static final int VERSION = 1;

    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    public OverdueTaskRule(JdbcTemplate jdbc, SettingsService settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public Description describe() {
        return new Description("D1", VERSION, "UNRESOLVED_CASE",
                "Aufgaben mit gespeichertem Fälligkeitsdatum und Status; optional Vorgang und Quelldokument",
                "Täglich und bei jeder Prüfung: eine Aufgabe ist offen und ihr Fälligkeitsdatum liegt in der Vergangenheit",
                "MEDIUM; HIGH ab der eingestellten Zahl überfälliger Tage",
                "Die Aufgabe ist überfällig und noch nicht erledigt. Das Fälligkeitsdatum ist das gespeicherte; der Radar erfindet keines.",
                List.of("Belege öffnen", "Bestätigen", "Zurückstellen", "Verantwortlichen zuweisen"),
                "Die Aufgabe wird erledigt, abgebrochen oder ihr Datum verschoben; der Radar schließt den Befund dann selbst",
                "Die Aufgabe wird nach dem Abschluss mit neuem, wieder überschrittenem Datum erneut geöffnet");
    }

    @Override
    public boolean sweepIsComplete() {
        return true;
    }

    @Override
    public List<Draft> onSweep(UUID tenantId) {
        int highAfter = settings.getInt("radar.task.overdue_high_days", 7);
        List<Draft> drafts = new ArrayList<>();
        for (Map<String, Object> task : jdbc.queryForList("""
                select id, title, case_id, source_document_id, due_at,
                       (current_date - due_at::date) as days_overdue
                from tasks
                where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                  and due_at is not null and due_at < now()
                order by due_at
                """, tenantId)) {
            UUID id = (UUID) task.get("id");
            int days = ((Number) task.get("days_overdue")).intValue();
            RadarSeverity severity = days >= highAfter ? RadarSeverity.HIGH : RadarSeverity.MEDIUM;
            List<Evidence> evidence = new ArrayList<>();
            if (task.get("source_document_id") != null) {
                evidence.add(new Evidence("PRIMARY", (UUID) task.get("source_document_id"), null, null, null, null));
            }
            drafts.add(new Draft("D1:" + id, "UNRESOLVED_CASE", "D1", VERSION,
                    "Aufgabe überfällig: " + task.get("title"),
                    "Die Aufgabe war am " + String.valueOf(task.get("due_at")).substring(0, 10) + " fällig und ist noch offen"
                            + (days > 0 ? " (" + days + " Tage)." : "."),
                    severity, 1.0, (UUID) task.get("case_id"), null, null, List.of("TASK_PAST_DUE_DATE"),
                    List.of(Map.of("action", "OPEN_TASK", "label", "Aufgabe öffnen")), evidence,
                    RadarRule.fingerprint(id, String.valueOf(task.get("due_at")).substring(0, 10))));
        }
        return drafts;
    }
}
