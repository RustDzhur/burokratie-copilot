package de.buerokratiecopilot.agents.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.analysis.MetricCollectAgent.MetricPoint;
import de.buerokratiecopilot.agents.analysis.MetricCollectAgent.SourceMetrics;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * Verifies the unification step of {@code metric-collect} — which sources survive, what the
 * unified set contains and how a tenant without data is reported — without a database.
 */
class MetricCollectAgentTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    private final ObjectMapper mapper = new ObjectMapper();
    private final MetricCollectAgent agent = new MetricCollectAgent(null, mapper);

    @Test
    void unifiesContributingSourcesIntoOneSet() throws Exception {
        Map<String, SourceMetrics> sources = new LinkedHashMap<>();
        sources.put("documents", new SourceMetrics("documents", "Dokumente", List.of(
                new MetricPoint("total", new BigDecimal("7"), "Dokumente"),
                new MetricPoint("failed", new BigDecimal("0"), "Dokumente"))));
        // A source contributing only zeros must be dropped, not reported as "collected".
        sources.put("tasks", new SourceMetrics("tasks", "Aufgaben", List.of(
                new MetricPoint("open", BigDecimal.ZERO, "Aufgaben"))));
        sources.put("invoices", new SourceMetrics("invoices", "Rechnungen", List.of(
                new MetricPoint("receivables", new BigDecimal("500"), "EUR"))));

        AgentResult result = agent.assemble(sources, DAY);

        assertTrue(result.ok());
        assertEquals("2", result.meta().get("sources"));
        assertEquals("3", result.meta().get("metrics"));
        assertTrue(result.summary().contains("3 Kennzahlen aus 2 Systemen"));

        List<Map<String, Object>> unified = mapper.readValue(result.meta().get("metricSet"),
                new TypeReference<List<Map<String, Object>>>() {
                });
        assertEquals(3, unified.size());
        Map<String, Object> receivables = unified.stream()
                .filter(row -> "receivables".equals(row.get("code")))
                .findFirst().orElseThrow();
        assertEquals("invoices", receivables.get("source"));
        assertEquals("Rechnungen", receivables.get("system"));
        assertEquals(500, ((Number) receivables.get("value")).intValue());
        assertEquals("EUR", receivables.get("unit"));
        // The all-zero source is absent from the unified set.
        assertTrue(unified.stream().noneMatch(row -> "tasks".equals(row.get("source"))));
        // One produced entry per contributing source.
        assertEquals(2, result.produced().size());
        assertEquals("metrics", result.produced().get(0).kind());
    }

    @Test
    void skipsHonestlyWhenNothingContributes() {
        Map<String, SourceMetrics> sources = new LinkedHashMap<>();
        sources.put("documents", new SourceMetrics("documents", "Dokumente", List.of(
                new MetricPoint("total", BigDecimal.ZERO, "Dokumente"))));
        sources.put("integrations", new SourceMetrics("integrations", "Verbundene Systeme", List.of(
                new MetricPoint("total", BigDecimal.ZERO, "Integrationen"))));

        AgentResult result = agent.assemble(sources, DAY);

        assertFalse(result.ok());
        assertTrue(result.summary().contains("Keine Daten gesammelt"));
        assertTrue(result.produced().isEmpty());
    }

    @Test
    void skipsHonestlyWhenThereAreNoSourcesAtAll() {
        AgentResult result = agent.assemble(Map.of(), DAY);

        assertFalse(result.ok());
        assertTrue(result.summary().contains("Keine Daten gesammelt"));
    }
}
