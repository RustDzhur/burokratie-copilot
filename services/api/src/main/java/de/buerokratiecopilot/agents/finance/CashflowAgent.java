package de.buerokratiecopilot.agents.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * {@code cashflow} — Liquiditätsplanung.
 *
 * <p>Builds a rolling liquidity forecast from the data the other finance modules already hold: the
 * last known bank balance, the open amounts of outgoing invoices (money coming in) and of incoming
 * invoices (money going out), day by day over the horizon. Read-only: the forecast is reported, not
 * stored, which makes the agent idempotent by construction.</p>
 */
@Component
public class CashflowAgent implements Agent {

    private static final int DEFAULT_HORIZON_DAYS = 30;
    private static final int DEFAULT_BUCKET_DAYS = 7;
    private static final List<String> OUTGOING_STATUSES = List.of("APPROVED", "SCHEDULED",
            "PARTIALLY_PAID");
    private static final List<String> INCOMING_STATUSES = List.of("RECEIVED", "IN_REVIEW", "APPROVED",
            "PARTIALLY_PAID");

    private final JdbcTemplate jdbc;

    public CashflowAgent(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "cashflow";
    }

    @Override
    public AgentResult run(AgentContext context) {
        int horizon = Math.max(1, Math.min(
                FinanceSupport.parseInt(context.input("horizonDays"), DEFAULT_HORIZON_DAYS), 365));
        int bucketDays = Math.max(1,
                FinanceSupport.parseInt(context.input("bucketDays"), DEFAULT_BUCKET_DAYS));
        LocalDate today = LocalDate.ofInstant(context.now(), ZoneId.systemDefault());
        LocalDate end = today.plusDays(horizon);

        Map<String, Object> statement = latestStatement(context.tenantId());
        BigDecimal base = statement == null || statement.get("closing_balance") == null ? BigDecimal.ZERO
                : FinanceSupport.nvl(statement.get("closing_balance"));
        String currency = statement == null || statement.get("currency") == null ? "EUR"
                : String.valueOf(statement.get("currency"));

        List<Map<String, Object>> inflows = openInvoices(context.tenantId(), "OUTGOING",
                OUTGOING_STATUSES, today, end);
        List<Map<String, Object>> outflows = openInvoices(context.tenantId(), "INCOMING",
                INCOMING_STATUSES, today, end);
        if (inflows.isEmpty() && outflows.isEmpty()) {
            return AgentResult.skipped("Liquiditätsplanung: keine offenen Rechnungen im Zeitraum "
                    + today + " bis " + end + " — keine Prognose möglich.");
        }

        TreeMap<LocalDate, BigDecimal[]> daily = new TreeMap<>();
        for (Map<String, Object> row : inflows) {
            addDaily(daily, row, 0);
        }
        for (Map<String, Object> row : outflows) {
            addDaily(daily, row, 1);
        }

        BigDecimal balance = base;
        BigDecimal minBalance = base;
        LocalDate minOn = today;
        BigDecimal inflowTotal = BigDecimal.ZERO;
        BigDecimal outflowTotal = BigDecimal.ZERO;
        List<Map<String, Object>> buckets = new ArrayList<>();
        Map<String, Object> bucket = null;
        int bucketIndex = -1;
        BigDecimal bucketIn = BigDecimal.ZERO;
        BigDecimal bucketOut = BigDecimal.ZERO;
        BigDecimal bucketNet = BigDecimal.ZERO;

        for (int day = 0; day <= horizon; day++) {
            LocalDate date = today.plusDays(day);
            BigDecimal[] delta = daily.get(date);
            BigDecimal dayIn = delta == null ? BigDecimal.ZERO : delta[0];
            BigDecimal dayOut = delta == null ? BigDecimal.ZERO : delta[1];
            if (delta != null) {
                inflowTotal = inflowTotal.add(dayIn);
                outflowTotal = outflowTotal.add(dayOut);
            }
            balance = balance.add(dayIn).subtract(dayOut);
            if (balance.compareTo(minBalance) < 0) {
                minBalance = balance;
                minOn = date;
            }
            int index = day / bucketDays;
            if (index != bucketIndex) {
                if (bucket != null) {
                    buckets.add(bucket);
                }
                bucket = new LinkedHashMap<>();
                bucket.put("start", date.toString());
                bucket.put("in", "0");
                bucket.put("out", "0");
                bucket.put("net", "0");
                bucketIndex = index;
                bucketIn = BigDecimal.ZERO;
                bucketOut = BigDecimal.ZERO;
                bucketNet = BigDecimal.ZERO;
            }
            bucketIn = bucketIn.add(dayIn);
            bucketOut = bucketOut.add(dayOut);
            bucketNet = bucketNet.add(dayIn).subtract(dayOut);
            bucket.put("in", bucketIn.toPlainString());
            bucket.put("out", bucketOut.toPlainString());
            bucket.put("net", bucketNet.toPlainString());
        }
        if (bucket != null) {
            buckets.add(bucket);
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("horizonDays", String.valueOf(horizon));
        meta.put("bucketDays", String.valueOf(bucketDays));
        meta.put("currency", currency);
        meta.put("baseLiquidity", base.toPlainString());
        meta.put("inflowTotal", inflowTotal.toPlainString());
        meta.put("outflowTotal", outflowTotal.toPlainString());
        meta.put("netDelta", inflowTotal.subtract(outflowTotal).toPlainString());
        meta.put("endBalance", balance.toPlainString());
        meta.put("minBalance", minBalance.toPlainString());
        meta.put("minBalanceOn", minOn.toString());
        meta.put("buckets", bucketsJson(buckets));
        return AgentResult.done(
                "Liquiditätsplanung über " + horizon + " Tage: Bestand " + FinanceSupport.money(base)
                        + ", Zuflüsse " + FinanceSupport.money(inflowTotal) + ", Abflüsse "
                        + FinanceSupport.money(outflowTotal) + ", Endbestand "
                        + FinanceSupport.money(balance) + " (Tiefststand " + FinanceSupport.money(minBalance)
                        + " am " + minOn + ").",
                List.of(new AgentResult.Produced("cashflow-report", "forecast",
                        "Liquidität über " + horizon + " Tage")),
                meta);
    }

    private static void addDaily(TreeMap<LocalDate, BigDecimal[]> daily, Map<String, Object> row,
                                 int side) {
        LocalDate due = FinanceSupport.toLocalDate(row.get("due_date"));
        if (due == null) {
            return;
        }
        BigDecimal open = FinanceSupport.nvl(row.get("gross_amount"))
                .subtract(FinanceSupport.nvl(row.get("paid_amount")));
        if (open.signum() <= 0) {
            return;
        }
        BigDecimal[] delta = daily.computeIfAbsent(due, d -> new BigDecimal[] { BigDecimal.ZERO,
                BigDecimal.ZERO });
        delta[side] = delta[side].add(open);
    }

    private List<Map<String, Object>> openInvoices(UUID tenantId, String direction,
                                                   List<String> statuses, LocalDate from,
                                                   LocalDate to) {
        String marks = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(direction);
        args.addAll(statuses);
        args.add(from);
        args.add(to);
        return jdbc.queryForList("""
                select due_date, gross_amount, paid_amount
                from invoices
                where tenant_id = ? and deleted_at is null and direction = ?
                  and status in (""" + marks + """
                ) and due_date between ? and ?
                  and coalesce(gross_amount, 0) > coalesce(paid_amount, 0)
                """, args.toArray());
    }

    private Map<String, Object> latestStatement(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select closing_balance, currency from bank_statements
                where tenant_id = ? order by period_end desc nulls last limit 1
                """, tenantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String bucketsJson(List<Map<String, Object>> buckets) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < buckets.size(); i++) {
            Map<String, Object> bucket = buckets.get(i);
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"start\":\"").append(bucket.get("start"))
                    .append("\",\"in\":").append(bucket.get("in"))
                    .append(",\"out\":").append(bucket.get("out"))
                    .append(",\"net\":").append(bucket.get("net")).append("}");
        }
        return json.append("]").toString();
    }
}
