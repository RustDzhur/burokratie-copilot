package de.buerokratiecopilot.agents.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.analysis.DailyReportAgent.DailyFigures;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * Verifies the report assembly of {@code report-daily} — the arithmetic of what counts as "a
 * day with activity", what lands in the summary/meta and the deterministic produced ref —
 * without a database.
 */
class DailyReportAgentTest {

    private final DailyReportAgent agent = new DailyReportAgent(null, null);

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

    @Test
    void reportsFiguresOfAnActiveDay() {
        DailyFigures figures = new DailyFigures(2, 3, 1, 4, 1, 2, new BigDecimal("320.00"),
                3, new BigDecimal("500.00"), new BigDecimal("30.00"));
        AgentResult result = agent.buildResult(figures, Map.of("expenses30d", "1250"), DAY);

        assertTrue(result.ok());
        assertEquals("2", result.meta().get("documents"));
        assertEquals("3", result.meta().get("tasks"));
        assertEquals("1", result.meta().get("deadlines"));
        assertEquals("4", result.meta().get("overdueTasks"));
        assertEquals("1", result.meta().get("invoicesDue"));
        assertEquals("2", result.meta().get("payments"));
        assertEquals("320", result.meta().get("paymentsAmount"));
        assertEquals("3", result.meta().get("bankTransactions"));
        assertEquals("500", result.meta().get("bankIncoming"));
        assertEquals("30", result.meta().get("bankOutgoing"));
        assertEquals("1250", result.meta().get("expenses30d"));
        assertTrue(result.summary().contains("2 neue Dokumente"));
        assertTrue(result.summary().contains("Ausgaben der letzten 30 Tage: 1250 EUR"));
        assertEquals(1, result.produced().size());
        assertEquals("report", result.produced().get(0).kind());
        assertEquals("daily:2026-10-20", result.produced().get(0).ref());
    }

    @Test
    void skipsHonestlyWhenTheDayHadNoActivity() {
        DailyFigures empty = new DailyFigures(0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, BigDecimal.ZERO);
        AgentResult result = agent.buildResult(empty, Map.of(), DAY);

        assertFalse(result.ok());
        assertTrue(result.summary().contains("Keine Aktivität am 2026-10-20"));
        assertTrue(result.produced().isEmpty());
    }

    @Test
    void reportContextIsOptional() {
        DailyFigures figures = new DailyFigures(1, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0,
                BigDecimal.ZERO, BigDecimal.ZERO);
        AgentResult result = agent.buildResult(figures, Map.of(), DAY);

        assertTrue(result.ok());
        assertFalse(result.meta().containsKey("expenses30d"));
        assertFalse(result.summary().contains("Ausgaben"));
    }

    @Test
    void parsesTheDayInputWithFallback() {
        assertEquals(DAY, AnalysisDates.parseDay("2026-10-20", LocalDate.now()));
        assertEquals(LocalDate.of(2026, 1, 2),
                AnalysisDates.parseDay("2026-01-02", LocalDate.now()));
        // Absent and malformed inputs fall back instead of failing the run.
        LocalDate fallback = LocalDate.of(2026, 6, 6);
        assertEquals(fallback, AnalysisDates.parseDay(null, fallback));
        assertEquals(fallback, AnalysisDates.parseDay("", fallback));
        assertEquals(fallback, AnalysisDates.parseDay("gestern", fallback));
    }
}
