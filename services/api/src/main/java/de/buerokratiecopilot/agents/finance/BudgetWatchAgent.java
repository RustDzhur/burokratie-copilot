package de.buerokratiecopilot.agents.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.shared.settings.SystemSetting;
import de.buerokratiecopilot.shared.settings.SystemSettingRepository;

/**
 * {@code budget-watch} — Budget-Überwachung.
 *
 * <p>Compares the current month's spending per cost category (from incoming invoice lines) with the
 * monthly budgets configured in the settings ({@code finance.budget.<category>.monthly_eur}, tenant
 * value over system default). Reports every deviation; categories over budget are additionally
 * surfaced as produced warnings. Read-only against the data, which makes it idempotent.</p>
 */
@Component
public class BudgetWatchAgent implements Agent {

    private static final String SETTING_PREFIX = "finance.budget.";
    private static final String SETTING_SUFFIX = ".monthly_eur";

    private record Category(String key, String label) {
    }

    private record Budget(String key, String label, BigDecimal monthlyEur) {
    }

    private static final List<Category> CATEGORIES = List.of(
            new Category("material", "Material"),
            new Category("marketing", "Marketing"),
            new Category("travel", "Reisekosten"),
            new Category("it", "IT & Software"),
            new Category("office", "Büro"),
            new Category("fees", "Honorare & Gebühren"),
            new Category("rent", "Miete"),
            new Category("utilities", "Nebenkosten"),
            new Category("insurance", "Versicherungen"),
            new Category("accounting", "Steuer & Buchhaltung"),
            new Category("personnel", "Personal"));

    private final JdbcTemplate jdbc;
    private final SystemSettingRepository settings;

