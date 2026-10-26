package de.buerokratiecopilot.agents.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentResult.Produced;

/**
 * {@code forecast} — Prognosen.
 *
 * <p>Projects the trend of the daily series (documents, tasks, invoices, payments, bank volume)
 * into the next days with a linear least-squares fit over the window — the simplest honest
 * extrapolation. Every forecast carries a range: the fitted value ± {@value #BAND_SIGMAS} × the
 * residual standard error, so a forecast is never presented as a point without its uncertainty.
 * The summary names the caveats explicitly (linear approximation, no seasonality, no external
 * factors, small samples are rough); there is deliberately no external AI behind any of it.</p>
 *
 * <p>A series needs at least {@value #MIN_POINTS} days of data to be projected; runs where no
 * series qualifies are skipped honestly. Read-only and idempotent: nothing is persisted and the
 * produced refs ({@code forecast:&lt;series&gt;}) are deterministic.</p>
 */
@Component
public class ForecastAgent implements Agent {

    static final int DEFAULT_WINDOW_DAYS = 30;
    static final int DEFAULT_HORIZON_DAYS = 7;
    static final int MIN_POINTS = 5;
    static final double BAND_SIGMAS = 1.0;
    static final int ROUGH_SAMPLE_POINTS = 12;

    private static final String CAVEATS = "Vorbehalte: lineare Näherung über die vorhandenen "
            + "Tageswerte; Saisonalität, Feiertage und externe Einflüsse sind nicht modelliert. "
            + "Das Band entspricht ±1σ der Residuen der Regressionslinie und ist keine Garantie; "
            + "bei weniger als " + ROUGH_SAMPLE_POINTS + " Datenpunkten ist die Prognose nur grob "
            + "orientierend.";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ForecastAgent(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "forecast";
    }

    @Override
    @Transactional(readOnly = true)
    public AgentResult run(AgentContext context) {
        LocalDate today = AnalysisDates.day(context.now());
        int windowDays = AnalysisDates.parseInt(context.input("windowDays"), DEFAULT_WINDOW_DAYS);
        int horizonDays = AnalysisDates.parseInt(context.input("horizonDays"), DEFAULT_HORIZON_DAYS);
        LocalDate from = today.minusDays(windowDays - 1L);
        List<AnalysisSeries.Series> series = AnalysisSeries.collect(jdbc, context.tenantId(),
                context.input("series"), from, today);
        return buildResult(series, horizonDays, windowDays);
    }

    /** One projected day: fitted value plus the band around it. */
    record ForecastPoint(LocalDate day, double value, double low, double high) {
    }

    /** The projection of one series: the fitted line and the forecast with its band. */
    record SeriesForecast(String series, int points, double slope, double r2, double residualSigma,
                          List<ForecastPoint> forecast) {
    }

    /**
     * Linear least-squares projection of one series — pure and unit-testable. Returns
     * {@code null} when the series has fewer than {@code minPoints} days of data; otherwise
     * forecasts the next {@code horizon} days, each with a band of
     * {@value #BAND_SIGMAS} × residual sigma around the fitted value.
     */
    SeriesForecast forecastSeries(String seriesName, List<AnalysisSeries.Point> points,
                                  int horizon, int minPoints) {
        if (points.size() < minPoints) {
            return null;
        }
        double[] x = new double[points.size()];
        double[] y = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            x[i] = i;
            y[i] = points.get(i).value();
        }
        AnalysisMath.Regression regression = AnalysisMath.regression(x, y);
        LocalDate lastDay = points.get(points.size() - 1).day();
        List<ForecastPoint> forecast = new ArrayList<>();
        for (int h = 1; h <= horizon; h++) {
            double value = AnalysisMath.predict(regression, points.size() - 1.0 + h);
            double band = BAND_SIGMAS * regression.residualSigma();
            forecast.add(new ForecastPoint(lastDay.plusDays(h),
                    AnalysisMath.round2(value),
                    AnalysisMath.round2(value - band),
                    AnalysisMath.round2(value + band)));
        }
        return new SeriesForecast(seriesName, points.size(), regression.slope(), regression.r2(),
                regression.residualSigma(), List.copyOf(forecast));
    }

    /**
     * Assembles the report from projected series — pure, unit-testable. Runs where no series
     * qualifies are skipped honestly; every projection is reported with its trend, the forecast
     * band and the standing caveats.
     */
    AgentResult buildResult(List<AnalysisSeries.Series> series, int horizonDays, int windowDays) {
        List<SeriesForecast> forecasts = series.stream()
                .map(source -> forecastSeries(source.name(), source.points(), horizonDays, MIN_POINTS))
                .filter(Objects::nonNull)
                .toList();
        if (forecasts.isEmpty()) {
            return AgentResult.skipped("Zu wenige Datenpunkte für eine Prognose: keine Reihe hat "
                    + "mindestens " + MIN_POINTS + " Tage mit Daten im Fenster.");
        }

        List<Produced> produced = forecasts.stream()
                .map(forecastItem -> new Produced("forecast", "forecast:" + forecastItem.series(),
                        "Prognose für " + forecastItem.series() + " über " + horizonDays + " Tage"))
                .toList();

        StringBuilder summary = new StringBuilder();
        for (SeriesForecast forecastItem : forecasts) {
            double average = forecastItem.forecast().stream()
                    .mapToDouble(ForecastPoint::value).average().orElse(0.0);
            if (!summary.isEmpty()) {
                summary.append(' ');
            }
            summary.append(String.format(java.util.Locale.ROOT,
                    "%s: Trend %.2f pro Tag, nächste %d Tage im Mittel %.2f (Band ±%.2f).",
                    forecastItem.series(), forecastItem.slope(), horizonDays, average,
                    forecastItem.residualSigma()));
        }
        summary.append(' ').append(CAVEATS);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("forecasts", Integer.toString(forecasts.size()));
        meta.put("windowDays", Integer.toString(windowDays));
        meta.put("horizonDays", Integer.toString(horizonDays));
        meta.put("bandSigmas", String.valueOf(BAND_SIGMAS));
        meta.put("forecast", toJson(forecasts.stream().map(forecastItem -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("series", forecastItem.series());
            row.put("points", forecastItem.points());
            row.put("slope", AnalysisMath.round2(forecastItem.slope()));
            row.put("r2", AnalysisMath.round2(forecastItem.r2()));
            row.put("residualSigma", AnalysisMath.round2(forecastItem.residualSigma()));
            row.put("rough", forecastItem.points() < ROUGH_SAMPLE_POINTS);
            row.put("forecast", forecastItem.forecast().stream().map(point -> {
                Map<String, Object> day = new LinkedHashMap<>();
                day.put("day", point.day().toString());
                day.put("value", point.value());
                day.put("low", point.low());
                day.put("high", point.high());
                return day;
            }).toList());
            return row;
        }).toList()));
        return AgentResult.done(summary.toString(), produced, meta);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
