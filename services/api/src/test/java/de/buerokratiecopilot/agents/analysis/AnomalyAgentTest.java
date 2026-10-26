package de.buerokratiecopilot.agents.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.analysis.AnomalyAgent.Anomaly;
import de.buerokratiecopilot.agents.analysis.AnomalyAgent.SeriesAnalysis;
import de.buerokratiecopilot.agents.analysis.AnalysisSeries.Point;
import de.buerokratiecopilot.agents.analysis.AnalysisSeries.Series;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * Verifies the statistics of {@code anomaly}: mean ± σ analysis over daily series, the minimum
 * point count, constant series, and the honest skip when no series qualifies.
 */
class AnomalyAgentTest {

    private final AnomalyAgent agent = new AnomalyAgent(null, new ObjectMapper());

    private static final LocalDate START = LocalDate.of(2026, 10, 1);

    private static List<Point> days(double... values) {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            points.add(new Point(START.plusDays(i), values[i]));
        }
        return points;
    }

    @Test
    void flagsTheDayOutsideTheBandAndOnlyThatDay() {
        // Nine quiet days with 10 and one with 30: mean 12, σ = sqrt(360/9) ≈ 6.32,
        // z(30) ≈ 2.85 > 2 — flagged; z(10) ≈ -0.32 — not flagged.
        SeriesAnalysis analysis = agent.analyze("documents",
                days(10, 10, 10, 10, 10, 10, 10, 10, 10, 30), AnomalyAgent.MIN_POINTS, 2.0);

        assertEquals(1, analysis.anomalies().size());
        Anomaly anomaly = analysis.anomalies().get(0);
        assertEquals("documents", anomaly.series());
        assertEquals(START.plusDays(9), anomaly.day());
        assertEquals(30.0, anomaly.value(), 1e-9);
        assertEquals(12.0, anomaly.mean(), 1e-9);
        assertEquals(Math.sqrt(360.0 / 9.0), anomaly.sigma(), 1e-9);
        assertTrue(anomaly.z() > 2.0, "z " + anomaly.z());
    }

    @Test
    void negativeDeviationIsAlsoAnAnomaly() {
        // Ten days with 10 and one with 2: z(2) ≈ -2.85 < -2 — flagged as an outlier down.
        SeriesAnalysis analysis = agent.analyze("tasks",
                days(10, 10, 10, 10, 10, 10, 10, 10, 10, 2), AnomalyAgent.MIN_POINTS, 2.0);

        assertEquals(1, analysis.anomalies().size());
        assertTrue(analysis.anomalies().get(0).z() < -2.0, "z " + analysis.anomalies().get(0).z());
    }

    @Test
    void constantSeriesCannotDeviate() {
        SeriesAnalysis analysis = agent.analyze("bank",
                days(7, 7, 7, 7, 7, 7, 7, 7, 7, 7), AnomalyAgent.MIN_POINTS, 2.0);

        assertEquals(0.0, analysis.sigma(), 1e-9);
        assertTrue(analysis.anomalies().isEmpty());
    }

    @Test
    void tooFewPointsIsNotJudgedAtAll() {
        assertNull(agent.analyze("documents", days(3, 3, 3, 3, 3, 3),
                AnomalyAgent.MIN_POINTS, 2.0));
    }

    @Test
    void reportSkipsHonestlyWhenNoSeriesQualifies() {
        List<Series> series = List.of(new Series("documents", days(1, 2, 3)));
        AgentResult result = agent.buildResult(series, 2.0, 30);

        assertFalse(result.ok());
        assertTrue(result.summary().contains("Zu wenige Datenpunkte"));
    }

    @Test
    void reportListsAnomaliesAndCarriesTheStats() throws Exception {
        List<Series> series = List.of(
                new Series("documents", days(10, 10, 10, 10, 10, 10, 10, 10, 10, 30)));
        AgentResult result = agent.buildResult(series, 2.0, 30);

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("anomalies"));
        assertEquals("1", result.meta().get("seriesChecked"));
        assertEquals("30", result.meta().get("windowDays"));
        assertEquals(1, result.produced().size());
        assertEquals("anomaly", result.produced().get(0).kind());
        assertEquals("documents:2026-10-10", result.produced().get(0).ref());
        assertTrue(result.summary().contains("1 Anomalie(n) erkannt"));
        // The stats and the detail are real JSON, not an error placeholder.
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(1, mapper.readTree(result.meta().get("seriesStats")).size());
        assertEquals(1, mapper.readTree(result.meta().get("anomaliesDetail")).size());
    }

    @Test
    void cleanSeriesIsASuccessThatSaysSo() {
        List<Series> series = List.of(
                new Series("tasks", days(5, 6, 5, 6, 5, 6, 5, 6, 5, 6)));
        AgentResult result = agent.buildResult(series, 2.0, 30);

        assertTrue(result.ok());
        assertEquals("0", result.meta().get("anomalies"));
        assertTrue(result.summary().contains("Keine Anomalien erkannt"));
        assertTrue(result.produced().isEmpty());
    }
}
