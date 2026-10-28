package de.buerokratiecopilot.agents.hospitality;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code hospitality-menu-cost} — Speisekalkulation.
 *
 * <p>Calculates the contribution margin of menu items from their recipes and purchase prices,
 * including waste. Missing purchase prices are a gap, never an estimate: a dish whose cost is
 * unknown is reported as unknown, and no margin is invented for it. The agent suggests a price for
 * items below the target margin, but changes no menu price — that is a decision of the house.</p>
 *
 * <p>Accepted input shape (documented because it is the contract of this agent):</p>
 * <pre>
 * menuItems:      [{"name": "Schnitzel", "price": 18.90, "vatPercent": 19,
 *                   "recipe": [{"ingredient": "Kalb", "quantity": 0.2,
 *                               "purchasePrice": 24.50}]}]
 * recipeLines:    [{"dish": "Schnitzel", "ingredient": "Kalb", "quantity": 0.2}]
 * purchasePrices: {"Kalb": 24.50}
 * </pre>
 */
@Component
public class MenuCostAgent implements Agent {

    static final String ARTIFACT_KIND = "menu-cost";
    static final int MAX_ITEMS = 200;
    private static final BigDecimal DEFAULT_WASTE = new BigDecimal("5");
    private static final BigDecimal DEFAULT_TARGET_MARGIN = new BigDecimal("65");
    private static final BigDecimal DEFAULT_VAT = new BigDecimal("19");

    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public MenuCostAgent(AgentTaskFactory tasks, AgentArtifactStore artifacts) {
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "hospitality-menu-cost";
    }

    public record RecipeLine(String ingredient, BigDecimal quantity, BigDecimal price) {
    }

    public record MenuItem(String name, BigDecimal price, BigDecimal vatPercent,
                           List<RecipeLine> recipe) {
    }

    /** The parsed inputs; {@code notes} lists what could not be attached (not what was priced). */
    public record Input(List<MenuItem> items, Map<String, BigDecimal> purchasePrices,
                        List<String> notes) {
    }

