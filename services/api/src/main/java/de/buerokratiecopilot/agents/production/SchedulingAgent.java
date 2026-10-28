package de.buerokratiecopilot.agents.production;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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
 * {@code production-scheduling} — Terminplanung.
 *
 * <p>Reads the open production work orders this catalogue created (or a supplied work-order list),
 * allocates them by earliest due date over the available daily capacity and reports days on which an
 * order cannot meet its due date. The result is a proposal for the production manager; the agent
 * starts no machine and moves no order.</p>
 */
@Component
public class SchedulingAgent implements Agent {

    private static final double DEFAULT_DAILY_HOURS = 8.0;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public SchedulingAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "production-scheduling";
    }

    @Override
    public AgentResult run(AgentContext context) {
        int horizonDays = AgentInputs.integer(context.input("horizonDays"), 14, 1, 90);
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        LocalDate end = today.plusDays(horizonDays);
        List<WorkOrder> orders = workOrders(context);
        if (orders.isEmpty()) {
            return AgentResult.skipped("Terminplanung: keine offenen Fertigungsaufträge gefunden. "
                    + "Zuerst 'production-work-order' ausführen oder 'workOrders' übergeben.");
        }
        List<Capacity> capacities = capacities(context.input("machineCapacities"));
        Schedule schedule = schedule(orders, capacities, today, end);
        String sourceKey = "production-scheduling:" + today + ":" + end + ":"
                + AgentInputs.key(orders.stream().map(WorkOrder::id).toList().toString());
        String html = html(today, end, capacities, schedule);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Terminplanung " + today + " bis " + end, html,
                Map.of("from", today.toString(), "to", end.toString(),
                        "orders", orders.size(), "overloaded", schedule.overloaded().size()), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(), "Terminplan");
        }
        String title = "Terminplan prüfen und bestätigen (" + today + ")";
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":approval", title,
                schedule.rows().size() + " Aufträge eingeplant, " + schedule.overloaded().size()
                        + " mit Terminkonflikt.\nDer Agent startet keine Maschine und verschiebt "
                        + "keinen Auftrag; Bestätigung durch die Produktionsleitung.",
                schedule.overloaded().isEmpty() ? Task.Priority.NORMAL : Task.Priority.HIGH,
                context.now().plus(Duration.ofDays(1)),
                List.of("production", "scheduling"),
                Map.of("orders", orders.size(),
                        "overloaded", schedule.overloaded().size(),
                        "horizonDays", horizonDays));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        for (ScheduleRow row : schedule.rows().stream().limit(20).toList()) {
            context.addProduced("schedule", row.orderId(),
                    row.orderId() + " → " + row.scheduledDate()
                            + (row.conflict() ? " (Konflikt)" : ""));
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("orders", String.valueOf(orders.size()));
        meta.put("scheduled", String.valueOf(schedule.rows().size()));
        meta.put("overloaded", String.valueOf(schedule.overloaded().size()));
        meta.put("horizonDays", String.valueOf(horizonDays));
        meta.put("capacityHoursPerDay", String.valueOf(capacities.stream()
                .mapToDouble(Capacity::hoursPerDay).sum()));
        meta.put("machineStarted", "false");
        if (!schedule.overloaded().isEmpty()) {
            return new AgentResult(false, "Terminplanung: " + schedule.rows().size()
                    + " Aufträge eingeplant, aber " + schedule.overloaded().size()
                    + " können im Horizont nicht fristgerecht gefertigt werden — Kapazität "
                    + "prüfen. Keine Maschine gestartet.", context.produced(), meta);
        }
        return AgentResult.done("Terminplanung " + today + " bis " + end + ": "
                + schedule.rows().size() + " Auftrag/Aufträge eingeplant, kein Terminkonflikt im "
                + "Horizont; Bestätigung offen.", context.produced(), meta);
    }

    private List<WorkOrder> workOrders(AgentContext context) {
        List<WorkOrder> result = new ArrayList<>();
        String raw = context.input("workOrders");
        if (raw != null && !raw.isBlank()) {
            try {
                for (Map<String, Object> row : mapper.readValue(raw,
                        new TypeReference<List<Map<String, Object>>>() {
                        })) {
                    WorkOrder order = fromMap(row);
                    if (order != null) {
                        result.add(order);
                    }
                }
            }
            catch (Exception ignored) {
                // fall through to catalogue tasks
            }
        }
        if (!result.isEmpty()) {
            return List.copyOf(result);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, title, due_at, metadata::text as metadata
                from tasks
                where tenant_id = ? and deleted_at is null
                  and status not in ('DONE', 'CANCELLED')
                  and metadata->>'agentKey' like 'production-work-order:%'
                order by due_at nulls last
                """, context.tenantId());
        for (Map<String, Object> row : rows) {
            try {
                Map<String, Object> metadata = mapper.readValue(String.valueOf(row.get("metadata")),
                        new TypeReference<Map<String, Object>>() {
                        });
                Object nested = metadata.get("workOrder");
                if (nested instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> orderMap = (Map<String, Object>) map;
                    WorkOrder order = fromMap(orderMap);
                    if (order != null) {
                        result.add(order);
                    }
                }
            }
            catch (Exception ignored) {
                // skip an unreadable work-order task
            }
        }
        return List.copyOf(result);
    }

    private static WorkOrder fromMap(Map<String, Object> map) {
        String id = map.get("orderId") == null ? null : String.valueOf(map.get("orderId"));
        LocalDate due = AgentInputs.date(map.get("dueDate"));
        if (id == null || due == null) {
            return null;
        }
        double minutes = 0;
        Object steps = map.get("workSteps");
        if (steps instanceof List<?> list) {
            for (Object step : list) {
                if (step instanceof Map<?, ?> stepMap && stepMap.get("minutes") instanceof Number number) {
                    minutes += number.doubleValue();
                }
            }
        }
        boolean estimated = minutes <= 0;
        if (estimated) {
            minutes = 60; // explicit estimate, flagged in the report
        }
        return new WorkOrder(id, due, minutes / 60.0, estimated);
    }

    private static List<Capacity> capacities(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                List<Map<String, Object>> rows = new ObjectMapper().readValue(raw,
                        new TypeReference<List<Map<String, Object>>>() {
                        });
                List<Capacity> capacities = new ArrayList<>();
                for (Map<String, Object> row : rows) {
                    String machine = row.get("machine") == null ? "Linie" : String.valueOf(row.get("machine"));
                    Object hours = row.get("hoursPerDay");
                    double value = hours instanceof Number number ? number.doubleValue() : 8.0;
                    if (value > 0) {
                        capacities.add(new Capacity(machine, value));
                    }
                }
                if (!capacities.isEmpty()) {
                    return List.copyOf(capacities);
                }
            }
            catch (Exception ignored) {
                // default capacity
            }
        }
        return List.of(new Capacity("Standardkapazität", DEFAULT_DAILY_HOURS));
    }

    /** Earliest-due-date allocation over daily capacity; pure and testable. */
    public static Schedule schedule(List<WorkOrder> orders, List<Capacity> capacities,
                                    LocalDate today, LocalDate end) {
        double capacityPerDay = capacities.stream().mapToDouble(Capacity::hoursPerDay).sum();
        List<WorkOrder> sorted = new ArrayList<>(orders);
        sorted.sort(Comparator.comparing(WorkOrder::dueDate));
        Map<LocalDate, Double> used = new LinkedHashMap<>();
        List<ScheduleRow> rows = new ArrayList<>();
        List<ScheduleRow> overloaded = new ArrayList<>();
        for (WorkOrder order : sorted) {
            LocalDate date = nextWorkday(today);
            double remaining = order.hours();
            while (!date.isAfter(end) && remaining > 0) {
                double usedToday = used.getOrDefault(date, 0.0);
                double free = Math.max(0, capacityPerDay - usedToday);
                if (free <= 0) {
                    date = nextWorkday(date.plusDays(1));
                    continue;
                }
                double allocate = Math.min(free, remaining);
                used.merge(date, allocate, Double::sum);
                remaining -= allocate;
                if (remaining > 0) {
                    date = nextWorkday(date.plusDays(1));
                }
            }
            boolean conflict = date.isAfter(end) || date.isAfter(order.dueDate());
            ScheduleRow row = new ScheduleRow(order.id(), date.isAfter(end) ? null : date,
                    order.dueDate(), order.hours(), conflict, order.estimated());
            rows.add(row);
            if (conflict) {
                overloaded.add(row);
            }
        }
        return new Schedule(rows, overloaded);
    }

    private static LocalDate nextWorkday(LocalDate date) {
        LocalDate current = date;
        while (current.getDayOfWeek() == DayOfWeek.SATURDAY
                || current.getDayOfWeek() == DayOfWeek.SUNDAY) {
            current = current.plusDays(1);
        }
        return current;
    }

    private static String html(LocalDate today, LocalDate end, List<Capacity> capacities,
                               Schedule schedule) {
        StringBuilder html = new StringBuilder("<h1>Terminplanung ").append(today).append(" bis ")
                .append(end).append("</h1><p>Tageskapazität: ")
                .append(capacities.stream().mapToDouble(Capacity::hoursPerDay).sum())
                .append(" h (").append(AgentInputs.html(capacities.stream()
                        .map(c -> c.machine() + " " + c.hoursPerDay() + " h")
                        .reduce((a, b) -> a + ", " + b).orElse(""))).append(")</p>")
                .append("<table><tr><th>Auftrag</th><th>Fällig</th><th>Dauer</th>"
                        + "<th>Eingeplant</th><th>Konflikt</th></tr>");
        for (ScheduleRow row : schedule.rows()) {
            html.append("<tr><td>").append(AgentInputs.html(row.orderId())).append("</td><td>")
                    .append(row.dueDate()).append("</td><td>").append(Math.round(row.hours() * 10) / 10.0)
                    .append(" h").append(row.estimated() ? " (geschätzt)" : "").append("</td><td>")
                    .append(row.scheduledDate() == null ? "nicht im Horizont" : row.scheduledDate())
                    .append("</td><td>").append(row.conflict() ? "ja" : "nein").append("</td></tr>");
        }
        html.append("</table>").append("<p><em>Vorschlag für die Produktionsleitung. Der Agent "
                + "startet keine Maschine, verschiebt keinen Auftrag und bucht keine Kapazität.</em></p>");
        return html.toString();
    }

    /** One production order to schedule. */
    public record WorkOrder(String id, LocalDate dueDate, double hours, boolean estimated) {
    }

    /** Available daily capacity. */
    public record Capacity(String machine, double hoursPerDay) {
    }

    /** One scheduled order. */
    public record ScheduleRow(String orderId, LocalDate scheduledDate, LocalDate dueDate, double hours,
                              boolean conflict, boolean estimated) {
    }

    /** The complete schedule. */
    public record Schedule(List<ScheduleRow> rows, List<ScheduleRow> overloaded) {
    }
}
