package de.buerokratiecopilot.agents.ecommerce;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code ecommerce-price-monitor} — Preis-Monitor.
 *
 * <p>The platform has no shop, marketplace or competitor feed table. What it does have is priced
 * line items on incoming and outgoing invoices. This agent therefore compares <strong>the prices
 * that exist in the database</strong>: purchase prices per article across suppliers and over time,
 * and — where an outgoing sale price exists for the same article — the resulting margin.</p>
 *
 * <p>It reports deviations and opens a review task. It changes no shop price and claims no
 * competitor price: the report states explicitly that competitor sources are not connected, so
 * nobody reads the result as a market comparison it is not.</p>
 */
@Component
public class EcommercePriceMonitorAgent implements Agent {

    private static final int MAX_ROWS = 5000;
    private static final int MAX_PRODUCED = 50;
    private static final BigDecimal DEFAULT_MAX_CHANGE = new BigDecimal("10");

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;

    public EcommercePriceMonitorAgent(JdbcTemplate jdbc, AgentTaskFactory tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "ecommerce-price-monitor";
    }

    /** One invoice line, reduced to the facts a price comparison needs. */
    public record PriceRow(String article, String description, String unit, BigDecimal price,
                           String currency, String direction, LocalDate date, String supplier) {
    }

    /** One finding about one article. */
    public record Deviation(String article, String description, String code, String message,
                            BigDecimal minimum, BigDecimal maximum, BigDecimal changePercent) {
    }

    /** The complete result of the deterministic comparison. */
    public record Analysis(int articles, int marginChecked, List<Deviation> deviations) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        BigDecimal marginFloor = decimal(context.input("marginFloorPercent"));
        if (marginFloor == null) {
            return AgentResult.skipped("marginFloorPercent fehlt; ohne Marge lässt sich keine "
                    + "Preisabweichung bewerten.");
        }
        BigDecimal maxChange = decimal(context.input("maxChangePercent"));
        if (maxChange == null) {
            maxChange = DEFAULT_MAX_CHANGE;
        }
        if (maxChange.signum() < 0 || marginFloor.signum() < 0 || marginFloor.compareTo(
                new BigDecimal("100")) > 0) {
            return AgentResult.failed("Die Prozentsätze sind ungültig (marginFloorPercent 0–100, "
                    + "maxChangePercent >= 0).");
        }
        Set<String> productFilter = split(context.input("productIds"));

