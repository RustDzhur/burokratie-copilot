package de.buerokratiecopilot.agents.logistics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
 * {@code logistics-customs} — Zollanmeldung.
 *
 * <p>Validates the data a customs declaration needs: shipment reference, item descriptions,
 * quantities, origin country, Incoterm and customs value. Missing or contradictory data is returned
 * with a correction task; the declaration itself remains an approval-gated external step. The agent
 * submits nothing to a customs system.</p>
 */
@Component
public class CustomsAgent implements Agent {

    private static final List<String> INCOTERMS = List.of("EXW", "FCA", "FAS", "FOB", "CFR",
            "CIF", "CPT", "CIP", "DAP", "DPU", "DDP");

    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public CustomsAgent(ObjectMapper mapper, AgentWorkspace workspace) {
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "logistics-customs";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID shipmentId = AgentInputs.uuid(context.input("shipmentId"));
        if (shipmentId == null) {
            return AgentResult.skipped("Zollanmeldung: 'shipmentId' fehlt oder ist keine gültige UUID.");
        }
        String country = AgentInputs.clean(context.input("countryOfOrigin"));
        String incoterm = AgentInputs.clean(context.input("incoterm"));
        BigDecimal customsValue = AgentInputs.decimal(context.input("customsValue"));
        String rawItems = context.input("items");
        List<Item> items = new ArrayList<>();
        if (rawItems != null && !rawItems.isBlank()) {
            try {
                for (Map<String, Object> row : mapper.readValue(rawItems,
                        new TypeReference<List<Map<String, Object>>>() {
                        })) {
                    items.add(new Item(text(row.get("description"), row.get("article")),
                            row.get("quantity") instanceof Number number ? number.intValue()
                                    : AgentInputs.integer(text(row.get("quantity"))),
                            decimal(row.get("value"), row.get("unitValue"))));
                }
            }
            catch (Exception ex) {
                return AgentResult.failed("Zollanmeldung: 'items' ist kein gültiges JSON-Array.");
            }
        }
        List<String> problems = new ArrayList<>();
        if (items.isEmpty()) {
            problems.add("keine Sendungspositionen");
        }
        for (Item item : items) {
            if (item.description() == null) {
                problems.add("Position ohne Warenbeschreibung");
            }
            if (item.quantity() == null || item.quantity() <= 0) {
                problems.add("Position " + item.description() + ": Menge fehlt oder ist nicht positiv");
            }
            if (item.value() == null || item.value().signum() <= 0) {
                problems.add("Position " + item.description() + ": Warenwert fehlt");
            }
        }
        if (country == null || !country.matches("[A-Za-z]{2}")) {
            problems.add("Ursprungsland fehlt oder ist kein ISO-2-Ländercode");
        }
        if (incoterm == null || !INCOTERMS.contains(incoterm.toUpperCase(Locale.ROOT))) {
            problems.add("Incoterm fehlt oder ist unbekannt");
        }
        if (customsValue == null || customsValue.signum() <= 0) {
            problems.add("Zollwert fehlt oder ist nicht positiv");
        }
        String sourceKey = "logistics-customs:" + shipmentId;
        String html = html(shipmentId, country, incoterm, customsValue, items, problems);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Zollanmeldung prüfen " + shipmentId, html,
                Map.of("shipmentId", shipmentId.toString(),
                        "items", items.size(), "problems", problems.size()),
                problems.isEmpty() ? List.of() : List.of("customsData"));
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Zoll-Prüfprotokoll " + shipmentId);
        }
        if (!problems.isEmpty()) {
            String title = "Zollangaben ergänzen: " + shipmentId;
            AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":clarify", title,
                    "Sendung: " + shipmentId + "\nFehlende/widersprüchliche Angaben:\n- "
                            + String.join("\n- ", problems)
                            + "\n\nDer Agent meldet nichts beim Zoll an; Angaben korrigieren und "
                            + "Anmeldung freigeben.",
                    Task.Priority.HIGH, context.now().plus(Duration.ofDays(1)),
                    List.of("logistics", "customs"), Map.of("shipmentId", shipmentId.toString(),
                            "problems", problems.size()));
            if (task.created()) {
                context.addProduced("task", task.id().toString(), title);
            }
            return new AgentResult(false, "Zollanmeldung " + shipmentId + ": Angaben unvollständig — "
                    + String.join("; ", problems) + ". Nichts angemeldet.", context.produced(),
                    Map.of("reason", "CUSTOMS_DATA_INCOMPLETE", "shipmentId", shipmentId.toString(),
                            "problems", String.valueOf(problems.size())));
        }
        BigDecimal itemsValue = items.stream().map(item -> item.value()
                        .multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean mismatch = customsValue.compareTo(BigDecimal.ZERO) > 0
                && itemsValue.subtract(customsValue).abs()
                        .compareTo(customsValue.multiply(new BigDecimal("0.05"))) > 0;
        String title = "Zollanmeldung freigeben: " + shipmentId;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":approval", title,
                "Sendung: " + shipmentId
                        + "\nWarenwert: " + customsValue + " EUR"
                        + "\nPositionssumme: " + itemsValue + " EUR"
                        + (mismatch ? "\nHinweis: Positionssumme weicht um mehr als 5 % vom Zollwert ab."
                                : "")
                        + "\nUrsprungsland: " + country.toUpperCase(Locale.ROOT)
                        + "\nIncoterm: " + incoterm
                        + "\n\nDer Agent meldet nichts an; Anmeldung nach Freigabe durch eine "
                        + "verantwortliche Person.",
                mismatch ? Task.Priority.HIGH : Task.Priority.NORMAL,
                context.now().plus(Duration.ofDays(2)),
                List.of("logistics", "customs"),
                Map.of("shipmentId", shipmentId.toString(), "customsValue", customsValue.toPlainString(),
                        "valueMismatch", mismatch));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("shipmentId", shipmentId.toString());
        meta.put("customsValue", customsValue.toPlainString());
        meta.put("itemsValue", itemsValue.setScale(2, RoundingMode.HALF_UP).toPlainString());
        meta.put("incoterm", incoterm.toUpperCase(Locale.ROOT));
        meta.put("countryOfOrigin", country.toUpperCase(Locale.ROOT));
        meta.put("valueMismatch", String.valueOf(mismatch));
        meta.put("filed", "false");
        return AgentResult.done("Sendung " + shipmentId + " zollseitig geprüft: " + items.size()
                + " Position(en), Zollwert " + customsValue + " EUR, Incoterm " + incoterm
                + "; Anmeldung bleibt freigabepflichtig.", context.produced(), meta);
    }

    private static String html(UUID shipmentId, String country, String incoterm,
                               BigDecimal customsValue, List<Item> items, List<String> problems) {
        StringBuilder html = new StringBuilder("<h1>Zollanmeldung — Prüfprotokoll</h1>")
                .append("<p>Sendung: ").append(shipmentId)
                .append("<br>Ursprungsland: ").append(AgentInputs.html(country == null ? "fehlt" : country))
                .append("<br>Incoterm: ").append(AgentInputs.html(incoterm == null ? "fehlt" : incoterm))
                .append("<br>Zollwert: ").append(customsValue == null ? "fehlt" : customsValue)
                .append("</p><table><tr><th>Warenbeschreibung</th><th>Menge</th><th>Wert</th></tr>");
        for (Item item : items) {
            html.append("<tr><td>").append(AgentInputs.html(item.description())).append("</td><td>")
                    .append(item.quantity() == null ? "fehlt" : item.quantity()).append("</td><td>")
                    .append(item.value() == null ? "fehlt" : item.value()).append("</td></tr>");
        }
        html.append("</table>");
        if (!problems.isEmpty()) {
            html.append("<h2>Fehlende oder widersprüchliche Angaben</h2><ul>");
            for (String problem : problems) {
                html.append("<li>").append(AgentInputs.html(problem)).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<p><em>Der Agent meldet nichts beim Zoll an. Die Anmeldung ist ein "
                + "freigabepflichtiger externer Schritt; Tarifnummern und rechtliche Bewertung "
                + "bleiben menschliche Prüfung.</em></p>");
        return html.toString();
    }

    private static String text(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).strip();
            }
        }
        return null;
    }

    private static BigDecimal decimal(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            try {
                return new BigDecimal(String.valueOf(value).replace(",", "."));
            }
            catch (NumberFormatException ignored) {
                // next
            }
        }
        return null;
    }

    /** One shipment item. */
    public record Item(String description, Integer quantity, BigDecimal value) {
    }
}
