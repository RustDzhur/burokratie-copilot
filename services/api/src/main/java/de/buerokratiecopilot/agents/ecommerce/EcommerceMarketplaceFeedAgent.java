package de.buerokratiecopilot.agents.ecommerce;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code ecommerce-marketplace-feed} — Marketplace-Feed.
 *
 * <p>Builds a feed from the only article and price data the platform holds: priced invoice lines.
 * The latest sale price per article wins; where no sale exists, the latest purchase price is used
 * and the row is marked as such. Missing mandatory fields are listed with the article they belong
 * to and excluded from the feed, so the file never silently contains a product with no price.</p>
 *
 * <p>The feed is stored as an artifact and a review task is opened. It is <strong>not
 * published</strong>: no marketplace or FTP adapter is connected, and the agent says so instead of
 * claiming a transfer.</p>
 */
@Component
public class EcommerceMarketplaceFeedAgent implements Agent {

    private static final int MAX_ROWS = 5000;
    private static final int MAX_PRODUCED = 50;
    private static final Set<String> FORMATS = Set.of("CSV", "JSON");

    private final JdbcTemplate jdbc;
    private final AgentArtifactStore artifacts;
    private final AgentTaskFactory tasks;
    private final ObjectMapper mapper;

    public EcommerceMarketplaceFeedAgent(JdbcTemplate jdbc, AgentArtifactStore artifacts,
                                         AgentTaskFactory tasks, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.artifacts = artifacts;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "ecommerce-marketplace-feed";
    }

    /** One feed-ready product. */
    public record Product(String sku, String title, BigDecimal price, String currency, String unit,
                          String priceSource, LocalDate priceDate) {
    }

    /** A row that cannot go into the feed, with the reason. */
    public record Skipped(String sku, String reason) {
    }

    /** The built feed plus what was left out. */
    public record Feed(List<Product> products, List<Skipped> skipped) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        String marketplace = clean(context.input("marketplace"));
        if (marketplace == null) {
            return AgentResult.skipped("marketplace fehlt; ohne Zielmarktplatz gibt es keinen "
                    + "Feed zu bauen.");
        }
        String format = clean(context.input("feedFormat"));
        format = format == null ? "CSV" : format.toUpperCase(Locale.ROOT);
        if (!FORMATS.contains(format)) {
            return AgentResult.failed("Unbekanntes Feed-Format '" + format + "'; erlaubt sind CSV "
                    + "und JSON.");
        }
        String currency = clean(context.input("currency"));
        currency = currency == null ? "EUR" : currency.toUpperCase(Locale.ROOT);
        String language = clean(context.input("language"));
        language = language == null ? "de" : language.toLowerCase(Locale.ROOT);
        Set<String> productFilter = split(context.input("productIds"));

        List<Map<String, Object>> raw = load(context.tenantId());
        if (raw.isEmpty()) {
            return AgentResult.skipped("Keine Produkt-/Preisdaten in Rechnungen gefunden; es gibt "
                    + "keinen Feed zu bauen.");
        }
        Feed feed = buildFeed(raw, currency, productFilter);
        if (feed.products().isEmpty()) {
            return AgentResult.failed("Kein Produkt hat die Pflichtfelder für den Feed erfüllt ("
                    + feed.skipped().size() + " Zeile(n) verworfen); es wurde kein Feed gespeichert.");
        }

        String content = "CSV".equals(format) ? toCsv(feed.products(), language)
                : toJson(feed.products(), language);
        String filename = "marketplace-feed-" + slug(marketplace) + "-"
                + LocalDate.ofInstant(context.now(), java.time.ZoneId.of("Europe/Berlin")) + "."
                + format.toLowerCase(Locale.ROOT);
        String contentType = "CSV".equals(format) ? "text/csv" : "application/json";
        String sourceKey = "marketplace-feed:" + marketplace.toLowerCase(Locale.ROOT) + ":"
                + format + ":" + currency + ":" + language + ":" + hash(content);

