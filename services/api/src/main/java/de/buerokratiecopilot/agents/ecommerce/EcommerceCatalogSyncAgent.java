package de.buerokratiecopilot.agents.ecommerce;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * {@code ecommerce-catalog-sync} — Katalog-Synchronisation.
 *
 * <p>There is no product, PIM or marketplace table in this platform. The article master that does
 * exist is the article number, title and unit carried by invoice lines. This agent compares those
 * values to each other and reports the conflicts a sync would have to resolve: one article number
 * with several titles or units, lines without an article number, lines without a title.</p>
 *
 * <p>The run is a dry run by default and writes nothing to any channel. Even with
 * {@code dryRun=false} the agent only reports: no shop, PIM or marketplace adapter is connected, and
 * pretending to have synchronised would be worse than saying so.</p>
 */
@Component
public class EcommerceCatalogSyncAgent implements Agent {

    private static final int MAX_ROWS = 5000;
    private static final int MAX_PRODUCED = 50;

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;

    public EcommerceCatalogSyncAgent(JdbcTemplate jdbc, AgentTaskFactory tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "ecommerce-catalog-sync";
    }

    /** One article line as it appears in the invoice data. */
    public record ArticleRow(String article, String title, String unit, String supplier,
                             LocalDate date) {
    }

    /** One conflict that a synchronisation would have to resolve. */
    public record Conflict(String code, String article, String message) {
    }

    /** The complete deterministic result. */
    public record Analysis(int articles, List<Conflict> conflicts) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        String channel = blankTo(context.input("channel"), "SHOP").toUpperCase(Locale.ROOT);
        boolean dryRun = !"false".equalsIgnoreCase(context.input("dryRun"));
        Set<String> fields = split(context.input("fieldsToSync"));

        List<ArticleRow> rows = load(context.tenantId());
        if (rows.isEmpty()) {
            return AgentResult.skipped("Keine Artikel-/Katalogdaten gefunden: es gibt keine "
                    + "Product-/Article-Tabellen und keine Rechnungspositionen mit Artikeldaten.");
        }
        Analysis analysis = analyse(rows, fields);
        if (analysis.articles() == 0) {
            return AgentResult.skipped("Die Rechnungspositionen tragen keine verwertbaren "
                    + "Artikeldaten; es gibt nichts abzugleichen.");
        }

