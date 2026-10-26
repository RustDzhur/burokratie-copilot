package de.buerokratiecopilot.agents.finance;

import java.math.BigDecimal;
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
import de.buerokratiecopilot.shared.settings.SystemSetting;
import de.buerokratiecopilot.shared.settings.SystemSettingRepository;

/**
 * {@code account-datev} — Buchhaltung &amp; DATEV.
 *
 * <p>Prepares approved incoming invoices as DATEV postings: the net amount goes to the expense account
 * chosen by the line's cost category, the tax to the input-tax account, the gross amount to the
 * creditor account. Control sums are checked (header vs. lines, Soll vs. Haben); an invoice whose sums
 * do not add up is held back, never silently exported. A successful preparation is marked in the
 * invoice metadata, which is what makes repeated runs idempotent.</p>
 *
 * <p>Nothing leaves the platform: the agent only prepares and marks, the DATEV file export itself is a
 * later, human-triggered step.</p>
 */
@Component
public class AccountDatevAgent implements Agent {

    private static final Map<String, String> ACCOUNT_BY_CATEGORY = new LinkedHashMap<>();
    static {
        ACCOUNT_BY_CATEGORY.put("material", "3400");
        ACCOUNT_BY_CATEGORY.put("marketing", "4600");
        ACCOUNT_BY_CATEGORY.put("travel", "4663");
        ACCOUNT_BY_CATEGORY.put("it", "4920");
        ACCOUNT_BY_CATEGORY.put("office", "4930");
        ACCOUNT_BY_CATEGORY.put("fees", "4950");
        ACCOUNT_BY_CATEGORY.put("rent", "4210");
        ACCOUNT_BY_CATEGORY.put("utilities", "4240");
        ACCOUNT_BY_CATEGORY.put("insurance", "4360");
        ACCOUNT_BY_CATEGORY.put("accounting", "4955");
        ACCOUNT_BY_CATEGORY.put("personnel", "4120");
    }

    private static final String DEFAULT_ACCOUNT = "5900";
    private static final String INPUT_TAX_ACCOUNT = "1576";
    private static final String CREDITOR_ACCOUNT = "1600";
    private static final String SETTING_PREFIX = "finance.datev.account.";
    private static final int MAX_INVOICES = 200;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    /** One bookkeeping row: a debit (Soll) or credit (Haben) on an account. */
    private record Posting(String account, boolean debit, BigDecimal amount) {
    }

    private final JdbcTemplate jdbc;
    private final SystemSettingRepository settings;