        AgentArtifactStore.Artifact artifact;
        try {
            artifact = artifacts.store(context.tenantId(), context.actorUserId(), sourceKey,
                    "Marketplace-Feed " + marketplace, filename, contentType, "TXT", content);
        }
        catch (RuntimeException ex) {
            return AgentResult.failed("Der Feed konnte nicht gespeichert werden: " + ex.getMessage());
        }

        String title = "Marketplace-Feed prüfen: " + marketplace;
        AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                context.actorUserId(), title, description(marketplace, feed, artifact, currency),
                Task.Priority.NORMAL,
                "Der Feed ist erzeugt, aber nicht veröffentlicht; Pflichtfelder und Inhalt müssen "
                        + "vor der Übermittlung freigegeben werden.",
                context.now().plus(3, ChronoUnit.DAYS),
                List.of("ecommerce", "marketplace-feed", slug(marketplace)));

        context.addProduced("marketplace-feed", artifact.storageKey(),
                feed.products().size() + " Produkte für " + marketplace + " (" + format + ")");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("marketplace", marketplace);
        meta.put("format", format);
        meta.put("currency", currency);
        meta.put("language", language);
        meta.put("products", String.valueOf(feed.products().size()));
        meta.put("skipped", String.valueOf(feed.skipped().size()));
        meta.put("storageKey", artifact.storageKey());
        meta.put("artifactDocumentId", artifact.documentId().toString());
        meta.put("artifactCreated", String.valueOf(artifact.created()));
        meta.put("taskId", task.taskId().toString());
        meta.put("taskCreated", String.valueOf(task.created()));
        meta.put("published", "false");
        meta.put("limitation", "Kein Marktplatz-/FTP-Adapter angebunden; der Feed wurde nur "
                + "gespeichert und zur Prüfung vorgelegt. Verfügbarkeit ist nicht bekannt und "
                + "wird als UNKNOWN ausgewiesen.");

        for (Skipped skipped : feed.skipped().stream().limit(MAX_PRODUCED).toList()) {
            context.addProduced("feed-row-skipped", skipped.sku(), skipped.reason());
        }
        return AgentResult.done("Marketplace-Feed für " + marketplace + ": " + feed.products().size()
                        + " Produkte gespeichert, " + feed.skipped().size()
                        + " Zeile(n) wegen fehlender Pflichtfelder ausgelassen. Nicht veröffentlicht.",
                context.produced(), meta);
    }

    private List<Map<String, Object>> load(UUID tenantId) {
        return jdbc.queryForList("""
                select il.article_number, il.description, il.unit, il.unit_price,
                       i.currency, i.direction, i.issue_date
                from invoice_lines il
                join invoices i on i.id = il.invoice_id and i.tenant_id = il.tenant_id
                where il.tenant_id = ? and i.deleted_at is null
                order by il.article_number nulls last, i.issue_date nulls last
                limit ?
                """, tenantId, MAX_ROWS);
    }

    /** Latest price per article; sale prices win over purchase prices. Visible for tests. */
    public static Feed buildFeed(List<Map<String, Object>> rows, String currency,
                                 Set<String> productFilter) {
        Map<String, Product> latest = new LinkedHashMap<>();
        Map<String, LocalDate> latestDates = new LinkedHashMap<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sku = clean(value(row.get("article_number")));
            String title = clean(value(row.get("description")));
            BigDecimal price = decimal(row.get("unit_price"));
            String rowCurrency = clean(value(row.get("currency")));
            String direction = clean(value(row.get("direction")));
            LocalDate date = date(row.get("issue_date"));
            String lookup = sku == null ? null : sku.toLowerCase(Locale.ROOT);
            if (productFilter != null && (lookup == null || !productFilter.contains(lookup))) {
                continue;
            }
            if (sku == null) {
                skipped.add(new Skipped(title == null ? "(ohne Artikelnummer)" : title,
                        "Artikelnummer fehlt"));
                continue;
            }
            if (title == null) {
                skipped.add(new Skipped(sku, "Titel fehlt"));
                continue;
            }
            if (price == null || price.signum() <= 0) {
                skipped.add(new Skipped(sku, "Preis fehlt oder ist nicht positiv"));
                continue;
            }
            if (rowCurrency != null && !rowCurrency.equalsIgnoreCase(currency)) {
                skipped.add(new Skipped(sku, "Währung " + rowCurrency + " statt " + currency));
                continue;
            }
            boolean sale = "OUTGOING".equalsIgnoreCase(direction);
            Product candidate = new Product(sku, title, price, currency,
                    clean(value(row.get("unit"))), sale ? "SALE_INVOICE" : "PURCHASE_INVOICE", date);
            Product current = latest.get(lookup);
            LocalDate currentDate = latestDates.get(lookup);
            if (current == null || isNewer(candidate, date, current, currentDate)) {
                latest.put(lookup, candidate);
                latestDates.put(lookup, date);
            }
        }
        return new Feed(List.copyOf(latest.values()), List.copyOf(skipped));
    }

    private static boolean isNewer(Product candidate, LocalDate candidateDate, Product current,
                                   LocalDate currentDate) {
        if (candidateDate == null && currentDate == null) {
            return "SALE_INVOICE".equals(candidate.priceSource())
                    && !"SALE_INVOICE".equals(current.priceSource());
        }
        if (candidateDate == null) {
            return false;
        }
        if (currentDate == null) {
            return true;
        }
        int comparison = candidateDate.compareTo(currentDate);
        if (comparison != 0) {
            return comparison > 0;
        }
        return "SALE_INVOICE".equals(candidate.priceSource())
                && !"SALE_INVOICE".equals(current.priceSource());
    }

    private String toCsv(List<Product> products, String language) {
        StringBuilder csv = new StringBuilder(
                "sku,title,price,currency,unit,language,availability,price_source,price_date\n");
        for (Product product : products) {
            csv.append(csv(product.sku())).append(',').append(csv(product.title())).append(',')
                    .append(product.price().toPlainString()).append(',')
                    .append(product.currency()).append(',')
                    .append(csv(product.unit() == null ? "" : product.unit())).append(',')
                    .append(language).append(",UNKNOWN,")
                    .append(product.priceSource()).append(',')
                    .append(product.priceDate() == null ? "" : product.priceDate()).append('\n');
        }
        return csv.toString();
    }

    private String toJson(List<Product> products, String language) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Product product : products) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sku", product.sku());
            row.put("title", product.title());
            row.put("price", product.price());
            row.put("currency", product.currency());
            row.put("unit", product.unit());
            row.put("language", language);
            row.put("availability", "UNKNOWN");
            row.put("priceSource", product.priceSource());
            row.put("priceDate", product.priceDate() == null ? null : product.priceDate().toString());
            rows.add(row);
        }
        try {
            return mapper.writeValueAsString(rows);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Feed cannot be serialized", ex);
        }
    }

    private static String description(String marketplace, Feed feed,
                                      AgentArtifactStore.Artifact artifact, String currency) {
        StringBuilder text = new StringBuilder();
        text.append("Marktplatz: ").append(marketplace).append('\n')
                .append("Währung: ").append(currency).append('\n')
                .append("Produkte: ").append(feed.products().size()).append('\n')
                .append("Ausgelassene Zeilen: ").append(feed.skipped().size()).append('\n')
                .append("Ablage: ").append(artifact.storageKey()).append("\n\n")
                .append("Der Feed ist NICHT veröffentlicht. Bitte Pflichtfelder und Preise prüfen ")
                .append("und die Übermittlung freigeben.\n");
        if (!feed.skipped().isEmpty()) {
            text.append("\nAusgelassen:\n");
            for (Skipped skipped : feed.skipped().stream().limit(20).toList()) {
                text.append("- ").append(skipped.sku()).append(": ").append(skipped.reason())
                        .append('\n');
            }
        }
        return text.toString();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
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

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static BigDecimal decimal(Object raw) {
        if (raw instanceof BigDecimal value) {
            return value;
        }
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(raw).replace(',', '.'));
        }
        catch (NumberFormatException ex) {
            return null;
        }
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
}
