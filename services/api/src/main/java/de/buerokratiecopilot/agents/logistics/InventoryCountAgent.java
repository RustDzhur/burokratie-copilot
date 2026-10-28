package de.buerokratiecopilot.agents.logistics;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code logistics-inventory-count} — Inventur.
 *
 * <p>Prepares a stock count: one counting list per group/zone, a counting task with the count date
 * and a second-count task where required. A difference protocol is part of the list. The agent
 * posts no stock movement — a difference becomes a proposal only after the second count.</p>
 */
@Component
public class InventoryCountAgent implements Agent {

    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public InventoryCountAgent(ObjectMapper mapper, AgentWorkspace workspace) {
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "logistics-inventory-count";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID warehouseId = AgentInputs.uuid(context.input("warehouseId"));
        if (warehouseId == null) {
            return AgentResult.skipped("Inventur: 'warehouseId' fehlt oder ist keine gültige UUID.");
        }
        LocalDate countDate = AgentInputs.date(context.input("countDate"));
        if (countDate == null) {
            return AgentResult.skipped("Inventur: 'countDate' fehlt oder ist kein gültiges Datum.");
        }
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        if (countDate.isBefore(today)) {
            return AgentResult.skipped("Inventur: Stichtag " + countDate
                    + " liegt in der Vergangenheit.");
        }
        String raw = context.input("itemGroups");
        if (raw == null || raw.isBlank()) {
            return AgentResult.skipped("Inventur: keine Zählgruppen übergeben ('itemGroups') — "
                    + "ohne Gruppen gäbe es nur eine leere Zählliste.");
        }
        List<Group> groups;
        try {
            groups = parse(mapper.readValue(raw, new TypeReference<List<Object>>() {
            }));
        }
        catch (Exception ex) {
            return AgentResult.failed("Inventur: 'itemGroups' ist kein gültiges JSON-Array.");
        }
        if (groups.isEmpty()) {
            return AgentResult.skipped("Inventur: die Zählgruppenliste ist leer.");
        }
        boolean secondCount = AgentInputs.bool(context.input("secondCountRequired"), true);
        String sourceKey = "logistics-inventory-count:" + warehouseId + ":" + countDate + ":"
                + AgentInputs.key(groups.toString());
        String html = html(warehouseId, countDate, groups, secondCount);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Zählliste " + countDate, html,
                Map.of("warehouseId", warehouseId.toString(), "countDate", countDate.toString(),
                        "groups", groups.size(), "secondCount", secondCount),
                List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Zählliste " + countDate);
        }
        int tasks = 0;
        for (Group group : groups) {
            String title = "Inventur zählen: " + group.name() + " (" + countDate + ")";
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    sourceKey + ":count:" + group.name().toLowerCase(Locale.ROOT), title,
                    "Lagerort: " + (group.location() == null ? warehouseId.toString() : group.location())
                            + "\nStichtag: " + countDate
                            + "\nZählgruppe: " + group.name()
                            + "\n\nZählliste ausfüllen, Differenzen begründen. Der Agent bucht keine "
                            + "Bestandsänderung.",
                    Task.Priority.NORMAL, countDate.atTime(8, 0).atZone(AgentInputs.BERLIN).toInstant(),
                    List.of("logistics", "inventory", "count"),
                    Map.of("warehouseId", warehouseId.toString(), "group", group.name(),
                            "countDate", countDate.toString()));
            if (task.created()) {
                tasks++;
                context.addProduced("task", task.id().toString(), title);
            }
            if (secondCount) {
                String secondTitle = "Inventur Nachzählung: " + group.name() + " (" + countDate + ")";
                AgentWorkspace.TaskRef second = workspace.ensureTask(context,
                        sourceKey + ":recount:" + group.name().toLowerCase(Locale.ROOT), secondTitle,
                        "Zweite Zählung für Gruppe " + group.name() + " nach der Erstzählung. "
                                + "Abweichungen erst danach als Buchungsvorschlag ausweisen; der "
                                + "Agent bucht nichts.",
                        Task.Priority.NORMAL,
                        countDate.plusDays(1).atTime(8, 0).atZone(AgentInputs.BERLIN).toInstant(),
                        List.of("logistics", "inventory", "recount"),
                        Map.of("warehouseId", warehouseId.toString(), "group", group.name()));
                if (second.created()) {
                    tasks++;
                    context.addProduced("task", second.id().toString(), secondTitle);
                }
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("warehouseId", warehouseId.toString());
        meta.put("countDate", countDate.toString());
        meta.put("groups", String.valueOf(groups.size()));
        meta.put("tasksCreated", String.valueOf(tasks));
        meta.put("secondCountRequired", String.valueOf(secondCount));
        meta.put("posted", "false");
        return AgentResult.done("Inventur " + countDate + " vorbereitet: " + groups.size()
                + " Zählgruppe(n), " + tasks + " Aufgabe(n) angelegt; keine Bestandsbuchung.",
                context.produced(), meta);
    }

    private static List<Group> parse(List<Object> rawGroups) {
        List<Group> groups = new ArrayList<>();
        for (Object raw : rawGroups) {
            if (raw instanceof String name && !name.isBlank()) {
                groups.add(new Group(name.strip(), null));
            }
            else if (raw instanceof Map<?, ?> map) {
                Object name = map.get("name") == null ? map.get("group") : map.get("name");
                Object location = map.get("location") == null ? map.get("zone") : map.get("location");
                if (name != null && !String.valueOf(name).isBlank()) {
                    groups.add(new Group(String.valueOf(name).strip(),
                            location == null ? null : String.valueOf(location).strip()));
                }
            }
        }
        return List.copyOf(groups);
    }

    private static String html(UUID warehouseId, LocalDate countDate, List<Group> groups,
                               boolean secondCount) {
        StringBuilder html = new StringBuilder("<h1>Inventur ").append(countDate).append("</h1>")
                .append("<p>Lager: ").append(warehouseId).append("<br>Stichtag: ").append(countDate)
                .append("<br>Zweite Zählung: ").append(secondCount ? "erforderlich" : "nicht erforderlich")
                .append("</p><table><tr><th>Zählgruppe</th><th>Lagerort</th><th>Erstzählung</th>"
                        + "<th>Nachzählung</th><th>Differenz</th><th>Begründung</th></tr>");
        for (Group group : groups) {
            html.append("<tr><td>").append(AgentInputs.html(group.name())).append("</td><td>")
                    .append(AgentInputs.html(group.location() == null ? "—" : group.location()))
                    .append("</td><td></td><td></td><td></td><td></td></tr>");
        }
        html.append("</table><p><em>Abweichungen erst nach zweiter Zählung als Buchungsvorschlag "
                + "ausweisen. Der Agent bucht keine Bestandsänderung.</em></p>");
        return html.toString();
    }

    /** One count group. */
    public record Group(String name, String location) {
    }
}
