package de.buerokratiecopilot.agents.hospitality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.agents.core.AgentTestFixtures;

/** {@code hospitality-menu-cost}: cost from recipes, margins, honest missing prices. */
class MenuCostAgentTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private static final String MENU = """
            [
              {"name": "Schnitzel", "price": 10.00, "vatPercent": 19,
               "recipe": [{"ingredient": "Kalb", "quantity": 0.4, "purchasePrice": 20.00}]},
              {"name": "Beilage", "price": 4.00, "vatPercent": 19,
               "recipe": [{"ingredient": "Kartoffel", "quantity": 0.5, "purchasePrice": 1.00}]}
            ]
            """;

    private AgentTaskFactory tasks;
    private AgentArtifactStore artifacts;
    private MenuCostAgent agent;

    @BeforeEach
    void setUp() {
        tasks = mock(AgentTaskFactory.class);
        artifacts = mock(AgentArtifactStore.class);
        agent = new MenuCostAgent(tasks, artifacts);
    }

    private AgentContext context(Map<String, String> inputs) {
        return new AgentContext(TENANT, USER, "manual", NOW, inputs);
    }

    @Test
    void calculatesTheMarginAndPreparesAPriceReviewForTheWeakItem() {
        when(tasks.createIfAbsent(any(), any()))
                .thenReturn(Optional.of(AgentTestFixtures.task(TENANT, "Preis prüfen")));
        when(artifacts.put(any(), any(), anyString(), anyString(), any()))
                .thenReturn(AgentTestFixtures.artifact("menu-cost"));

        AgentResult result = agent.run(context(Map.of("menuItems", MENU,
                "wastePercent", "0", "targetMarginPercent", "65")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("items", "2")
                .containsEntry("costKnown", "2")
                .containsEntry("lossMaking", "0")
                .containsEntry("belowTarget", "1")
                .containsEntry("tasksCreated", "1");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("menu-cost", "task");
    }

    @Test
    void refusesWhenNoPurchasePriceIsAvailableInsteadOfGuessing() {
        String menu = """
                [{"name": "Schnitzel", "price": 10.00,
                  "recipe": [{"ingredient": "Kalb", "quantity": 0.4}]}]
                """;

        AgentResult result = agent.run(context(Map.of("menuItems", menu)));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "NO_COST_DATA");
        assertThat(result.meta().get("missingPrices")).contains("Kalb");
        verify(artifacts, never()).put(any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesMalformedMenuJson() {
        AgentResult result = agent.run(context(Map.of("menuItems", "kein json")));

        assertThat(result.ok()).isFalse();
        assertThat(result.meta()).containsEntry("reason", "INVALID_INPUT");
    }

    @Test
    void calculationAppliesWasteAndSuggestsAPriceWithinTheTargetMargin() {
        MenuCostAgent.Input input = MenuCostAgent.parse(MENU, null, null);
        assertThat(input).isNotNull();

        List<MenuCostAgent.CostedItem> costed = MenuCostAgent.calculate(input,
                new BigDecimal("5"), new BigDecimal("65"));

        MenuCostAgent.CostedItem schnitzel = costed.getFirst();
        assertThat(schnitzel.costKnown()).isTrue();
        assertThat(schnitzel.cost()).isEqualTo("8.40");
        assertThat(schnitzel.netPrice()).isEqualTo("8.40");
        assertThat(schnitzel.belowTarget()).isTrue();
        assertThat(schnitzel.suggestedPrice()).isNotNull();
        assertThat(new BigDecimal(schnitzel.suggestedPrice()))
                .isGreaterThan(new BigDecimal("10.00"));

        MenuCostAgent.CostedItem beilage = costed.get(1);
        assertThat(beilage.cost()).isEqualTo("0.53");
        assertThat(beilage.belowTarget()).isFalse();
    }

    @Test
    void recipeLinesAndPurchasePricesCanComeFromSeparateInputs() {
        String lines = """
                [{"dish": "Schnitzel", "ingredient": "Kalb", "quantity": 0.4}]
                """;
        MenuCostAgent.Input input = MenuCostAgent.parse("""
                [{"name": "Schnitzel", "price": 10.00}]
                """, lines, "{\"Kalb\": 20.00}");

        assertThat(input).isNotNull();
        List<MenuCostAgent.CostedItem> costed = MenuCostAgent.calculate(input,
                BigDecimal.ZERO, new BigDecimal("65"));
        assertThat(costed.getFirst().costKnown()).isTrue();
        assertThat(costed.getFirst().cost()).isEqualTo("8.00");
    }
}
