package de.buerokratiecopilot.agents.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.analysis.AnalysisSeries.Point;
import de.buerokratiecopilot.agents.analysis.AnalysisSeries.Series;
import de.buerokratiecopilot.agents.analysis.ForecastAgent.SeriesForecast;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * Verifies the projection arithmetic of {@code forecast}: the linear fit over the history, the
 * forecast with its band, the minimum point count and the standing caveats.
 */
class ForecastAgentTest {

    private final ForecastAgent agent = new ForecastAgent(null, new ObjectMapper());

    private static final LocalDate START = LocalDate.of(2026, 10, 1);

    private static List<Point> days(double... values) {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            points.add(new Point(START.plusDays(i), values[i]));
        }
        return points;
    }

    @Test
    void projectsAnExactLineWithAZeroBand() {
        // y = 2x + 5 over x = 0..9 — the next two days are 25 and 27, band ±0.
        SeriesForecast forecast = agent.forecastSeries("documents",
                days(5, 7, 9, 11, 13, 15, 17, 19, 21, 23), 2, ForecastAgent.MIN_POINTS);

        assertEquals(10, forecast.points());
        assertEquals(2.0, forecast.slope(), 1e-9);
        assertEquals(1.0, forecast.r2(), 1e-9);
        assertEquals(0.0, forecast.residualSigma(), 1e-9);
        assertEquals(2, forecast.forecast().size());
        assertEquals(25.0, forecast.forecast().get(0).value(), 1e-9);
        assertEquals(25.0, forecast.forecast().get(0).low(), 1e-9);
        assertEquals(25.0, forecast.forecast().get(0).high(), 1e-9);
        assertEquals(27.0, forecast.forecast().get(1).value(), 1e-9);
        assertEquals(START.plusDays(10), forecast.forecast().get(0).day());
    }

    @Test
    void noiseWidensTheBandAroundTheForecast() {
        SeriesForecast forecast = agent.forecastSeries("tasks",
                days(5.1, 6.8, 9.2, 10.9, 13.2, 15.1, 16.9, 19.2, 20.8, 23.1), 3,
                ForecastAgent.MIN_POINTS);

        assertTrue(forecast.residualSigma() > 0, "noise must widen the band");
        assertEquals(3, forecast.forecast().size());
        for (ForecastAgent.ForecastPoint point : forecast.forecast()) {
            assertTrue(point.high() > point.low(), "band must have a width");
            assertTrue(point.value() >= point.low() && point.value() <= point.high());
        }
    }

    @Test
    void tooFewPointsIsNotProjected() {
        assertNull(agent.forecastSeries("bank", days(1, 2, 3, 4), 7, ForecastAgent.MIN_POINTS));
    }

    @Test
    void reportSkipsHonestlyWhenNoSeriesQualifies() {
        List<Series> series = List.of(new Series("documents", days(1, 2, 3)));
        AgentResult result = agent.buildResult(series, 7, 30);

        assertFalse(result.ok());
        assertTrue(result.summary().contains("Zu wenige Datenpunkte"));
    }

    @Test
    void reportCarriesTheForecastBandAndCaveats() throws Exception {
        List<Series> series = List.of(
                new Series("tasks", days(5, 7, 9, 11, 13, 15, 17, 19, 21, 23)));
        AgentResult result = agent.buildResult(series, 7, 30);

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("forecasts"));
        assertEquals("7", result.meta().get("horizonDays"));
        assertEquals("1.0", result.meta().get("bandSigmas"));
        assertTrue(result.summary().contains("tasks: Trend 2.00 pro Tag"));
        assertTrue(result.summary().contains("Vorbehalte"));

        JsonNode root = new ObjectMapper().readTree(result.meta().get("forecast"));
        assertEquals(1, root.size());
        JsonNode tasks = root.get(0);
        assertEquals("tasks", tasks.get("series").asText());
        assertEquals(10, tasks.get("points").asInt());
        assertEquals(2.0, tasks.get("slope").asDouble(), 1e-9);
        assertTrue(tasks.get("rough").asBoolean(), "10 points is a rough sample");
        assertEquals(7, tasks.get("forecast").size());
        // Next 7 days of an exact line: 25, 27, 29, 31, 33, 35, 37.
        assertEquals(25.0, tasks.get("forecast").get(0).get("value").asDouble(), 1e-9);
        assertEquals(37.0, tasks.get("forecast").get(6).get("value").asDouble(), 1e-9);

        assertEquals(1, result.produced().size());
        assertEquals("forecast", result.produced().get(0).kind());
        assertEquals("forecast:tasks", result.produced().get(0).ref());
    }
}
