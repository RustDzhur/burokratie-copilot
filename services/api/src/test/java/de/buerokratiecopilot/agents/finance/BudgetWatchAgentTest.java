package de.buerokratiecopilot.agents.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.shared.settings.SystemSetting;
import de.buerokratiecopilot.shared.settings.SystemSettingRepository;

class BudgetWatchAgentTest {

    private JdbcTemplate jdbc;
    private SystemSettingRepository settings;
    private BudgetWatchAgent agent;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        settings = mock(SystemSettingRepository.class);
        agent = new BudgetWatchAgent(jdbc, settings);
    }

    private AgentContext ctx(Map<String, String> inputs) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null, inputs);
    }

    @Test
    void reportsCategoriesOverBudget() {
        SystemSetting budgetSetting = mock(SystemSetting.class);
        when(budgetSetting.valueAsString()).thenReturn("700");
        when(settings.findByTenantIdAndSettingKey(any(), any())).thenReturn(Optional.empty());
        when(settings.findByTenantIdIsNullAndSettingKey(any())).thenReturn(Optional.of(budgetSetting));
        when(jdbc.queryForList(contains("group by 1"), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of(Map.of("category", "it", "spent", new BigDecimal("800.00"))));
        when(jdbc.queryForList(contains("not exists"), any(Object.class), any(Object.class), any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(List.of());

        AgentResult result = agent.run(ctx(Map.of("month", "2026-10")));

        assertTrue(result.ok());
        assertEquals("2026-10", result.meta().get("month"));
        assertEquals("11", result.meta().get("activeBudgets"));
        assertEquals("1", result.meta().get("overBudgets"));
        assertEquals("800.00", result.meta().get("totalSpent"));
        assertEquals(1, result.produced().size());
        assertEquals("it", result.produced().get(0).ref());
        assertEquals("budget-warning", result.produced().get(0).kind());
    }

    @Test
    void noBudgetsConfiguredIsSkipped() {
        when(settings.findByTenantIdAndSettingKey(any(), any())).thenReturn(Optional.empty());
        when(settings.findByTenantIdIsNullAndSettingKey(any())).thenReturn(Optional.empty());

        AgentResult result = agent.run(ctx(Map.of()));

        assertFalse(result.ok());
        assertTrue(result.summary().contains("keine Budgets"));
    }
}
