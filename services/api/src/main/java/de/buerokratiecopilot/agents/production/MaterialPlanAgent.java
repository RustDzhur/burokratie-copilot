package de.buerokratiecopilot.agents.production;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
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
 * {@code production-material-plan} — Materialplanung.
 *
 * <p>Aggregates material demand from the bill of materials or from the production work orders this
 * catalogue created, subtracts the supplied stock and open quantities and reports shortages. Without
 * stock data the agent will not claim a shortage: it produces the requirement list and an honest
 * non-success asking for the stock figures. It orders nothing.</p>
 */
@Component
public class MaterialPlanAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public MaterialPlanAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "production-material-plan";
    }

    @Override
    public AgentResult run(AgentContext context) {
        int leadTimeDays = AgentInputs.integer(context.input("leadTimeDays"), 14, 1, 365);
        Map<String, BigDecimal> demand = demand(context);
        if (demand.isEmpty()) {
            return AgentResult.skipped("Materialplanung: keine Stücklisten oder Materialangaben "
                    + "gefunden ('billOfMaterials' oder Fertigungsaufträge mit Material).");
        }
        Map<String, BigDecimal> stock = quantities(context.input("stock"));
        Map<String, BigDecimal> open = quantities(context.input("openOrders"));
        if (open == null) {
            open = Map.of();
        }
        BigDecimal totalDemand = demand.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        String sourceKey = "production-material-plan:" + AgentInputs.key(demand.toString(),
                stock == null ? "no-stock" : stock.toString(), open.toString());
        if (stock == null) {
            String html = html(demand, null, open, Map.of(), "Bestandsdaten fehlen");
            AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null,
                    null, "Materialbedarf (Bestand offen)", html,
                    Map.of("articles", demand.size()), List.of("stock"));
            if (document.created()) {
                context.addProduced("generated_document", document.id().toString(),
                        "Materialbedarfsliste");
            }
            String title = "Lagerbestand erfassen für Materialplanung";
            AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":stock", title,
                    "Der Materialbedarf für " + demand.size() + " Artikel wurde berechnet, aber es "
                            + "liegen keine Bestandsdaten vor. Ohne Bestand lässt sich keine "
                            + "Fehlmenge belegen; Bestand erfassen und Agent erneut starten.",
                    Task.Priority.HIGH, context.now().plus(Duration.ofDays(1)),
                    List.of("production", "material"),
                    Map.of("articles", demand.size(), "reason", "NO_STOCK_DATA"));
            if (task.created()) {
                context.addProduced("task", task.id().toString(), title);
            }
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("articles", String.valueOf(demand.size()));
            meta.put("totalDemand", totalDemand.toPlainString());
            meta.put("reason", "NO_STOCK_DATA");
            meta.put("ordered", "false");
            return new AgentResult(false, "Materialplanung: Bedarf für " + demand.size()
                    + " Artikel berechnet, aber ohne Bestandsdaten keine Fehlmenge belegbar — "
                    + "Bestandserfassung als Aufgabe angelegt.", context.produced(), meta);
        }
        List<Shortage> shortages = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : demand.entrySet()) {
            BigDecimal available = stock.getOrDefault(entry.getKey(), BigDecimal.ZERO)
                    .add(open.getOrDefault(entry.getKey(), BigDecimal.ZERO));
            BigDecimal missing = entry.getValue().subtract(available);
            if (missing.signum() > 0) {
                shortages.add(new Shortage(entry.getKey(), entry.getValue(), available, missing));
            }
        }
        String html = html(demand, stock, open,
                shortages.stream().collect(java.util.stream.Collectors.toMap(Shortage::article,
                        Shortage::missing)), null);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Materialplanung", html, Map.of("articles", demand.size(),
                        "shortages", shortages.size()), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(), "Materialplan");
        }
        int tasks = 0;
        for (Shortage shortage : shortages) {
            String title = "Material bestellen: " + shortage.article();
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    sourceKey + ":order:" + shortage.article(), title,
                    "Artikel: " + shortage.article()
                            + "\nBedarf: " + shortage.required()
                            + "\nVerfügbar (Bestand + offene Bestellungen): " + shortage.available()
                            + "\nFehlmenge: " + shortage.missing()
                            + "\nLieferzeit: " + leadTimeDays + " Tage"
                            + "\n\nBestellung erst nach Freigabe; der Agent bestellt nichts. "
                            + "Mindestbestellmenge und Lieferant sind zu prüfen.",
                    Task.Priority.HIGH, context.now().plus(Duration.ofDays(leadTimeDays)),
                    List.of("production", "material"),
                    Map.of("article", shortage.article(),
                            "missing", shortage.missing().toPlainString()));
            if (task.created()) {
                tasks++;
                context.addProduced("task", task.id().toString(), title);
            }
            context.addProduced("material", shortage.article(),
                    "Fehlmenge " + shortage.missing());
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("articles", String.valueOf(demand.size()));
        meta.put("totalDemand", totalDemand.toPlainString());
        meta.put("shortages", String.valueOf(shortages.size()));
        meta.put("tasksCreated", String.valueOf(tasks));
        meta.put("leadTimeDays", String.valueOf(leadTimeDays));
        meta.put("ordered", "false");
        if (shortages.isEmpty()) {
            return AgentResult.done("Materialplanung: Bedarf für " + demand.size()
                    + " Artikel gedeckt; keine Fehlmenge. Bestellt wurde nichts.", context.produced(), meta);
        }
        return AgentResult.done("Materialplanung: " + shortages.size() + " Fehlmenge(n) bei "
                + demand.size() + " Artikel(n), " + tasks + " Bestellvorschlag/Bestellvorschläge "
                + "als Aufgabe; bestellt wurde nichts.", context.produced(), meta);
    }

    private Map<String, BigDecimal> demand(AgentContext context) {
        Map<String, BigDecimal> demand = new LinkedHashMap<>();
        String bom = context.input("billOfMaterials");
        if (bom != null && !bom.isBlank()) {
            try {
                List<Map<String, Object>> rows = mapper.readValue(bom,
                        new TypeReference<List<Map<String, Object>>>() {
                        });
                for (Map<String, Object> row : rows) {
                    String article = text(row.get("article"), row.get("sku"), row.get("name"));
                    BigDecimal quantity = decimal(row.get("quantity"), row.get("required"));
                    if (article != null && quantity != null && quantity.signum() > 0) {
                        demand.merge(article, quantity, BigDecimal::add);
                    }
                }
            }
            catch (Exception ignored) {
                // fall through to work-order tasks
            }
        }
        if (!demand.isEmpty()) {
            return demand;
        }
        List<UUID> workOrderIds = AgentInputs.uuidList(context.input("workOrderIds"));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, metadata::text as metadata
                from tasks
                where tenant_id = ? and deleted_at is null
                  and status not in ('DONE', 'CANCELLED')
                  and metadata->>'agentKey' like 'production-work-order:%'
                """, context.tenantId());
        for (Map<String, Object> row : rows) {
            if (!workOrderIds.isEmpty()) {
                UUID taskId = (UUID) row.get("id");
                if (!workOrderIds.contains(taskId)) {
                    continue;
                }
            }
            try {
                Map<String, Object> metadata = mapper.readValue(String.valueOf(row.get("metadata")),
                        new TypeReference<Map<String, Object>>() {
                        });
                if (metadata.get("workOrder") instanceof Map<?, ?> workOrder
                        && workOrder.get("materials") instanceof List<?> materials) {
                    for (Object material : materials) {
                        if (material instanceof Map<?, ?> map) {
                            String article = text(map.get("article"), map.get("sku"), map.get("name"));
                            BigDecimal quantity = decimal(map.get("quantity"), map.get("required"));
                            if (article != null && quantity != null && quantity.signum() > 0) {
                                demand.merge(article, quantity, BigDecimal::add);
                            }
                        }
                    }
                }
            }
            catch (Exception ignored) {
                // skip unreadable metadata
            }
        }
        return demand;
    }

    private Map<String, BigDecimal> quantities(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        try {
            Object parsed = mapper.readValue(raw, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    BigDecimal quantity = decimal(entry.getValue());
                    if (quantity != null) {
                        values.put(String.valueOf(entry.getKey()), quantity);
                    }
                }
            }
            else if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        String article = text(map.get("article"), map.get("sku"), map.get("name"));
                        BigDecimal quantity = decimal(map.get("quantity"), map.get("available"));
                        if (article != null && quantity != null) {
                            values.put(article, quantity);
                        }
                    }
                }
            }
        }
        catch (Exception ignored) {
            return Map.of();
        }
        return values;
    }

    private static String html(Map<String, BigDecimal> demand, Map<String, BigDecimal> stock,
                               Map<String, BigDecimal> open, Map<String, BigDecimal> shortages,
                               String note) {
        StringBuilder html = new StringBuilder("<h1>Materialplanung</h1>");
        if (note != null) {
            html.append("<p><strong>Hinweis:</strong> ").append(AgentInputs.html(note)).append("</p>");
        }
        html.append("<table><tr><th>Artikel</th><th>Bedarf</th><th>Bestand</th>"
                + "<th>Offene Bestellungen</th><th>Fehlmenge</th></tr>");
        for (Map.Entry<String, BigDecimal> entry : demand.entrySet()) {
            html.append("<tr><td>").append(AgentInputs.html(entry.getKey())).append("</td><td>")
                    .append(entry.getValue()).append("</td><td>")
                    .append(stock == null ? "offen" : stock.getOrDefault(entry.getKey(), BigDecimal.ZERO))
                    .append("</td><td>").append(open.getOrDefault(entry.getKey(), BigDecimal.ZERO))
                    .append("</td><td>").append(shortages.getOrDefault(entry.getKey(),
                            BigDecimal.ZERO)).append("</td></tr>");
        }
        html.append("</table><p><em>Bestellvorschläge nennen Lieferzeit und Mindestbestellmenge "
                + "nicht automatisch; Bestellung erst nach Freigabe. Der Agent bestellt nichts.</em></p>");
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

    /** One material shortage. */
    public record Shortage(String article, BigDecimal required, BigDecimal available,
                           BigDecimal missing) {
    }
}
