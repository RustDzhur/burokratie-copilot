package de.buerokratiecopilot.agents.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.analysis.InsightAgent.BaseMetrics;
import de.buerokratiecopilot.agents.analysis.InsightAgent.Finding;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * Verifies the rules of {@code insight}: thresholds, severities and recommendations over the
 * collected figures, plus the honest skip for a tenant without data and the explicit
 * "nothing to report" success.
 */
class InsightAgentTest {

    private final InsightAgent agent = new InsightAgent(null, new ObjectMapper());

    /** A populated tenant: everything 0 except the named fields. */
    private BaseMetrics metrics() {
        return new BaseMetrics(1, 1, 1, 1, 1, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0,
                0, 0);
    }

    @Test
    void overdueTasksProduceARecommendationWithEscalatingSeverity() {
        List<Finding> findings = agent.evaluate(new BaseMetrics(1, 1, 1, 1, 1,
                3, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0));
        assertEquals(1, findings.size());
        assertEquals("OVERDUE_TASKS", findings.get(0).id());
        assertEquals("warning", findings.get(0).severity());
        assertTrue(findings.get(0).text().contains("3 Aufgaben sind überfällig"));
        assertTrue(findings.get(0).recommendation().contains("priorisieren"));

        List<Finding> critical = agent.evaluate(new BaseMetrics(1, 1, 1, 1, 1,
                12, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0));
        assertEquals("critical", critical.get(0).severity());
    }

    @Test
    void failedDocumentsAreAlwaysCritical() {
        List<Finding> findings = agent.evaluate(new BaseMetrics(1, 1, 1, 1, 1,
                0, 2, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0));
        assertEquals(1, findings.size());
        assertEquals("FAILED_DOCUMENTS", findings.get(0).id());
        assertEquals("critical", findings.get(0).severity());
    }

    @Test
    void receivablesFireOnlyAboveTheThreshold() {
        assertTrue(agent.evaluate(new BaseMetrics(1, 1, 1, 1, 1,
                0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0)).isEmpty());

        List<Finding> warn = agent.evaluate(new BaseMetrics(1, 1, 1, 1, 1,
                0, 0, 0, new BigDecimal("1500"), BigDecimal.ZERO, 0, 0, 0, 0, 0));
        assertEquals("warning", warn.get(0).severity());
        assertTrue(warn.get(0).text().contains("1500 EUR"));

        List<Finding> crit = agent.evaluate(new BaseMetrics(1, 1, 1, 1, 1,
                0, 0, 0, new BigDecimal("12000"), BigDecimal.ZERO, 0, 0, 0, 0, 0));
        assertEquals("critical", crit.get(0).severity());
    }

    @Test
    void unmatchedTransactionsAndPayablesFollowTheirRules() {
        List<Finding> unmatched = agent.evaluate(new BaseMetrics(1, 1, 1, 1, 1,
                0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 5, 0, 0, 0, 0));
        assertEquals("warning", unmatched.get(0).severity());

        List<Finding> many = agent.evaluate(new BaseMetrics(1, 1, 1, 1, 1,
                0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 25, 0, 0, 0, 0));
        assertEquals("critical", many.get(0).severity());

        List<Finding> payables = agent.evaluate(new BaseMetrics(1, 1, 1, 1, 1,
                0, 0, 0, BigDecimal.ZERO, new BigDecimal("2000"), 0, 0, 0, 0, 0));
        assertEquals("OPEN_PAYABLES", payables.get(0).id());
    }

    @Test
    void integrationHealthAndDeadlineLoadAreCovered() {
        List<Finding> findings = agent.evaluate(new BaseMetrics(1, 1, 1, 1, 1,
                0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0, 12, 0, 2, 1));
        assertEquals(3, findings.size());
        assertTrue(findings.stream().anyMatch(finding -> finding.id().equals("INTEGRATION_ERRORS")
                && finding.severity().equals("critical")));
        assertTrue(findings.stream().anyMatch(finding -> finding.id().equals("INTEGRATIONS_STALE")
                && finding.severity().equals("warning")));
        assertTrue(findings.stream().anyMatch(finding -> finding.id().equals("DEADLINE_LOAD")
                && finding.severity().equals("info")));
    }

    @Test
    void healthyMetricsProduceNoFindings() {
        assertTrue(agent.evaluate(metrics()).isEmpty());
    }

    @Test
    void reportSkipsHonestlyWithoutAnyData() {
        BaseMetrics empty = new BaseMetrics(0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 0, 0, 0, 0);
        AgentResult result = agent.buildResult(empty, List.of());

        assertFalse(result.ok());
        assertTrue(result.summary().contains("Keine Daten für eine Auswertung"));
    }

    @Test
    void reportSaysExplicitlyWhenEverythingIsFine() {
        AgentResult result = agent.buildResult(metrics(), List.of());

        assertTrue(result.ok());
        assertEquals("0", result.meta().get("findings"));
        assertTrue(result.summary().contains("Keine Auffälligkeiten"));
        assertTrue(result.produced().isEmpty());
    }

    @Test
    void reportTurnsFindingsIntoProducedEntriesAndJson() throws Exception {
        List<Finding> findings = List.of(
                new Finding("OVERDUE_TASKS", "warning", "3 Aufgaben sind überfällig.",
                        "Überfällige Aufgaben priorisieren und Fälligkeiten neu planen."));
        AgentResult result = agent.buildResult(metrics(), findings);

        assertTrue(result.ok());
        assertEquals("1", result.meta().get("findings"));
        assertEquals(1, result.produced().size());
        assertEquals("insight", result.produced().get(0).kind());
        assertEquals("OVERDUE_TASKS", result.produced().get(0).ref());
        assertTrue(result.summary().contains("1 Auffälligkeit(en)"));

        var detail = new ObjectMapper().readTree(result.meta().get("findingsDetail"));
        assertEquals(1, detail.size());
        assertEquals("OVERDUE_TASKS", detail.get(0).get("id").asText());
        assertTrue(detail.get(0).get("recommendation").asText().contains("priorisieren"));
    }
}
