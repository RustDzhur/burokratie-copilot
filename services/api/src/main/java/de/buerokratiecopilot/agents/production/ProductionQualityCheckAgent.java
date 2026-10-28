package de.buerokratiecopilot.agents.production;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code production-quality-check} — Qualitätsprüfung.
 *
 * <p>Compares the measurements of one work order against the supplied tolerances. A measurement
 * without a tolerance is reported as not assessable — the agent does not assume one. Valve decisions
 * are not made: the result is a list of deviations and a task for the quality team.</p>
 */
@Component
public class ProductionQualityCheckAgent implements Agent {

    private static final int MAX_MEASUREMENTS = 1000;
    private static final int MAX_PRODUCED = 50;

    private final AgentTaskFactory tasks;
    private final ObjectMapper mapper;

    public ProductionQualityCheckAgent(AgentTaskFactory tasks, ObjectMapper mapper) {
        this.tasks = tasks;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "production-quality-check";
    }

    public record Measurement(String name, BigDecimal value, BigDecimal min, BigDecimal max,
                              String unit) {
    }

    public record Finding(String code, String measurement, String message) {
    }

    public record Analysis(int measurements, int assessed, List<Finding> findings) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        String workOrderId = clean(context.input("workOrderId"));
        if (workOrderId == null) {
            return AgentResult.skipped("workOrderId fehlt; ohne Auftrag keine Qualitätsprüfung.");
        }
        List<Measurement> measurements = parseMeasurements(context.input("measurements"),
                context.input("tolerances"));
        if (measurements.isEmpty()) {
            return AgentResult.skipped("measurements fehlt oder enthält keine Messwerte; es gibt "
                    + "nichts zu prüfen.");
        }
        Set<String> defectCodes = split(context.input("defectCodes"));
        Analysis analysis = analyse(measurements, defectCodes);
        if (analysis.assessed() == 0) {
            return AgentResult.skipped("Keine Messung hat eine Toleranz; ohne Toleranz lässt sich "
                    + "keine Abweichung feststellen (es wird keine angenommen).");
        }

