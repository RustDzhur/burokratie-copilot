package de.buerokratiecopilot.agents.production;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * {@code production-work-order} — Fertigungsauftrag.
 *
 * <p>Creates a production work order draft from an order reference, product, quantity and due date:
 * work steps, material requirements and checkpoints from the supplied JSON. Missing steps are marked
 * as open points instead of invented. The agent starts no machine and books no material
 * consumption.</p>
 */
@Component
public class WorkOrderAgent implements Agent {

    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public WorkOrderAgent(ObjectMapper mapper, AgentWorkspace workspace) {
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "production-work-order";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String orderId = AgentInputs.clean(context.input("orderId"));
        UUID productId = AgentInputs.uuid(context.input("productId"));
        BigDecimal quantity = AgentInputs.decimal(context.input("quantity"));
        LocalDate dueDate = AgentInputs.date(context.input("dueDate"));
        if (orderId == null || productId == null || quantity == null || dueDate == null) {
            return AgentResult.skipped("Fertigungsauftrag: 'orderId', 'productId', 'quantity' und "
                    + "'dueDate' sind erforderlich.");
        }
        if (quantity.signum() <= 0) {
            return AgentResult.failed("Fertigungsauftrag: 'quantity' muss positiv sein.");
        }
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        if (dueDate.isBefore(today)) {
            return AgentResult.failed("Fertigungsauftrag: das Fälligkeitsdatum " + dueDate
                    + " liegt in der Vergangenheit.");
        }
        List<Map<String, Object>> steps = jsonArray(context.input("workSteps"));
        List<Map<String, Object>> materials = jsonArray(context.input("materials"));
        List<String> openPoints = new ArrayList<>();
        if (steps.isEmpty()) {
            openPoints.add("Arbeitsfolgen/Zeiten noch nicht hinterlegt");
        }
        if (materials.isEmpty()) {
            openPoints.add("Materialbedarf noch nicht hinterlegt");
        }
        String sourceKey = "production-work-order:" + orderId + ":" + productId;
        String html = html(orderId, productId, quantity, dueDate, steps, materials, openPoints);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Fertigungsauftrag " + orderId, html,
                Map.of("orderId", orderId, "productId", productId.toString(),
                        "quantity", quantity.toPlainString(), "dueDate", dueDate.toString()),
                openPoints.isEmpty() ? List.of() : List.of("workSteps", "materials"));
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Fertigungsauftrag " + orderId);
        }
        Map<String, Object> workOrder = new LinkedHashMap<>();
        workOrder.put("orderId", orderId);
        workOrder.put("productId", productId.toString());
        workOrder.put("quantity", quantity.toPlainString());
        workOrder.put("dueDate", dueDate.toString());
        workOrder.put("workSteps", steps);
        workOrder.put("materials", materials);
        workOrder.put("openPoints", openPoints);
        String title = "Fertigungsauftrag " + orderId + " — " + productId;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":task", title,
                "Auftrag: " + orderId + "\nProdukt: " + productId + "\nMenge: " + quantity
                        + "\nFällig: " + dueDate
                        + "\nArbeitsfolgen: " + (steps.isEmpty() ? "offen" : steps.size())
                        + "\nMaterialpositionen: " + (materials.isEmpty() ? "offen" : materials.size())
                        + "\nOffene Punkte: " + (openPoints.isEmpty() ? "keine"
                                : String.join("; ", openPoints))
                        + "\n\nDer Agent startet keine Maschine und bucht keinen Verbrauch. "
                        + "Kapazität und Termin sind vor dem Start zu prüfen.",
                Task.Priority.NORMAL, dueDate.minusDays(3).atTime(8, 0)
                        .atZone(AgentInputs.BERLIN).toInstant(),
                List.of("production", "work-order"),
                Map.of("workOrder", workOrder, "openPoints", openPoints.size()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("orderId", orderId);
        meta.put("productId", productId.toString());
        meta.put("quantity", quantity.toPlainString());
        meta.put("dueDate", dueDate.toString());
        meta.put("steps", String.valueOf(steps.size()));
        meta.put("materials", String.valueOf(materials.size()));
        meta.put("openPoints", String.valueOf(openPoints.size()));
        meta.put("machineStarted", "false");
        return AgentResult.done("Fertigungsauftrag " + orderId + " für Produkt " + productId
                + " (" + quantity + ", fällig " + dueDate + ") angelegt: " + steps.size()
                + " Arbeitsfolge(n), " + materials.size() + " Materialposition(en)"
                + (openPoints.isEmpty() ? "" : ", " + openPoints.size() + " offene(r) Punkt(e)")
                + "; keine Maschine gestartet.", context.produced(), meta);
    }

    private List<Map<String, Object>> jsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            return List.of();
        }
    }

    private static String html(String orderId, UUID productId, BigDecimal quantity, LocalDate dueDate,
                               List<Map<String, Object>> steps, List<Map<String, Object>> materials,
                               List<String> openPoints) {
        StringBuilder html = new StringBuilder("<h1>Fertigungsauftrag ").append(AgentInputs.html(orderId))
                .append("</h1><p>Produkt: ").append(productId).append("<br>Menge: ").append(quantity)
                .append("<br>Fällig: ").append(dueDate).append("</p><h2>Arbeitsfolgen</h2>");
        if (steps.isEmpty()) {
            html.append("<p>offen</p>");
        }
        else {
            html.append("<ol>");
            for (Map<String, Object> step : steps) {
                html.append("<li>").append(AgentInputs.html(String.valueOf(
                        step.getOrDefault("step", step.getOrDefault("name", "Arbeitsfolge")))))
                        .append(step.get("minutes") == null ? "" : " — "
                                + step.get("minutes") + " Min.")
                        .append("</li>");
            }
            html.append("</ol>");
        }
        html.append("<h2>Materialbedarf</h2>");
        if (materials.isEmpty()) {
            html.append("<p>offen</p>");
        }
        else {
            html.append("<ul>");
            for (Map<String, Object> material : materials) {
                html.append("<li>").append(AgentInputs.html(String.valueOf(
                        material.getOrDefault("article", material.getOrDefault("name", "Material")))))
                        .append(": ").append(material.getOrDefault("quantity", "offen"))
                        .append(" ").append(material.getOrDefault("unit", "")).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<h2>Prüfpunkte</h2><ul><li>Kapazität und Termin gegen den vorhandenen Plan "
                + "prüfen.</li><li>Materialbereitstellung vor Start bestätigen.</li>"
                + "<li>Erste Stückprüfung und Freigabe dokumentieren.</li></ul>");
        if (!openPoints.isEmpty()) {
            html.append("<h2>Offene Punkte</h2><ul>");
            for (String point : openPoints) {
                html.append("<li>").append(AgentInputs.html(point)).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<p><em>Der Agent startet keine Maschine, bucht keinen Verbrauch und "
                + "verschiebt keinen Termin.</em></p>");
        return html.toString();
    }
}
