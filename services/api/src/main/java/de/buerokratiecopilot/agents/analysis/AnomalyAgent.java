package de.buerokratiecopilot.agents.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * {@code anomaly} — Anomalie-Erkennung.
 *
 * <p>Detects days that deviate from the usual course with plain statistics, nothing else:
 * per daily series (documents, tasks, invoices, payments, bank volume) it computes the mean and
 * the sample standard deviation over the window and flags every day whose value lies more than
 * {@code sigma} factor × σ away from the mean. Deterministic, explainable, no external AI.</p>
 *
 * <p>A series needs at least {@value #MIN_POINTS} days of data to be judged; a series with too
 * few points is left out of the report rather than guessed about, and a run where no series
 * qualifies is honestly skipped. A constant series (σ ≈ 0) cannot deviate and is never flagged.
 * Read-only and idempotent: nothing is persisted, refs are deterministic
 * ({@code &lt;series&gt;:&lt;day&gt;}).</p>
 */
@Component
public class AnomalyAgent implements Agent {

    static final int DEFAULT_WINDOW_DAYS = 30;
    static final int MIN_POINTS = 7;
    static final double DEFAULT_SIGMA_FACTOR = 2.0;
    private static final int MAX_PRODUCED = 20;
    private static final double EPSILON = 1e-9;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AnomalyAgent(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "anomaly";
    }

    @Override
    @Transactional(readOnly = true)
    public AgentResult run(AgentContext context) {
        LocalDate today = AnalysisDates.day(context.now());
        int windowDays = AnalysisDates.parseInt(context.input("windowDays"), DEFAULT_WINDOW_DAYS);
        double factor = AnalysisDates.parseDouble(context.input("sigma"), DEFAULT_SIGMA_FACTOR);
        LocalDate from = today.minusDays(windowDays - 1L);
        List<AnalysisSeries.Series> series = AnalysisSeries.collect(jdbc, context.tenantId(),
                context.input("series"), from, today);
        return buildResult(series, factor, windowDays);
    }

    /** One day outside the band: which series, when, and how far (z in units of σ). */
    record Anomaly(String series, LocalDate day, double value, double mean, double sigma, double z) {
    }

    /** The statistics a series was judged by, whether or not it contains anomalies. */
    record SeriesStats(String series, int points, double mean, double sigma, int anomalies) {
    }

    /** The outcome of analysing one series, or {@code null} when it has too few points. */
    record SeriesAnalysis(String series, double mean, double sigma, List<Anomaly> anomalies) {
    }

    /**
     * Mean ± σ analysis of one series — pure and unit-testable. Returns {@code null} when the
     * series has fewer than {@code minPoints} days of data; flags every day with
     * {@code |z| > factor}, with z measured against the sample standard deviation of the whole
     * window.
     */
    SeriesAnalysis analyze(String seriesName, List<AnalysisSeries.Point> points, int minPoints,
                           double factor) {
        if (points.size() < minPoints) {
            return null;
        }
        double[] values = points.stream().mapToDouble(AnalysisSeries.Point::value).toArray();
        double mean = AnalysisMath.mean(values);
        double sigma = AnalysisMath.sampleStdDev(values);
        List<Anomaly> anomalies = new ArrayList<>();
        if (sigma >= EPSILON) {
            for (AnalysisSeries.Point point : points) {
                double z = AnalysisMath.zScore(point.value(), mean, sigma);
                if (Math.abs(z) > factor) {
                    anomalies.add(new Anomaly(seriesName, point.day(), point.value(), mean, sigma, z));
                }
            }
        }
        return new SeriesAnalysis(seriesName, mean, sigma, List.copyOf(anomalies));
    }

    /**
     * Assembles the report from analysed series — pure, unit-testable. A run where no series
     * qualifies is skipped honestly; a qualifying run without anomalies is a success that says
     * so, and every anomaly is reported with its series, day and z-score.
     */
    AgentResult buildResult(List<AnalysisSeries.Series> series, double factor, int windowDays) {
        List<SeriesStats> stats = new ArrayList<>();
        List<Anomaly> anomalies = new ArrayList<>();
        for (AnalysisSeries.Series source : series) {
            SeriesAnalysis analysis = analyze(source.name(), source.points(), MIN_POINTS, factor);
            if (analysis != null) {
                stats.add(new SeriesStats(source.name(), source.points().size(),
                        AnalysisMath.round2(analysis.mean()), AnalysisMath.round2(analysis.sigma()),
                        analysis.anomalies().size()));
                anomalies.addAll(analysis.anomalies());
            }
        }
        if (stats.isEmpty()) {
            return AgentResult.skipped("Zu wenige Datenpunkte für eine Anomalie-Erkennung: keine "
                    + "Reihe hat mindestens " + MIN_POINTS + " Tage mit Daten im Fenster.");
        }

        anomalies.sort(Comparator.comparingDouble((Anomaly anomaly) -> Math.abs(anomaly.z())).reversed());
        List<AgentResult.Produced> produced = anomalies.stream().limit(MAX_PRODUCED)
                .map(anomaly -> new AgentResult.Produced("anomaly",
                        anomaly.series() + ":" + anomaly.day(),
                        String.format(java.util.Locale.ROOT,
                                "%s am %s: %.0f bei Mittel %.1f (z=%.1f)",
                                anomaly.series(), anomaly.day(), anomaly.value(),
                                anomaly.mean(), anomaly.z())))
                .toList();

        String summary = anomalies.isEmpty()
                ? "Keine Anomalien erkannt: alle geprüften Reihen liegen innerhalb von ±" + factor
                        + "σ um ihren Mittelwert."
                : anomalies.size() + " Anomalie(n) erkannt: " + anomalies.stream().limit(3)
                        .map(anomaly -> anomaly.series() + " am " + anomaly.day()
                                + " (z=" + String.format(java.util.Locale.ROOT, "%.1f", anomaly.z()) + ")")
                        .reduce((first, second) -> first + ", " + second).orElse("");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("anomalies", Integer.toString(anomalies.size()));
        meta.put("seriesChecked", Integer.toString(stats.size()));
        meta.put("windowDays", Integer.toString(windowDays));
        meta.put("sigmaFactor", String.valueOf(factor));
        meta.put("seriesStats", toJson(stats.stream().map(stat -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("series", stat.series());
            row.put("points", stat.points());
            row.put("mean", stat.mean());
            row.put("sigma", stat.sigma());
            row.put("anomalies", stat.anomalies());
            return row;
        }).toList()));
        meta.put("anomaliesDetail", toJson(anomalies.stream().limit(MAX_PRODUCED).map(anomaly -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("series", anomaly.series());
            row.put("day", anomaly.day().toString());
            row.put("value", AnalysisMath.round2(anomaly.value()));
            row.put("mean", AnalysisMath.round2(anomaly.mean()));
            row.put("sigma", AnalysisMath.round2(anomaly.sigma()));
            row.put("z", AnalysisMath.round2(anomaly.z()));
            return row;
        }).toList()));
        meta.put("note", "Der letzte Tag des Fensters kann unvollständig sein.");
        return AgentResult.done(summary, produced, meta);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