    public AccountDatevAgent(JdbcTemplate jdbc, SystemSettingRepository settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    @Override
    public String id() {
        return "account-datev";
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<UUID> wanted = FinanceSupport.parseUuids(context.input("invoiceIds"));
        List<Map<String, Object>> rows = eligibleInvoices(context.tenantId(), wanted);
        if (rows.isEmpty()) {
            return AgentResult.skipped("DATEV: keine geprüften, noch nicht exportierten eingehenden "
                    + "Rechnungen (APPROVED/PARTIALLY_PAID/PAID ohne Buchungsblockade) gefunden.");
        }

        int exported = 0;
        int already = 0;
        int failed = 0;
        int postings = 0;
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        List<AgentResult.Produced> produced = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            UUID invoiceId = (UUID) row.get("id");
            String number = String.valueOf(row.get("invoice_number"));
            BigDecimal net = FinanceSupport.nvl(row.get("net_amount"));
            BigDecimal tax = FinanceSupport.nvl(row.get("tax_amount"));
            BigDecimal gross = FinanceSupport.nvl(row.get("gross_amount"));
            List<Map<String, Object>> lines = linesOf(invoiceId);

            List<Posting> invoicePostings = new ArrayList<>();
            BigDecimal lineNet = BigDecimal.ZERO;
            BigDecimal lineTax = BigDecimal.ZERO;
            BigDecimal lineGross = BigDecimal.ZERO;
            if (lines.isEmpty()) {
                if (net.signum() > 0) {
                    invoicePostings.add(new Posting(DEFAULT_ACCOUNT, true, net));
                }
            }
            else {
                for (Map<String, Object> line : lines) {
                    BigDecimal itemNet = FinanceSupport.nvl(line.get("net_amount"));
                    String category = line.get("cost_category") == null ? null
                            : String.valueOf(line.get("cost_category"));
                    if (itemNet.signum() > 0) {
                        invoicePostings.add(new Posting(accountFor(context.tenantId(), category), true,
                                itemNet));
                    }
                    lineNet = lineNet.add(itemNet);
                    lineTax = lineTax.add(FinanceSupport.nvl(line.get("tax_amount")));
                    lineGross = lineGross.add(FinanceSupport.nvl(line.get("gross_amount")));
                }
                if (!FinanceSupport.close(lineNet, net, TOLERANCE)
                        || !FinanceSupport.close(lineTax, tax, TOLERANCE)
                        || !FinanceSupport.close(lineGross, gross, TOLERANCE)) {
                    failed++;
                    continue; // control sums do not add up: never export silently
                }
            }
            if (tax.signum() > 0) {
                invoicePostings.add(new Posting(INPUT_TAX_ACCOUNT, true, tax));
            }
            if (gross.signum() > 0) {
                invoicePostings.add(new Posting(CREDITOR_ACCOUNT, false, gross));
            }
            BigDecimal debit = sum(invoicePostings, true);
            BigDecimal credit = sum(invoicePostings, false);
            if (!FinanceSupport.close(debit, credit, TOLERANCE)) {
                failed++;
                continue;
            }

            int updated = jdbc.update("""
                    update invoices set metadata = metadata || jsonb_build_object(
                        'datev', jsonb_build_object('exportedAt', ?, 'postings', ?, 'controlOk', true))
                    where id = ? and tenant_id = ? and metadata->'datev' is null
                    """, context.now().toString(), invoicePostings.size(), invoiceId,
                    context.tenantId());
            if (updated == 0) {
                already++; // exported by a concurrent run: idempotency guard
                continue;
            }
            exported++;
            postings += invoicePostings.size();
            totalDebit = totalDebit.add(debit);
            totalCredit = totalCredit.add(credit);
            produced.add(new AgentResult.Produced("datev-posting", invoiceId.toString(),
                    number + ": Soll " + FinanceSupport.money(debit) + " / Haben "
                            + FinanceSupport.money(credit)));
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("exported", String.valueOf(exported));
        meta.put("alreadyExported", String.valueOf(already));
        meta.put("failed", String.valueOf(failed));
        meta.put("postings", String.valueOf(postings));
        meta.put("totalDebit", totalDebit.toPlainString());
        meta.put("totalCredit", totalCredit.toPlainString());
        meta.put("controlOk", String.valueOf(FinanceSupport.close(totalDebit, totalCredit, TOLERANCE)));
        if (exported == 0) {
            return new AgentResult(false,
                    "DATEV: keine neuen Buchungen — " + already + " bereits exportiert, " + failed
                            + " Rechnung(en) mit Kontrollsummen-Abweichung zurückgehalten.",
                    produced, meta);
        }
        return AgentResult.done(
                "DATEV: " + exported + " Rechnung(en) als " + postings + " Buchungssätze vorbereitet (Soll "
                        + FinanceSupport.money(totalDebit) + ", Haben "
                        + FinanceSupport.money(totalCredit) + ").",
                produced, meta);
    }

    private List<Map<String, Object>> eligibleInvoices(UUID tenantId, List<UUID> wanted) {
        StringBuilder sql = new StringBuilder("""
                select id, invoice_number, issue_date, net_amount, tax_amount, gross_amount
                from invoices
                where tenant_id = ? and deleted_at is null and direction = 'INCOMING'
                  and status in ('APPROVED', 'PARTIALLY_PAID', 'PAID')
                  and metadata->'datev' is null
                  and coalesce((validation_state->>'blocksAutomaticPosting')::boolean, false) = false
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (!wanted.isEmpty()) {
            sql.append(" and id in (");
            sql.append(String.join(",", java.util.Collections.nCopies(wanted.size(), "?")));
            sql.append(")");
            args.addAll(wanted);
        }
        sql.append(" order by issue_date nulls last, created_at limit ?");
        args.add(MAX_INVOICES);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private List<Map<String, Object>> linesOf(UUID invoiceId) {
        return jdbc.queryForList("""
                select line_number, description, net_amount, tax_amount, gross_amount, cost_category
                from invoice_lines where invoice_id = ? order by line_number
                """, invoiceId);
    }

    private String accountFor(UUID tenantId, String category) {
        String key = category == null ? "" : category.toLowerCase(Locale.ROOT).strip();
        if (!key.isBlank()) {
            String override = settings.findByTenantIdAndSettingKey(tenantId, SETTING_PREFIX + key)
                    .map(SystemSetting::valueAsString)
                    .orElseGet(() -> settings.findByTenantIdIsNullAndSettingKey(SETTING_PREFIX + key)
                            .map(SystemSetting::valueAsString).orElse(null));
            if (override != null && !override.isBlank()) {
                return override.strip();
            }
            String builtin = ACCOUNT_BY_CATEGORY.get(key);
            if (builtin != null) {
                return builtin;
            }
        }
        return DEFAULT_ACCOUNT;
    }

    private static BigDecimal sum(List<Posting> postings, boolean debit) {
        BigDecimal total = BigDecimal.ZERO;
        for (Posting posting : postings) {
            if (posting.debit() == debit) {
                total = total.add(posting.amount());
            }
        }
        return total;
    }
}