    public BudgetWatchAgent(JdbcTemplate jdbc, SystemSettingRepository settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public String id() {
        return "budget-watch";
    }

    @Override
    public AgentResult run(AgentContext context) {
        YearMonth month = month(context);
        LocalDate start = month.atDay(1);
        LocalDate end = start.plusMonths(1);
        Instant startInstant = start.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant endInstant = end.atStartOfDay(ZoneId.systemDefault()).toInstant();

        Map<String, Budget> budgets = new LinkedHashMap<>();
        for (Category category : CATEGORIES) {
            BigDecimal budget = readBudget(context.tenantId(), category.key());
            if (budget.signum() > 0) {
                budgets.put(category.key(), new Budget(category.key(), category.label(), budget));
            }
        }
        if (budgets.isEmpty()) {
            return AgentResult.skipped("Budget-Überwachung: keine Budgets konfiguriert (Einstellungen "
                    + SETTING_PREFIX + "<kategorie>" + SETTING_SUFFIX + ").");
        }

        Map<String, BigDecimal> spentByCategory = spendingByCategory(context.tenantId(), start, end,
                startInstant, endInstant);
        BigDecimal uncategorized = spendingWithoutLines(context.tenantId(), start, end, startInstant,
                endInstant);

        BigDecimal totalBudget = BigDecimal.ZERO;
        BigDecimal totalSpent = BigDecimal.ZERO;
        int over = 0;
        List<AgentResult.Produced> produced = new ArrayList<>();
        List<Map<String, Object>> report = new ArrayList<>();
        for (Budget budget : budgets.values()) {
            BigDecimal spent = spentByCategory.getOrDefault(budget.key(), BigDecimal.ZERO);
            totalBudget = totalBudget.add(budget.monthlyEur());
            totalSpent = totalSpent.add(spent);
            BigDecimal deviation = spent.subtract(budget.monthlyEur());
            boolean overBudget = deviation.signum() > 0;
            if (overBudget) {
                over++;
                produced.add(new AgentResult.Produced("budget-warning", budget.key(),
                        budget.label() + ": " + FinanceSupport.money(spent) + " von "
                                + FinanceSupport.money(budget.monthlyEur()) + " EUR verbraucht"));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", budget.key());
            row.put("label", budget.label());
            row.put("budget", budget.monthlyEur().toPlainString());
            row.put("spent", spent.toPlainString());
            row.put("deviation", deviation.toPlainString());
            row.put("over", overBudget);
            report.add(row);
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("month", month.toString());
        meta.put("activeBudgets", String.valueOf(budgets.size()));
        meta.put("overBudgets", String.valueOf(over));
        meta.put("totalBudget", totalBudget.toPlainString());
        meta.put("totalSpent", totalSpent.toPlainString());
        meta.put("uncategorized", uncategorized.toPlainString());
        meta.put("report", reportJson(report));
        return AgentResult.done(
                "Budget-Überwachung " + month + ": " + over + " von " + budgets.size()
                        + " Budget(s) überschritten, Ausgaben " + FinanceSupport.money(totalSpent)
                        + " von " + FinanceSupport.money(totalBudget) + " EUR.",
                produced, meta);
    }

    private YearMonth month(AgentContext context) {
        String input = context.input("month");
        if (input != null && !input.isBlank()) {
            try {
                return YearMonth.parse(input.strip());
            }
            catch (DateTimeParseException ignored) {
                // fall through to the current month
            }
        }
        return YearMonth.from(LocalDate.ofInstant(context.now(), ZoneId.systemDefault()));
    }

    private BigDecimal readBudget(UUID tenantId, String key) {
        String raw = settings.findByTenantIdAndSettingKey(tenantId, SETTING_PREFIX + key + SETTING_SUFFIX)
                .map(SystemSetting::valueAsString)
                .orElseGet(() -> settings
                        .findByTenantIdIsNullAndSettingKey(SETTING_PREFIX + key + SETTING_SUFFIX)
                        .map(SystemSetting::valueAsString).orElse(null));
        BigDecimal parsed = FinanceSupport.parseAmount(raw);
        return parsed == null ? BigDecimal.ZERO : parsed;
    }

    private Map<String, BigDecimal> spendingByCategory(UUID tenantId, LocalDate start, LocalDate end,
                                                       Instant startInstant, Instant endInstant) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select coalesce(l.cost_category, 'uncategorized') as category, sum(l.net_amount) as spent
                from invoice_lines l
                join invoices i on i.id = l.invoice_id
                where i.tenant_id = ? and i.deleted_at is null and i.direction = 'INCOMING'
                  and ((i.issue_date >= ? and i.issue_date < ?)
                       or (i.issue_date is null and i.created_at >= ? and i.created_at < ?))
                group by 1
                """, tenantId, start, end, startInstant, endInstant);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(String.valueOf(row.get("category")), FinanceSupport.nvl(row.get("spent")));
        }
        return result;
    }

    private BigDecimal spendingWithoutLines(UUID tenantId, LocalDate start, LocalDate end,
                                            Instant startInstant, Instant endInstant) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select coalesce(sum(i.net_amount), 0) as spent
                from invoices i
                where i.tenant_id = ? and i.deleted_at is null and i.direction = 'INCOMING'
                  and not exists (select 1 from invoice_lines l where l.invoice_id = i.id)
                  and ((i.issue_date >= ? and i.issue_date < ?)
                       or (i.issue_date is null and i.created_at >= ? and i.created_at < ?))
                """, tenantId, start, end, startInstant, endInstant);
        return rows.isEmpty() ? BigDecimal.ZERO : FinanceSupport.nvl(rows.getFirst().get("spent"));
    }

    private static String reportJson(List<Map<String, Object>> report) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < report.size(); i++) {
            Map<String, Object> row = report.get(i);
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"category\":\"").append(row.get("category"))
                    .append("\",\"label\":\"").append(row.get("label"))
                    .append("\",\"budget\":").append(row.get("budget"))
                    .append(",\"spent\":").append(row.get("spent"))
                    .append(",\"deviation\":").append(row.get("deviation"))
                    .append(",\"over\":").append(row.get("over")).append("}");
        }
        return json.append("]").toString();
    }
}