        if (analysis.findings().isEmpty()) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("workOrderId", workOrderId);
            meta.put("measurements", String.valueOf(analysis.measurements()));
            meta.put("assessed", String.valueOf(analysis.assessed()));
            meta.put("deviations", "0");
            meta.put("taskId", "");
            meta.put("note", "Keine Freigabeentscheidung: der Agent dokumentiert nur die Prüfung.");
            return AgentResult.done("Qualitätsprüfung " + workOrderId + ": "
                    + analysis.assessed() + " Messung(en) innerhalb der Toleranz, keine "
                    + "Abweichung.", List.of(), meta);
        }

        String title = "Qualitätsprüfung: " + workOrderId;
        AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                context.actorUserId(), title, description(workOrderId, analysis),
                Task.Priority.HIGH,
                "Messwert ausserhalb der Toleranz; Nacharbeit und Freigabe sind eine "
                        + "menschliche Entscheidung.",
                context.now().plus(2, ChronoUnit.DAYS),
                List.of("production", "quality", workOrderId));

        for (Finding finding : analysis.findings().stream().limit(MAX_PRODUCED).toList()) {
            context.addProduced("quality-finding", finding.measurement(),
                    finding.code() + ": " + finding.message());
        }
        context.addProduced("task", task.taskId().toString(), title);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("workOrderId", workOrderId);
        meta.put("measurements", String.valueOf(analysis.measurements()));
        meta.put("assessed", String.valueOf(analysis.assessed()));
        meta.put("deviations", String.valueOf(analysis.findings().stream()
                .filter(finding -> "DEVIATION".equals(finding.code())).count()));
        meta.put("notAssessable", String.valueOf(analysis.findings().stream()
                .filter(finding -> "NOT_ASSESSABLE".equals(finding.code())).count()));
        meta.put("defectCodes", String.valueOf(defectCodes.size()));
        meta.put("taskId", task.taskId().toString());
        meta.put("taskCreated", String.valueOf(task.created()));
        meta.put("limitation", "Der Agent trifft keine Freigabeentscheidung für den Versand. "
                + "Messwerte ohne Toleranz werden als nicht bewertbar ausgewiesen.");

        return AgentResult.done("Qualitätsprüfung " + workOrderId + ": " + analysis.assessed()
                        + " von " + analysis.measurements() + " Messung(en) geprüft, "
                        + analysis.findings().size() + " Auffälligkeit(en). Keine "
                        + "Freigabeentscheidung.", context.produced(), meta);
    }

    /** Compares values to tolerances. Visible for tests. */
    public static Analysis analyse(List<Measurement> measurements, Set<String> defectCodes) {
        List<Finding> findings = new ArrayList<>();
        int assessed = 0;
        for (Measurement measurement : measurements) {
            String name = measurement.name() == null ? "unbenannt" : measurement.name();
            if (measurement.value() == null) {
                findings.add(new Finding("INVALID_VALUE", name,
                        "Messwert ist nicht lesbar oder fehlt"));
                continue;
            }
            if (measurement.min() == null && measurement.max() == null) {
                findings.add(new Finding("NOT_ASSESSABLE", name,
                        "keine Toleranz hinterlegt; der Wert " + measurement.value().toPlainString()
                                + (measurement.unit() == null ? "" : " " + measurement.unit())
                                + " wird nicht bewertet"));
                continue;
            }
            assessed++;
            if (measurement.min() != null && measurement.value().compareTo(measurement.min()) < 0) {
                findings.add(new Finding("DEVIATION", name,
                        "Wert " + measurement.value().toPlainString()
                                + (measurement.unit() == null ? "" : " " + measurement.unit())
                                + " liegt unter der Untergrenze " + measurement.min().toPlainString()));
            }
            if (measurement.max() != null && measurement.value().compareTo(measurement.max()) > 0) {
                findings.add(new Finding("DEVIATION", name,
                        "Wert " + measurement.value().toPlainString()
                                + (measurement.unit() == null ? "" : " " + measurement.unit())
                                + " liegt über der Obergrenze " + measurement.max().toPlainString()));
            }
        }
        for (String defect : defectCodes) {
            findings.add(new Finding("DEFECT_CODE", defect, "gemeldeter Fehlercode " + defect));
        }
        return new Analysis(measurements.size(), assessed, List.copyOf(findings));
    }

    private List<Measurement> parseMeasurements(String measurementsJson, String tolerancesJson) {
        if (measurementsJson == null || measurementsJson.isBlank()) {
            return List.of();
        }
        Map<String, BigDecimal[]> tolerances = parseTolerances(tolerancesJson);
        List<Measurement> result = new ArrayList<>();
        try {
            List<Map<String, Object>> rows = mapper.readValue(measurementsJson,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            for (Map<String, Object> row : rows.stream().limit(MAX_MEASUREMENTS).toList()) {
                String name = clean(string(value(row, "name", "parameter", "measurement", "merkmal")));
                BigDecimal value = decimal(value(row, "value", "wert", "measuredValue"));
                BigDecimal min = decimal(value(row, "min", "minimum", "lower", "lowerLimit"));
                BigDecimal max = decimal(value(row, "max", "maximum", "upper", "upperLimit"));
                BigDecimal[] tolerance = name == null ? null : tolerances.get(name.toLowerCase(
                        Locale.ROOT));
                if (tolerance != null) {
                    if (min == null) {
                        min = tolerance[0];
                    }
                    if (max == null) {
                        max = tolerance[1];
                    }
                }
                result.add(new Measurement(name, value, min, max,
                        clean(string(value(row, "unit", "einheit")))));
            }
        }
        catch (Exception ex) {
            // Try the map form: {"measurement": 12.3, "other": 4.5}
            try {
                Map<String, Object> rows = mapper.readValue(measurementsJson,
                        new TypeReference<Map<String, Object>>() {
                        });
                for (Map.Entry<String, Object> entry : rows.entrySet()) {
                    BigDecimal value = decimal(entry.getValue());
                    BigDecimal[] tolerance = tolerances.get(entry.getKey().toLowerCase(
                            Locale.ROOT));
                    result.add(new Measurement(entry.getKey(), value,
                            tolerance == null ? null : tolerance[0],
                            tolerance == null ? null : tolerance[1], null));
                }
            }
            catch (Exception ignored) {
                return List.of();
            }
        }
        return result;
    }

    private Map<String, BigDecimal[]> parseTolerances(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> rows = mapper.readValue(raw,
                    new TypeReference<Map<String, Object>>() {
                    });
            Map<String, BigDecimal[]> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : rows.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> map) {
                    BigDecimal min = decimal(map.get("min") != null ? map.get("min")
                            : map.get("lower"));
                    BigDecimal max = decimal(map.get("max") != null ? map.get("max")
                            : map.get("upper"));
                    result.put(entry.getKey().toLowerCase(Locale.ROOT),
                            new BigDecimal[]{min, max});
                }
                else if (entry.getValue() instanceof List<?> list && list.size() >= 2) {
                    result.put(entry.getKey().toLowerCase(Locale.ROOT),
                            new BigDecimal[]{decimal(list.get(0)), decimal(list.get(1))});
                }
            }
            return result;
        }
        catch (Exception ex) {
            return Map.of();
        }
    }

    private static String description(String workOrderId, Analysis analysis) {
        StringBuilder text = new StringBuilder();
        text.append("Qualitätsprüfung ").append(workOrderId).append("\n")
                .append("Messungen: ").append(analysis.measurements()).append('\n')
                .append("Bewertet: ").append(analysis.assessed()).append("\n\n")
                .append("Abweichungen und Auffälligkeiten:\n");
        for (Finding finding : analysis.findings().stream().limit(MAX_PRODUCED).toList()) {
            text.append("- [").append(finding.code()).append("] ")
                    .append(finding.measurement()).append(": ").append(finding.message())
                    .append('\n');
        }
        text.append("\nBitte Nacharbeit und Freigabe entscheiden. Der Agent trifft keine ")
                .append("Freigabeentscheidung für den Versand.");
        return text.toString();
    }

    private static Object value(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Set<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                values.add(part.strip());
            }
        }
        return Set.copyOf(values);
    }

    private static BigDecimal decimal(Object raw) {
        if (raw instanceof BigDecimal value) {
            return value;
        }
        if (raw instanceof Number value) {
            return new BigDecimal(value.toString());
        }
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(raw).strip().replace(',', '.'));
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
