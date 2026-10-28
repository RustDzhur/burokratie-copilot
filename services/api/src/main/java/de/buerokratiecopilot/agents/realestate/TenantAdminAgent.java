package de.buerokratiecopilot.agents.realestate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code realestate-tenant-admin} — Mieterverwaltung.
 *
 * <p>Prepares a move-in, move-out or master-data change as a dated checklist: contract, deposit,
 * meter readings, handover protocol, keys and effective date. Each step becomes a task; the
 * document records the facts. The agent books no payment and creates no operating-cost statement.</p>
 */
@Component
public class TenantAdminAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public TenantAdminAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "realestate-tenant-admin";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID propertyId = AgentInputs.uuid(context.input("propertyId"));
        UUID tenantId = AgentInputs.uuid(context.input("tenantContactId"));
        LocalDate effectiveDate = AgentInputs.date(context.input("effectiveDate"));
        String changeType = AgentInputs.blankTo(context.input("changeType"), "CHANGE")
                .toUpperCase(Locale.ROOT);
        if (propertyId == null || tenantId == null || effectiveDate == null) {
            return AgentResult.skipped("Mieterverwaltung: 'propertyId', 'tenantContactId' und "
                    + "'effectiveDate' sind erforderlich.");
        }
        if (!List.of("MOVE_IN", "MOVE_OUT", "CHANGE").contains(changeType)) {
            return AgentResult.failed("Mieterverwaltung: unbekannter Änderungstyp '" + changeType + "'.");
        }
        String tenantName = findContact(context.tenantId(), tenantId);
        if (tenantName == null) {
            return AgentResult.skipped("Mieterverwaltung: Kontakt " + tenantId
                    + " ist im Mandanten nicht vorhanden.");
        }
        BigDecimal deposit = AgentInputs.decimal(context.input("depositAmount"));
        if (context.input("depositAmount") != null && !context.input("depositAmount").isBlank()
                && deposit == null) {
            return AgentResult.failed("Mieterverwaltung: 'depositAmount' ist keine gültige Dezimalzahl.");
        }
        List<Check> checks = checklist(changeType, effectiveDate, deposit);
        String sourceKey = "realestate-tenant-admin:" + propertyId + ":" + tenantId + ":"
                + changeType + ":" + effectiveDate;
        String html = html(propertyId, tenantName, changeType, effectiveDate, deposit, checks);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Mieterwechsel " + propertyId + " — " + tenantName, html,
                Map.of("propertyId", propertyId.toString(), "tenantContactId", tenantId.toString(),
                        "changeType", changeType, "effectiveDate", effectiveDate.toString()),
                List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Mieterwechsel " + propertyId);
        }
        int tasks = 0;
        for (Check check : checks) {
            String title = check.title() + ": " + tenantName + " (" + propertyId + ")";
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    sourceKey + ":check:" + check.key(), title,
                    "Objekt: " + propertyId + "\nMieter: " + tenantName + "\nTyp: " + changeType
                            + "\nWirksamkeit: " + effectiveDate
                            + "\nSchritt: " + check.description()
                            + (deposit == null ? "" : "\nKaution: " + deposit + " EUR (nur erfasst, "
                                    + "nicht gebucht)")
                            + "\n\nDer Agent bucht keine Zahlung und erstellt keine "
                            + "Betriebskostenabrechnung.",
                    Task.Priority.NORMAL, check.dueDate().atTime(9, 0)
                            .atZone(AgentInputs.BERLIN).toInstant(),
                    List.of("realestate", "tenant", changeType.toLowerCase(Locale.ROOT)),
                    Map.of("propertyId", propertyId.toString(), "tenantContactId", tenantId.toString(),
                            "changeType", changeType));
            if (task.created()) {
                tasks++;
                context.addProduced("task", task.id().toString(), title);
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("propertyId", propertyId.toString());
        meta.put("tenantContactId", tenantId.toString());
        meta.put("changeType", changeType);
        meta.put("effectiveDate", effectiveDate.toString());
        meta.put("steps", String.valueOf(checks.size()));
        meta.put("tasksCreated", String.valueOf(tasks));
        meta.put("depositBooked", "false");
        return AgentResult.done("Mieterwechsel " + changeType + " für " + tenantName
                + " zum " + effectiveDate + " vorbereitet: " + checks.size() + " Schritt(e), "
                + tasks + " Aufgabe(n); keine Zahlung gebucht.", context.produced(), meta);
    }

    /** The checklist as data; pure and testable. */
    public static List<Check> checklist(String changeType, LocalDate effectiveDate, BigDecimal deposit) {
        List<Check> checks = new ArrayList<>();
        if ("MOVE_IN".equals(changeType)) {
            checks.add(new Check("vertrag", "Mietvertrag prüfen und ablegen", effectiveDate.minusDays(7)));
            checks.add(new Check("kaution", deposit == null
                    ? "Kaution vereinbaren (Betrag offen)" : "Kaution " + deposit + " EUR vereinbaren "
                            + "(keine Buchung)", effectiveDate.minusDays(5)));
            checks.add(new Check("zaehler", "Zählerstände bei Einzug erfassen", effectiveDate));
            checks.add(new Check("uebergabe", "Wohnungsübergabeprotokoll erstellen", effectiveDate));
            checks.add(new Check("schluessel", "Schlüsselübergabe dokumentieren", effectiveDate));
        }
        else if ("MOVE_OUT".equals(changeType)) {
            checks.add(new Check("kuendigung", "Kündigung und Auszugstermin prüfen", effectiveDate.minusDays(14)));
            checks.add(new Check("zaehler", "Zählerstände bei Auszug erfassen", effectiveDate));
            checks.add(new Check("uebergabe", "Übergabeprotokoll und Schäden dokumentieren", effectiveDate));
            checks.add(new Check("schluessel", "Schlüsselrückgabe dokumentieren", effectiveDate));
            checks.add(new Check("kaution", "Kaution abrechnen (nur vorbereiten, keine Buchung)",
                    effectiveDate.plusDays(30)));
        }
        else {
            checks.add(new Check("stammdaten", "Stammdaten ändern und Wirksamkeitsdatum setzen", effectiveDate));
            checks.add(new Check("nachweis", "Nachweis zur Änderung ablegen", effectiveDate));
            checks.add(new Check("ansprechpartner", "Ansprechpartner und Kommunikationsweg aktualisieren",
                    effectiveDate));
        }
        return List.copyOf(checks);
    }

    private String findContact(UUID tenantId, UUID contactId) {
        List<String> rows = jdbc.query("""
                select display_name from contacts
                where tenant_id = ? and id = ? and deleted_at is null
                """, (rs, row) -> rs.getString(1), tenantId, contactId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String html(UUID propertyId, String tenant, String changeType,
                               LocalDate effectiveDate, BigDecimal deposit, List<Check> checks) {
        StringBuilder html = new StringBuilder("<h1>Mieterwechsel</h1><p>Objekt: ")
                .append(propertyId).append("<br>Mieter: ").append(AgentInputs.html(tenant))
                .append("<br>Typ: ").append(changeType).append("<br>Wirksamkeit: ")
                .append(effectiveDate).append("<br>Kaution: ")
                .append(deposit == null ? "nicht angegeben" : deposit + " EUR")
                .append("</p><table><tr><th>Schritt</th><th>Beschreibung</th><th>Fällig</th></tr>");
        for (Check check : checks) {
            html.append("<tr><td>").append(check.title()).append("</td><td>")
                    .append(AgentInputs.html(check.description())).append("</td><td>")
                    .append(check.dueDate()).append("</td></tr>");
        }
        html.append("</table><p><em>Der Agent bucht keine Zahlungen und erstellt keine "
                + "Betriebskostenabrechnung. Änderungen werden mit Wirksamkeitsdatum und Nachweis "
                + "dokumentiert.</em></p>");
        return html.toString();
    }

    /** One checklist step. */
    public record Check(String key, String title, LocalDate dueDate) {

        String description() {
            return title;
        }
    }
}