    /** One calculated dish. Numeric values are strings so the artifact stays JSON-stable. */
    public record CostedItem(String name, String price, String netPrice, String cost,
                             String marginPercent, String suggestedPrice, boolean costKnown,
                             boolean lossMaking, boolean belowTarget, List<String> missingPrices) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("price", price);
            map.put("netPrice", netPrice);
            map.put("cost", cost);
            map.put("marginPercent", marginPercent);
            map.put("suggestedPrice", suggestedPrice);
            map.put("costKnown", costKnown);
            map.put("lossMaking", lossMaking);
            map.put("belowTarget", belowTarget);
            map.put("missingPrices", missingPrices);
            return map;
        }
    }

    @Override
    public AgentResult run(AgentContext context) {
        String menuRaw = context.input("menuItems");
        if (AgentInputs.text(menuRaw) == null) {
            return AgentResult.refused("NO_INPUT",
                    "Für die Speisekalkulation werden menuItems benötigt (JSON-Array).");
        }
        Input input = parse(menuRaw, context.input("recipeLines"),
                context.input("purchasePrices"));
        if (input == null) {
            return AgentResult.refused("INVALID_INPUT",
                    "menuItems, recipeLines oder purchasePrices sind kein gültiges JSON in der "
                            + "erwarteten Form.");
        }
        if (input.items().isEmpty()) {
            return AgentResult.refused("NO_INPUT",
                    "Die Speisekarte enthält keine Position.");
        }
        if (input.items().size() > MAX_ITEMS) {
            return AgentResult.refused("TOO_MANY_ITEMS",
                    "Es können höchstens " + MAX_ITEMS + " Positionen auf einmal kalkuliert werden.");
        }
        BigDecimal waste = AgentInputs.decimalInRange(context.input("wastePercent"), DEFAULT_WASTE,
                BigDecimal.ZERO, new BigDecimal("50"));
        BigDecimal target = AgentInputs.decimalInRange(context.input("targetMarginPercent"),
                DEFAULT_TARGET_MARGIN, BigDecimal.ZERO, new BigDecimal("95"));

        List<CostedItem> costed = calculate(input, waste, target);
        List<CostedItem> known = costed.stream().filter(CostedItem::costKnown).toList();
        if (known.isEmpty()) {
            List<String> missing = costed.stream().flatMap(item -> item.missingPrices().stream())
                    .distinct().toList();
            return AgentResult.refused("NO_COST_DATA",
                    "Für keine Position sind Einkaufspreise hinterlegt; es wird nichts geschätzt. "
                            + "Fehlende Preise: " + String.join(", ", missing) + ".",
                    Map.of("missingPrices", String.join(",", missing)));
        }

        List<CostedItem> lossMaking = known.stream().filter(CostedItem::lossMaking).toList();
        List<CostedItem> belowTarget = known.stream().filter(CostedItem::belowTarget).toList();
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("wastePercent", waste.toPlainString());
        payload.put("targetMarginPercent", target.toPlainString());
        payload.put("itemCount", costed.size());
        payload.put("costKnownCount", known.size());
        payload.put("items", costed.stream().map(CostedItem::asMap).toList());
        payload.put("lossMaking", lossMaking.stream().map(CostedItem::name).toList());
        payload.put("belowTarget", belowTarget.stream().map(CostedItem::name).toList());
        payload.put("missingInputs", input.notes());
        payload.put("note", "Der Agent ändert keine Kartenpreise; Preisvorschläge sind Vorschläge.");

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND,
                AgentInputs.sha256(menuRaw + "|" + nullToEmpty(context.input("recipeLines")) + "|"
                        + nullToEmpty(context.input("purchasePrices")) + "|"
                        + waste.toPlainString() + "|" + target.toPlainString())
                        .substring(0, 16));
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("menu-cost", artifact.id().toString(),
                "Speisekalkulation für " + costed.size() + " Position(en), " + lossMaking.size()
                        + " defizitär, " + belowTarget.size() + " unter Zielmarge");

        int created = 0;
        for (CostedItem item : belowTarget.stream().limit(MAX_ITEMS).toList()) {
            String title = "Speisekartenpreis prüfen: " + AgentInputs.truncate(item.name(), 250);
            String description = "Aktueller Preis " + item.price() + " EUR, Netto " + item.netPrice()
                    + " EUR, Wareneinsatz " + item.cost() + " EUR, Marge " + item.marginPercent()
                    + " % (Ziel " + target.toPlainString() + " %). "
                    + (item.suggestedPrice() == null ? "Kein Preisvorschlag möglich."
                            : "Vorschlag: " + item.suggestedPrice() + " EUR.")
                    + " Der Preis wird nicht automatisch geändert."
                    + (item.lossMaking() ? " Achtung: defizitär." : "");
            AgentTaskFactory.Request request = new AgentTaskFactory.Request(title, description,
                    "Der Kartenpreis ist entschieden und die Speisekarte aktualisiert.",
                    AgentInputs.dueAt(today, 7), Task.Priority.NORMAL,
                    List.of("speisekarte", "kalkulation"), null, context.actorUserId());
            var optionalTask = tasks.createIfAbsent(context, request);
            if (optionalTask.isPresent()) {
                AgentTaskFactory.produce(context, optionalTask.get());
                created++;
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("items", String.valueOf(costed.size()));
        meta.put("costKnown", String.valueOf(known.size()));
        meta.put("lossMaking", String.valueOf(lossMaking.size()));
        meta.put("belowTarget", String.valueOf(belowTarget.size()));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("wastePercent", waste.toPlainString());
        meta.put("targetMarginPercent", target.toPlainString());
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Speisekalkulation: " + known.size() + " von " + costed.size()
                + " Position(en) kalkulierbar, " + lossMaking.size() + " defizitär, "
                + belowTarget.size() + " unter der Zielmarge von " + target.toPlainString()
                + " %; " + created + " Preisprüfung(en) angelegt.", context.produced(), meta);
    }

    /** Parses all three JSON inputs; {@code null} means malformed. Visible for tests. */
    public static Input parse(String menuRaw, String linesRaw, String pricesRaw) {
        JsonNode menu = AgentInputs.json(menuRaw);
        if (menu == null || !menu.isArray()) {
            return null;
        }
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        if (AgentInputs.text(pricesRaw) != null) {
            JsonNode priceNode = AgentInputs.json(pricesRaw);
            if (priceNode == null || !priceNode.isObject()) {
                return null;
            }
            priceNode.fields().forEachRemaining(entry -> {
                BigDecimal value = entry.getValue().isNumber() ? entry.getValue().decimalValue()
                        : AgentInputs.decimal(entry.getValue().asText());
                if (value != null) {
                    prices.put(entry.getKey(), value);
                }
            });
        }

        Map<String, List<RecipeLine>> extraLines = new LinkedHashMap<>();
        if (AgentInputs.text(linesRaw) != null) {
            JsonNode lines = AgentInputs.json(linesRaw);
            if (lines == null || !lines.isArray()) {
                return null;
            }
            for (JsonNode line : lines) {
                if (!line.isObject()) {
                    continue;
                }
                String dish = firstText(line, "dish", "item", "menuItem", "name");
                String ingredient = firstText(line, "ingredient", "zutat");
                BigDecimal quantity = firstDecimal(line, "quantity", "menge");
                BigDecimal price = firstDecimal(line, "purchasePrice", "unitPrice", "price");
                if (dish != null && ingredient != null) {
                    extraLines.computeIfAbsent(dish.toLowerCase(Locale.ROOT),
                            ignored -> new ArrayList<>()).add(new RecipeLine(ingredient, quantity,
                            price));
                }
            }
        }

        List<MenuItem> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (JsonNode node : menu) {
            if (!node.isObject()) {
                return null;
            }
            String name = firstText(node, "name", "dish");
            BigDecimal price = firstDecimal(node, "price", "verkaufspreis");
            if (name == null || name.isBlank() || price == null || price.signum() <= 0) {
                return null;
            }
            BigDecimal vat = firstDecimal(node, "vatPercent", "vat", "mwst");
            BigDecimal vatPercent = vat == null ? DEFAULT_VAT : clamp(vat, BigDecimal.ZERO,
                    new BigDecimal("30"));
            List<RecipeLine> recipe = new ArrayList<>();
            JsonNode recipeNode = node.get("recipe");
            if (recipeNode != null && recipeNode.isArray()) {
                for (JsonNode line : recipeNode) {
                    if (!line.isObject()) {
                        continue;
                    }
                    String ingredient = firstText(line, "ingredient", "zutat");
                    if (ingredient == null) {
                        continue;
                    }
                    recipe.add(new RecipeLine(ingredient, firstDecimal(line, "quantity", "menge"),
                            firstDecimal(line, "purchasePrice", "unitPrice", "price")));
                }
            }
            recipe.addAll(extraLines.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()));
            items.add(new MenuItem(name.strip(), price, vatPercent, List.copyOf(recipe)));
        }
        for (String dish : extraLines.keySet()) {
            boolean matched = items.stream()
                    .anyMatch(item -> item.name().equalsIgnoreCase(dish));
            if (!matched) {
                notes.add("Rezepturzeilen ohne passende Position: " + dish);
            }
        }
        return new Input(List.copyOf(items), Map.copyOf(prices), List.copyOf(notes));
    }

    /**
     * Cost, margin and price suggestion per dish. A dish stays {@code costKnown=false} while any
     * recipe line lacks a price or a quantity — the calculation never fills a hole with a guess.
     * Visible for tests.
     */
    public static List<CostedItem> calculate(Input input, BigDecimal wastePercent,
                                             BigDecimal targetMarginPercent) {
        BigDecimal waste = wastePercent == null ? DEFAULT_WASTE : wastePercent;
        BigDecimal target = targetMarginPercent == null ? DEFAULT_TARGET_MARGIN
                : targetMarginPercent;
        BigDecimal wasteFactor = BigDecimal.ONE.add(waste.divide(new BigDecimal("100"), 6,
                RoundingMode.HALF_UP));
        List<CostedItem> costed = new ArrayList<>();
        for (MenuItem item : input.items()) {
            List<String> missing = new ArrayList<>();
            BigDecimal totalCost = BigDecimal.ZERO;
            boolean costKnown = !item.recipe().isEmpty();
            for (RecipeLine line : item.recipe()) {
                BigDecimal price = line.price() != null ? line.price()
                        : lookup(input.purchasePrices(), line.ingredient());
                if (price == null) {
                    missing.add(line.ingredient());
                    costKnown = false;
                }
                if (line.quantity() == null || line.quantity().signum() <= 0) {
                    missing.add(line.ingredient() + " (Menge)");
                    costKnown = false;
                }
                if (price != null && line.quantity() != null && line.quantity().signum() > 0) {
                    totalCost = totalCost.add(price.multiply(line.quantity()));
                }
            }
            BigDecimal vat = item.vatPercent() == null ? DEFAULT_VAT : item.vatPercent();
            BigDecimal netPrice = item.price().divide(BigDecimal.ONE.add(vat.divide(
                    new BigDecimal("100"), 6, RoundingMode.HALF_UP)), 2, RoundingMode.HALF_UP);
            if (!costKnown) {
                costed.add(new CostedItem(item.name(), item.price().toPlainString(),
                        netPrice.toPlainString(), null, null, null, false, false, false,
                        List.copyOf(missing)));
                continue;
            }
            BigDecimal cost = totalCost.multiply(wasteFactor).setScale(2, RoundingMode.HALF_UP);
            BigDecimal margin = netPrice.subtract(cost);
            BigDecimal marginPercent = netPrice.signum() == 0 ? BigDecimal.ZERO
                    : margin.multiply(new BigDecimal("100")).divide(netPrice, 1,
                            RoundingMode.HALF_UP);
            BigDecimal suggested = target.compareTo(new BigDecimal("95")) >= 0 ? null
                    : cost.divide(BigDecimal.ONE.subtract(target.divide(new BigDecimal("100"), 6,
                            RoundingMode.HALF_UP)), 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.ONE.add(vat.divide(new BigDecimal("100"), 6,
                                    RoundingMode.HALF_UP)))
                            .setScale(1, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
            costed.add(new CostedItem(item.name(), item.price().toPlainString(),
                    netPrice.toPlainString(), cost.toPlainString(),
                    marginPercent.toPlainString(), suggested == null ? null
                            : suggested.toPlainString(), true, margin.signum() < 0,
                    marginPercent.compareTo(target) < 0, List.copyOf(missing)));
        }
        return List.copyOf(costed);
    }

    private static BigDecimal lookup(Map<String, BigDecimal> prices, String ingredient) {
        if (ingredient == null) {
            return null;
        }
        BigDecimal exact = prices.get(ingredient);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, BigDecimal> entry : prices.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(ingredient)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) {
            return min;
        }
        return value.compareTo(max) > 0 ? max : value;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = AgentInputs.nodeText(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static BigDecimal firstDecimal(JsonNode node, String... fields) {
        for (String field : fields) {
            BigDecimal value = AgentInputs.nodeDecimal(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
