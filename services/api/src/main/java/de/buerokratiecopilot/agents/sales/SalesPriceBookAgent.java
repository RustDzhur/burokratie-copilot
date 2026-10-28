package de.buerokratiecopilot.agents.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
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
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code sales-price-book} — Preisbuch-Pflege.
 *
 * <p>There is no price-list table. The sales prices the platform actually charged are on outgoing
 * invoice lines, and that is what the agent builds the price book from: latest price per article,
 * whether it was confirmed after the effective date, and whether the article was sold at materially
 * different prices.</p>
 *
 * <p>The result is a CSV artifact plus a review task. No price is changed anywhere — the point is
 * to show the price book that exists, not to declare the one that should.</p>
 */
@Component
public class SalesPriceBookAgent implements Agent {

    private static final int MAX_ROWS = 10000;
    private static final int MAX_PRODUCED = 50;
    private static final BigDecimal INCONSISTENCY_PERCENT = new BigDecimal("10");
    private static final int STALE_DAYS = 365;

    private final JdbcTemplate jdbc;
    private final AgentArtifactStore artifacts;
    private final AgentTaskFactory tasks;

    public SalesPriceBookAgent(JdbcTemplate jdbc, AgentArtifactStore artifacts,
                               AgentTaskFactory tasks) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "sales-price-book";
    }

    /** One sold article reduced to the price facts. */
    public record PriceRow(String article, String description, String unit, BigDecimal price,
                           String currency, LocalDate date) {
    }

    /** The latest price per article and the two reasons to look at it. */
    public record Entry(String article, String description, String unit, BigDecimal price,
                        String currency, LocalDate lastSold, boolean unconfirmed,
                        boolean stale, boolean inconsistent, BigDecimal minimum, BigDecimal maximum) {
    }

    public record Analysis(int articles, List<Entry> entries) {

        public long unconfirmed() {
            return entries.stream().filter(Entry::unconfirmed).count();
        }

        public long stale() {
            return entries.stream().filter(Entry::stale).count();
        }

        public long inconsistent() {
            return entries.stream().filter(Entry::inconsistent).count();
        }
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate effectiveFrom;
        try {
            effectiveFrom = LocalDate.parse(require(context.input("effectiveFrom")));
        }
        catch (RuntimeException ex) {
            return AgentResult.skipped("effectiveFrom im Format YYYY-MM-DD fehlt; ohne "
                    + "Gültigkeitsdatum gibt es kein Preisbuch.");
        }
        String currency = clean(context.input("currency"));
        currency = currency == null ? "EUR" : currency.toUpperCase(Locale.ROOT);
        String priceListId = clean(context.input("priceListId"));
        if (priceListId != null) {
            try {
                UUID.fromString(priceListId);
            }
            catch (IllegalArgumentException ex) {
                return AgentResult.failed("priceListId ist keine gültige UUID.");
            }
        }
        BigDecimal marginFloor = decimal(context.input("marginFloorPercent"));
        if (marginFloor != null && (marginFloor.signum() < 0
                || marginFloor.compareTo(new BigDecimal("100")) > 0)) {
            return AgentResult.failed("marginFloorPercent muss zwischen 0 und 100 liegen.");
        }
        Set<String> categories = split(context.input("categories"));

        List<Map<String, Object>> raw = jdbc.queryForList("""
                select il.article_number, il.description, il.unit, il.unit_price,
                       i.currency, i.issue_date
                from invoice_lines il
                join invoices i on i.id = il.invoice_id and i.tenant_id = il.tenant_id
                where il.tenant_id = ? and i.deleted_at is null and i.direction = 'OUTGOING'
                  and il.unit_price is not null
                order by il.article_number nulls last, i.issue_date nulls last
                limit ?
                """, context.tenantId(), MAX_ROWS);

        List<PriceRow> rows = new ArrayList<>();
        int otherCurrency = 0;
        for (Map<String, Object> row : raw) {
            String description = clean(string(row.get("description")));
            if (!matchesCategories(description, categories)) {
                continue;
            }
            String rowCurrency = clean(string(row.get("currency")));
            if (rowCurrency != null && !rowCurrency.equalsIgnoreCase(currency)) {
                otherCurrency++;
                continue;
            }
            rows.add(new PriceRow(clean(string(row.get("article_number"))), description,
                    clean(string(row.get("unit"))), decimal(row.get("unit_price")), currency,
                    date(row.get("issue_date"))));
        }
        if (rows.isEmpty()) {
            return AgentResult.skipped("Keine Verkaufspreise in Ausgangsrechnungen für Währung "
                    + currency + (categories == null ? "" : " und Kategorien "
                            + String.join(", ", categories)) + " gefunden; ein Preisbuch lässt "
                    + "sich daraus nicht bauen.");
        }

        LocalDate staleBefore = LocalDate.ofInstant(context.now(), ZoneId.of("Europe/Berlin"))
                .minusDays(STALE_DAYS);
        Analysis analysis = analyse(rows, effectiveFrom, staleBefore);
        String content = toCsv(analysis.entries());
        String sourceKey = "price-book:" + effectiveFrom + ":" + currency + ":"
                + (categories == null ? "all" : String.join("+", categories)) + ":"
                + hash(content);
        AgentArtifactStore.Artifact artifact;
        try {
            artifact = artifacts.store(context.tenantId(), context.actorUserId(), sourceKey,
                    "Preisbuch " + effectiveFrom, "preisbuch-" + effectiveFrom + ".csv",
                    "text/csv", "TXT", content);
        }
        catch (RuntimeException ex) {
            return AgentResult.failed("Das Preisbuch konnte nicht gespeichert werden: "
                    + ex.getMessage());
        }

        UUID taskId = null;
        boolean taskCreated = false;
        if (analysis.unconfirmed() + analysis.stale() + analysis.inconsistent() > 0) {
            AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                    context.actorUserId(), "Preisbuch prüfen: " + effectiveFrom,
                    description(analysis, effectiveFrom, currency, artifact, marginFloor),
                    Task.Priority.NORMAL,
                    "Preispositionen ohne Bestätigung nach dem Gültigkeitsdatum oder mit "
                            + "abweichenden Verkaufspreisen; Änderungen nur nach Freigabe.",
                    context.now().plus(7, ChronoUnit.DAYS),
                    List.of("sales", "price-book"));
            taskId = task.taskId();
            taskCreated = task.created();
        }

        for (Entry entry : analysis.entries().stream().limit(MAX_PRODUCED).toList()) {
            if (entry.unconfirmed() || entry.stale() || entry.inconsistent()) {
                context.addProduced("price-book-finding", entry.article() == null
                                ? entry.description() : entry.article(),
                        findingText(entry));
            }
        }
        context.addProduced("price-book", artifact.storageKey(),
                analysis.articles() + " Artikel, Stand " + effectiveFrom);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("articles", String.valueOf(analysis.articles()));
        meta.put("unconfirmed", String.valueOf(analysis.unconfirmed()));
        meta.put("stale", String.valueOf(analysis.stale()));
        meta.put("inconsistent", String.valueOf(analysis.inconsistent()));
        meta.put("effectiveFrom", effectiveFrom.toString());
        meta.put("currency", currency);
        meta.put("otherCurrencySkipped", String.valueOf(otherCurrency));
        meta.put("storageKey", artifact.storageKey());
        meta.put("artifactCreated", String.valueOf(artifact.created()));
        meta.put("taskId", taskId == null ? "" : taskId.toString());
        meta.put("taskCreated", String.valueOf(taskCreated));
        meta.put("marginFloorPercent", marginFloor == null ? "" : marginFloor.toPlainString());
        meta.put("limitation", "Keine Preisliste-/Staffel-Tabelle angebunden; Grundlage sind "
                + "Verkaufspreise aus Ausgangsrechnungen. Eine Margenprüfung ist ohne "
                + "Einkaufskosten je Artikel nicht möglich.");

        String summary = "Preisbuch " + effectiveFrom + ": " + analysis.articles()
                + " Artikel aus Verkaufspreisen, " + analysis.unconfirmed()
                + " ohne Bestätigung nach dem Gültigkeitsdatum, " + analysis.stale()
                + " älter als " + STALE_DAYS + " Tage, " + analysis.inconsistent()
                + " mit Preisabweichungen. Keine Preise geändert.";
        return AgentResult.done(summary, context.produced(), meta);
    }

    /** Builds the latest-price view. Visible for tests. */
    public static Analysis analyse(List<PriceRow> rows, LocalDate effectiveFrom,
                                   LocalDate staleBefore) {
        Map<String, List<PriceRow>> byArticle = new LinkedHashMap<>();
        for (PriceRow row : rows) {
            String key = row.article() != null ? "A:" + row.article()
                    : "D:" + (row.description() == null ? "unbekannt" : row.description());
            byArticle.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<Entry> entries = new ArrayList<>();
        for (List<PriceRow> articleRows : byArticle.values()) {
            List<PriceRow> priced = articleRows.stream().filter(row -> row.price() != null)
                    .sorted(Comparator.comparing(PriceRow::date,
                            Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
            if (priced.isEmpty()) {
                continue;
            }
            PriceRow latest = priced.getLast();
            BigDecimal minimum = priced.stream().map(PriceRow::price)
                    .min(Comparator.naturalOrder()).orElse(latest.price());
            BigDecimal maximum = priced.stream().map(PriceRow::price)
                    .max(Comparator.naturalOrder()).orElse(latest.price());
            BigDecimal spread = minimum.signum() == 0 ? BigDecimal.ZERO
                    : maximum.subtract(minimum).multiply(new BigDecimal("100"))
                            .divide(minimum, 2, RoundingMode.HALF_UP);
            boolean inconsistent = spread.compareTo(INCONSISTENCY_PERCENT) > 0;
            boolean unconfirmed = latest.date() == null || latest.date().isBefore(effectiveFrom);
            boolean stale = latest.date() == null || latest.date().isBefore(staleBefore);
            entries.add(new Entry(latest.article(), latest.description(), latest.unit(),
                    latest.price(), latest.currency(), latest.date(), unconfirmed, stale,
                    inconsistent, minimum, maximum));
        }
        return new Analysis(entries.size(), List.copyOf(entries));
    }

    private static String findingText(Entry entry) {
        List<String> parts = new ArrayList<>();
        if (entry.unconfirmed()) {
            parts.add("kein Preis nach " + (entry.lastSold() == null ? "unbekannt"
                    : entry.lastSold()));
        }
        if (entry.stale()) {
            parts.add("letzter Verkauf " + entry.lastSold());
        }
        if (entry.inconsistent()) {
            parts.add("Preise " + entry.minimum().toPlainString() + " bis "
                    + entry.maximum().toPlainString());
        }
        return "Preis " + entry.price().toPlainString() + " " + entry.currency() + ": "
                + String.join("; ", parts);
    }

    private static String toCsv(List<Entry> entries) {
        StringBuilder csv = new StringBuilder(
                "sku,title,unit,price,currency,last_sold,status\n");
        for (Entry entry : entries) {
            List<String> status = new ArrayList<>();
            if (entry.unconfirmed()) {
                status.add("UNCONFIRMED");
            }
            if (entry.stale()) {
                status.add("STALE");
            }
            if (entry.inconsistent()) {
                status.add("INCONSISTENT");
            }
            csv.append(csv(entry.article())).append(',').append(csv(entry.description())).append(',')
                    .append(csv(entry.unit())).append(',')
                    .append(entry.price() == null ? "" : entry.price().toPlainString()).append(',')
                    .append(entry.currency()).append(',')
                    .append(entry.lastSold() == null ? "" : entry.lastSold()).append(',')
                    .append(String.join(";", status)).append('\n');
        }
        return csv.toString();
    }

    private static String description(Analysis analysis, LocalDate effectiveFrom, String currency,
                                      AgentArtifactStore.Artifact artifact, BigDecimal marginFloor) {
        StringBuilder text = new StringBuilder();
        text.append("Preisbuch zum ").append(effectiveFrom).append(" (").append(currency)
                .append(")\n")
                .append("Artikel: ").append(analysis.articles()).append('\n')
                .append("Ohne Bestätigung: ").append(analysis.unconfirmed()).append('\n')
                .append("Älter als 365 Tage: ").append(analysis.stale()).append('\n')
                .append("Preisabweichungen: ").append(analysis.inconsistent()).append("\n\n")
                .append("Ablage: ").append(artifact.storageKey()).append("\n\n")
                .append("Auffällige Positionen:\n");
        int shown = 0;
        for (Entry entry : analysis.entries()) {
            if (!(entry.unconfirmed() || entry.stale() || entry.inconsistent())) {
                continue;
            }
            text.append("- ").append(entry.article() == null ? entry.description()
                    : entry.article()).append(": ").append(findingText(entry)).append('\n');
            if (++shown >= MAX_PRODUCED) {
                break;
            }
        }
        if (marginFloor != null) {
            text.append("\nEine Margenprüfung gegen ").append(marginFloor.toPlainString())
                    .append(" % ist ohne Einkaufskosten je Artikel nicht möglich.\n");
        }
        text.append("\nEs wurden keine Preise geändert.");
        return text.toString();
    }

    private static boolean matchesCategories(String description, Set<String> categories) {
        if (categories == null) {
            return true;
        }
        if (description == null) {
            return false;
        }
        String text = description.toLowerCase(Locale.ROOT);
        for (String category : categories) {
            if (text.contains(category)) {
                return true;
            }
        }
        return false;
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 16);
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
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

    private static String value(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private static String string(Object raw) {
        return value(raw);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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

    private static LocalDate date(Object raw) {
        if (raw instanceof LocalDate value) {
            return value;
        }
        if (raw instanceof java.sql.Date value) {
            return value.toLocalDate();
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

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing");
        }
        return value.strip();
    }
}
