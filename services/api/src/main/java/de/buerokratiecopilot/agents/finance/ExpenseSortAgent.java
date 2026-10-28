package de.buerokratiecopilot.agents.finance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.invoices.InvoiceLineRepository;
import de.buerokratiecopilot.invoices.domain.InvoiceLine;

/**
 * {@code expense-sort} — Beleg-Sortierung.
 *
 * <p>Assigns cost categories to the line items of incoming invoices by fixed keyword rules (the same
 * category vocabulary that the DATEV and budget agents consume). Existing categories are kept unless
 * the caller explicitly asks to overwrite them, which makes repeated runs idempotent.</p>
 */
@Component
public class ExpenseSortAgent implements Agent {

    private static final Map<String, List<String>> CATEGORY_KEYWORDS = new LinkedHashMap<>();
    static {
        CATEGORY_KEYWORDS.put("travel", List.of("hotel", "übernachtung", "uebernachtung", "flug",
                "bahn", "taxi", "mietwagen", "reise", "restaurant", "bewirtung"));
        CATEGORY_KEYWORDS.put("office", List.of("büro", "buero", "papier", "toner", "drucker", "stift",
                "ordner", "büromaterial", "bueromaterial"));
        CATEGORY_KEYWORDS.put("it", List.of("software", "lizenz", "hosting", "server", "laptop",
                "monitor", "domain", "domäne", "cloud"));
        CATEGORY_KEYWORDS.put("marketing", List.of("werbung", "marketing", "anzeige", "flyer",
                "visitenkarten", "social"));
        CATEGORY_KEYWORDS.put("rent", List.of("miete", "pacht", "nebenkosten"));
        CATEGORY_KEYWORDS.put("utilities", List.of("strom", "gas", "wasser", "heizung", "telefon",
                "internet", "telekom", "abfall", "müll", "muell"));
        CATEGORY_KEYWORDS.put("insurance", List.of("versicherung"));
        CATEGORY_KEYWORDS.put("accounting", List.of("steuerberatung", "steuerberater", "buchhaltung",
                "lohnabrechnung"));
        CATEGORY_KEYWORDS.put("fees", List.of("beratung", "honorar", "gutachten", "gebühr", "gebuehr",
                "beitrag"));
        CATEGORY_KEYWORDS.put("material", List.of("ware", "material", "lieferung", "rohstoff",
                "verpackung"));
        CATEGORY_KEYWORDS.put("personnel", List.of("personal", "weiterbildung", "schulung"));
    }

    private static final int MAX_INVOICES = 50;

    private final JdbcTemplate jdbc;
    private final InvoiceLineRepository lines;

    public ExpenseSortAgent(JdbcTemplate jdbc, InvoiceLineRepository lines) {
        this.jdbc = jdbc;
        this.lines = lines;
    }

    @Override
    public String id() {
        return "expense-sort";
    }

    @Override
    public AgentResult run(AgentContext context) {
        boolean overwrite = "true".equalsIgnoreCase(context.input("overwrite"));
        List<UUID> invoiceIds = invoiceIds(context);
        if (invoiceIds.isEmpty()) {
            return AgentResult.skipped("Beleg-Sortierung: keine eingehenden Rechnungen gefunden.");
        }

        int categorized = 0;
        int unmatched = 0;
        int kept = 0;
        List<AgentResult.Produced> produced = new ArrayList<>();
        for (UUID invoiceId : invoiceIds) {
            for (InvoiceLine line : lines.findByInvoiceIdOrderByLineNumberAsc(invoiceId)) {
                if (!overwrite && line.getCostCategory() != null && !line.getCostCategory().isBlank()) {
                    kept++;
                    continue;
                }
                String category = match(line.getDescription());
                if (category == null) {
                    unmatched++;
                    continue;
                }
                line.setCostCategory(category);
                lines.save(line);
                categorized++;
                produced.add(new AgentResult.Produced("expense-categorization", line.getId().toString(),
                        category + ": " + FinanceSupport.abbreviate(line.getDescription(), 60)));
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("categorized", String.valueOf(categorized));
        meta.put("unmatched", String.valueOf(unmatched));
        meta.put("kept", String.valueOf(kept));
        meta.put("invoicesScanned", String.valueOf(invoiceIds.size()));
        if (categorized == 0) {
            return new AgentResult(false,
                    "Beleg-Sortierung: keine Position zugeordnet (" + unmatched
                            + " ohne passende Regel, " + kept + " bereits kategorisiert).",
                    produced, meta);
        }
        return AgentResult.done(
                "Beleg-Sortierung: " + categorized + " Position(en) kategorisiert, " + unmatched
                        + " ohne passende Regel, " + kept + " unverändert.",
                produced, meta);
    }

    private List<UUID> invoiceIds(AgentContext context) {
        String input = context.input("invoiceId");
        if (input != null && !input.isBlank() && !"all".equalsIgnoreCase(input.trim())) {
            UUID id = FinanceSupport.parseUuid(input);
            return id == null ? List.of() : List.of(id);
        }
        return jdbc.queryForList("""
                select id from invoices
                where tenant_id = ? and deleted_at is null and direction = 'INCOMING'
                order by created_at desc limit ?
                """, UUID.class, context.tenantId(), MAX_INVOICES);
    }

    private static String match(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String text = description.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> rule : CATEGORY_KEYWORDS.entrySet()) {
            for (String keyword : rule.getValue()) {
                if (text.contains(keyword)) {
                    return rule.getKey();
                }
            }
        }
        return null;
    }
}