        UUID taskId = null;
        boolean taskCreated = false;
        if (!analysis.conflicts().isEmpty()) {
            AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                    context.actorUserId(),
                    "Katalog-Abgleich prüfen: " + channel + " (" + analysis.conflicts().size()
                            + " Konflikte)", description(channel, analysis),
                    Task.Priority.NORMAL,
                    "Stammdatenkonflikt zwischen Rechnungsdaten und Zielkanal; eine Übertragung "
                            + "erfolgt erst nach menschlicher Entscheidung.",
                    context.now().plus(7, ChronoUnit.DAYS),
                    List.of("ecommerce", "catalog-sync"));
            taskId = task.taskId();
            taskCreated = task.created();
        }

        for (Conflict conflict : analysis.conflicts().stream().limit(MAX_PRODUCED).toList()) {
            context.addProduced("catalog-conflict", conflict.article(), conflict.code() + ": "
                    + conflict.message());
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("channel", channel);
        meta.put("dryRun", String.valueOf(dryRun));
        meta.put("source", "invoice_lines");
        meta.put("articlesChecked", String.valueOf(analysis.articles()));
        meta.put("conflicts", String.valueOf(analysis.conflicts().size()));
        meta.put("taskId", taskId == null ? "" : taskId.toString());
        meta.put("taskCreated", String.valueOf(taskCreated));
        meta.put("limitation", "Kein Shop-/ERP-/Marktplatz-Adapter angebunden; es wurde nichts "
                + "übertragen. Es werden nur Artikelnummer, Titel und Einheit verglichen.");

        if (analysis.conflicts().isEmpty()) {
            return AgentResult.done("Katalog-Abgleich (" + channel + "): " + analysis.articles()
                    + " Artikel geprüft, keine Konflikte gefunden."
                    + (dryRun ? " Trockenlauf, nichts übertragen." : " Kein Kanal angebunden, "
                            + "nichts übertragen."), context.produced(), meta);
        }
        if (dryRun) {
            return AgentResult.done("Katalog-Abgleich (" + channel + ", Trockenlauf): "
                    + analysis.articles() + " Artikel geprüft, " + analysis.conflicts().size()
                    + " Konflikt(e) gefunden. Nichts übertragen.", context.produced(), meta);
        }
        return AgentResult.done("Katalog-Abgleich (" + channel + "): " + analysis.articles()
                        + " Artikel geprüft, " + analysis.conflicts().size()
                        + " Konflikt(e); mangels Kanal-Adapter wurde nichts übertragen.",
                context.produced(), meta);
    }

    private List<ArticleRow> load(UUID tenantId) {
        List<Map<String, Object>> raw = jdbc.queryForList("""
                select il.article_number, il.description, il.unit, i.issue_date,
                       s.name as supplier_name
                from invoice_lines il
                join invoices i on i.id = il.invoice_id and i.tenant_id = il.tenant_id
                left join suppliers s on s.id = i.supplier_id
                where il.tenant_id = ? and i.deleted_at is null
                order by il.article_number nulls last, i.issue_date nulls last
                limit ?
                """, tenantId, MAX_ROWS);
        List<ArticleRow> rows = new ArrayList<>();
        for (Map<String, Object> row : raw) {
            rows.add(new ArticleRow(string(row.get("article_number")),
                    string(row.get("description")), string(row.get("unit")),
                    string(row.get("supplier_name")), date(row.get("issue_date"))));
        }
        return rows;
    }

    /**
     * Compares the article master carried by invoice lines. Visible for tests.
     *
     * @param fields optional allow-list; when given, only conflicts about these fields are reported
     */
    public static Analysis analyse(List<ArticleRow> rows, Set<String> fields) {
        Map<String, List<ArticleRow>> byArticle = new LinkedHashMap<>();
        List<Conflict> conflicts = new ArrayList<>();
        Set<String> missingArticleTitles = new LinkedHashSet<>();

        for (ArticleRow row : rows) {
            String article = clean(row.article());
            String title = clean(row.title());
            if (article == null) {
                String key = title == null ? "(ohne Titel)" : title;
                if (missingArticleTitles.add(key.toLowerCase(Locale.ROOT))) {
                    conflicts.add(new Conflict("ARTICLE_NUMBER_MISSING", key,
                            "Position '" + key + "' hat keine Artikelnummer"));
                }
                continue;
            }
            byArticle.computeIfAbsent(article, ignored -> new ArrayList<>()).add(row);
        }

        for (Map.Entry<String, List<ArticleRow>> entry : byArticle.entrySet()) {
            String article = entry.getKey();
            List<ArticleRow> lines = entry.getValue();
            Set<String> titles = new LinkedHashSet<>();
            Set<String> units = new LinkedHashSet<>();
            for (ArticleRow line : lines) {
                String title = clean(line.title());
                String unit = clean(line.unit());
                if (title != null) {
                    titles.add(title.toLowerCase(Locale.ROOT));
                }
                if (unit != null) {
                    units.add(unit.toLowerCase(Locale.ROOT));
                }
            }
            if (lines.stream().anyMatch(line -> clean(line.title()) == null)
                    && allowed(fields, "title", "description")) {
                conflicts.add(new Conflict("TITLE_MISSING", article,
                        "Artikel " + article + " hat eine Position ohne Titel"));
            }
            if (titles.size() > 1 && allowed(fields, "title", "description")) {
                conflicts.add(new Conflict("TITLE_MISMATCH", article,
                        "Artikel " + article + " trägt " + titles.size()
                                + " verschiedene Titel: " + String.join(" | ", limit(titles))));
            }
            if (units.size() > 1 && allowed(fields, "unit")) {
                conflicts.add(new Conflict("UNIT_MISMATCH", article,
                        "Artikel " + article + " trägt " + units.size()
                                + " verschiedene Einheiten: " + String.join(", ", units)));
            }
        }
        return new Analysis(byArticle.size(), List.copyOf(conflicts));
    }

    private static boolean allowed(Set<String> fields, String... names) {
        if (fields == null) {
            return true;
        }
        for (String name : names) {
            if (fields.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> limit(Set<String> values) {
        return values.stream().limit(5).map(value -> value.length() > 60
                ? value.substring(0, 57) + "..." : value).toList();
    }

    private static String description(String channel, Analysis analysis) {
        StringBuilder text = new StringBuilder(
                "Der Katalog-Abgleich hat folgende Konflikte gefunden (Kanal " + channel
                        + "). Es wurde nichts übertragen.\n\n");
        for (Conflict conflict : analysis.conflicts().stream().limit(MAX_PRODUCED).toList()) {
            text.append("- [").append(conflict.code()).append("] ")
                    .append(conflict.message()).append('\n');
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

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