        List<PriceRow> rows = load(context.tenantId());
        if (productFilter != null) {
            rows = rows.stream()
                    .filter(row -> row.article() != null && productFilter.contains(
                            row.article().toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (rows.isEmpty()) {
            return AgentResult.skipped("Keine Preiszeilen in Rechnungen gefunden"
                    + (productFilter == null ? "" : " (Filter: " + String.join(", ", productFilter)
                            + ")") + "; es gibt nichts zu vergleichen.");
        }

        Analysis analysis = analyse(rows, marginFloor, maxChange);
        if (analysis.articles() == 0) {
            return AgentResult.skipped("Die gefundenen Rechnungszeilen tragen keine "
                    + "Artikelnummer; ein Preisvergleich je Artikel ist nicht möglich.");
        }

        UUID taskId = null;
        boolean taskCreated = false;
        if (!analysis.deviations().isEmpty()) {
            AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                    context.actorUserId(),
                    "E-Commerce: Preisabweichungen prüfen (" + analysis.deviations().size()
                            + " Artikel)", description(analysis),
                    Task.Priority.NORMAL,
                    "Preisabweichung ausserhalb der hinterlegten Grenzen; eine Änderung im Shop "
                            + "erfolgt erst nach menschlicher Freigabe.",
                    context.now().plus(7, ChronoUnit.DAYS),
                    List.of("ecommerce", "price-monitor"));
            taskId = task.taskId();
            taskCreated = task.created();
        }

        for (Deviation deviation : analysis.deviations().stream().limit(MAX_PRODUCED).toList()) {
            context.addProduced("price-deviation", deviation.article(),
                    deviation.code() + ": " + deviation.message());
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("source", "invoice_lines");
        meta.put("articlesChecked", String.valueOf(analysis.articles()));
        meta.put("deviations", String.valueOf(analysis.deviations().size()));
        meta.put("marginChecked", String.valueOf(analysis.marginChecked()));
        meta.put("marginFloorPercent", marginFloor.toPlainString());
        meta.put("maxChangePercent", maxChange.toPlainString());
        meta.put("taskId", taskId == null ? "" : taskId.toString());
        meta.put("taskCreated", String.valueOf(taskCreated));
        meta.put("limitation", "Wettbewerbspreise und Shoppreise sind nicht angebunden; "
                + "verglichen werden Einkaufspreise aus Rechnungen und, falls vorhanden, "
                + "Verkaufspreise aus Ausgangsrechnungen.");

        if (analysis.deviations().isEmpty()) {
            return AgentResult.done("Preis-Monitor: " + analysis.articles()
                    + " Artikel geprüft, keine Abweichung über " + maxChange.toPlainString()
                    + " %.", context.produced(), meta);
        }
        return AgentResult.done("Preis-Monitor: " + analysis.articles() + " Artikel geprüft, "
                + analysis.deviations().size() + " Abweichung(en) gefunden; "
                + analysis.marginChecked() + " Marge(n) berechnet. Keine Shop-Preise geändert.",
                context.produced(), meta);
    }

    private List<PriceRow> load(UUID tenantId) {
        List<Map<String, Object>> raw = jdbc.queryForList("""
                select il.article_number, il.description, il.unit, il.unit_price,
                       i.currency, i.direction, i.issue_date, s.name as supplier_name
                from invoice_lines il
                join invoices i on i.id = il.invoice_id and i.tenant_id = il.tenant_id
                left join suppliers s on s.id = i.supplier_id
                where il.tenant_id = ? and i.deleted_at is null
                  and il.article_number is not null and il.unit_price is not null
                order by il.article_number, i.issue_date nulls last
                limit ?
                """, tenantId, MAX_ROWS);
        List<PriceRow> rows = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            rows.add(new PriceRow(string(row.get("article_number")),
                    string(row.get("description")), string(row.get("unit")),
                    decimal(row.get("unit_price")), string(row.get("currency")),
                    string(row.get("direction")), date(row.get("issue_date")),
                    string(row.get("supplier_name"))));
        }
        return rows;
    }

    /**
     * Deterministic comparison. Visible for tests: purchase prices of one article are compared
     * across suppliers and across time; a margin is calculated only when both a purchase and a
     * sale price exist.
     */
    public static Analysis analyse(List<PriceRow> rows, BigDecimal marginFloor,
                                   BigDecimal maxChange) {
        Map<String, List<PriceRow>> byArticle = new LinkedHashMap<>();
        for (PriceRow row : rows) {
            if (row.article() == null || row.article().isBlank() || row.price() == null) {
                continue;
            }
            byArticle.computeIfAbsent(row.article().strip(), ignored -> new ArrayList<>()).add(row);
        }

        List<Deviation> deviations = new ArrayList<>();
        int marginChecked = 0;
        for (Map.Entry<String, List<PriceRow>> entry : byArticle.entrySet()) {
            String article = entry.getKey();
            List<PriceRow> incoming = entry.getValue().stream()
                    .filter(row -> "INCOMING".equalsIgnoreCase(row.direction()))
                    .sorted(Comparator.comparing(PriceRow::date,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            List<PriceRow> outgoing = entry.getValue().stream()
                    .filter(row -> "OUTGOING".equalsIgnoreCase(row.direction()))
                    .sorted(Comparator.comparing(PriceRow::date,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            if (incoming.isEmpty()) {
                continue;
            }
            String description = firstDescription(entry.getValue());
            addPriceSpread(article, description, incoming, maxChange, deviations);
            addOwnPriceChange(article, description, incoming, maxChange, deviations);

            PriceRow cheapestPurchase = incoming.stream()
                    .min(Comparator.comparing(PriceRow::price)).orElse(null);
            PriceRow latestSale = outgoing.isEmpty() ? null : outgoing.getLast();
            if (cheapestPurchase != null && latestSale != null
                    && latestSale.price().signum() > 0) {
                marginChecked++;
                BigDecimal margin = latestSale.price().subtract(cheapestPurchase.price())
                        .multiply(new BigDecimal("100"))
                        .divide(latestSale.price(), 2, RoundingMode.HALF_UP);
                if (margin.compareTo(marginFloor) < 0) {
                    deviations.add(new Deviation(article, description, "MARGIN_BELOW_FLOOR",
                            "Marge " + margin.toPlainString() + " % liegt unter der Untergrenze "
                                    + marginFloor.toPlainString() + " % (Einkauf "
                                    + cheapestPurchase.price().toPlainString() + ", Verkauf "
                                    + latestSale.price().toPlainString() + ")",
                            cheapestPurchase.price(), latestSale.price(), margin));
                }
            }
        }
        return new Analysis(byArticle.size(), marginChecked, List.copyOf(deviations));
    }

    private static void addPriceSpread(String article, String description, List<PriceRow> incoming,
                                       BigDecimal maxChange, List<Deviation> deviations) {
        PriceRow min = incoming.stream().min(Comparator.comparing(PriceRow::price)).orElse(null);
        PriceRow max = incoming.stream().max(Comparator.comparing(PriceRow::price)).orElse(null);
        if (min == null || max == null || min.price().signum() <= 0
                || min.price().compareTo(max.price()) == 0) {
            return;
        }
        BigDecimal spread = max.price().subtract(min.price()).multiply(new BigDecimal("100"))
                .divide(min.price(), 2, RoundingMode.HALF_UP);
        if (spread.compareTo(maxChange) > 0) {
            deviations.add(new Deviation(article, description, "SUPPLIER_SPREAD",
                    "Einkaufspreise liegen " + spread.toPlainString() + " % auseinander ("
                            + min.price().toPlainString() + " bis " + max.price().toPlainString()
                            + ", " + distinctSuppliers(incoming) + " Lieferanten)",
                    min.price(), max.price(), spread));
        }
    }

    private static void addOwnPriceChange(String article, String description,
                                          List<PriceRow> incoming, BigDecimal maxChange,
                                          List<Deviation> deviations) {
        Map<String, List<PriceRow>> bySupplier = new LinkedHashMap<>();
        for (PriceRow row : incoming) {
            String key = row.supplier() == null || row.supplier().isBlank()
                    ? "unbekannt" : row.supplier().strip();
            bySupplier.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        for (Map.Entry<String, List<PriceRow>> entry : bySupplier.entrySet()) {
            List<PriceRow> prices = entry.getValue();
            if (prices.size() < 2) {
                continue;
            }
            PriceRow before = prices.get(prices.size() - 2);
            PriceRow after = prices.getLast();
            if (before.price().signum() <= 0) {
                continue;
            }
            BigDecimal change = after.price().subtract(before.price())
                    .multiply(new BigDecimal("100"))
                    .divide(before.price(), 2, RoundingMode.HALF_UP);
            if (change.abs().compareTo(maxChange) > 0) {
                deviations.add(new Deviation(article, description, "PRICE_CHANGE",
                        "Preis bei " + entry.getKey() + " änderte sich um " + change.toPlainString()
                                + " % (" + before.price().toPlainString() + " → "
                                + after.price().toPlainString() + ")",
                        before.price(), after.price(), change));
            }
        }
    }

    private static int distinctSuppliers(List<PriceRow> rows) {
        Set<String> suppliers = new LinkedHashSet<>();
        for (PriceRow row : rows) {
            suppliers.add(row.supplier() == null || row.supplier().isBlank() ? "unbekannt"
                    : row.supplier());
        }
        return suppliers.size();
    }

    private static String firstDescription(List<PriceRow> rows) {
        return rows.stream().map(PriceRow::description).filter(value -> value != null
                && !value.isBlank()).findFirst().orElse("");
    }

    private static String description(Analysis analysis) {
        StringBuilder text = new StringBuilder(
                "Der Preis-Monitor hat folgende Auffälligkeiten in Rechnungspreisen gefunden. "
                        + "Es wurden keine Shop-Preise geändert.\n\n");
        for (Deviation deviation : analysis.deviations().stream().limit(MAX_PRODUCED).toList()) {
            text.append("- ").append(deviation.article()).append(": ")
                    .append(deviation.message()).append('\n');
        }
        return text.toString();
    }

    private static Set<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                values.add(part.strip().toLowerCase(Locale.ROOT));
            }
        }
        return values.isEmpty() ? null : values;
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.strip().replace(',', '.'));
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal decimal(Object raw) {
        if (raw instanceof BigDecimal value) {
            return value;
        }
        return raw == null ? null : decimal(String.valueOf(raw));
    }

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static LocalDate date(Object raw) {
        if (raw instanceof LocalDate value) {
            return value;
        }
        if (raw instanceof java.sql.Date value) {
            return value.toLocalDate();
        }
        if (raw instanceof java.util.Date value) {
            return value.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(raw));
        }
        catch (java.time.DateTimeException ex) {
            return null;
        }
    }
}
