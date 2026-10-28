package de.buerokratiecopilot.agents.logistics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code logistics-supplier-scorecard} — Lieferantenbewertung.
 *
 * <p>Scores suppliers only on metrics the platform actually records: open/late purchase orders and
 * overdue incoming invoices in the period. On-time delivery rate, completeness, complaints and
 * reaction time are <strong>not</strong> measurable without goods-receipt and complaint records;
 * the report names this gap instead of inventing values. The score is a decision aid, never an
 * automatic exclusion.</p>
 */
@Component
public class SupplierScorecardAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public SupplierScorecardAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "logistics-supplier-scorecard";
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate from = AgentInputs.date(context.input("periodFrom"));
        LocalDate to = AgentInputs.date(context.input("periodTo"));
        if (from == null || to == null || to.isBefore(from)) {
            return AgentResult.skipped("Lieferantenbewertung: 'periodFrom' und 'periodTo' sind als "
                    + "gültiger Zeitraum erforderlich.");
        }
        List<UUID> supplierIds = AgentInputs.uuidList(context.input("supplierIds"));
        Weights weights = weights(context.input("weights"));
        StringBuilder sql = new StringBuilder("""
                select s.id, s.name,
                       (select count(*) from purchase_orders po
                         where po.supplier_id = s.id and po.tenant_id = s.tenant_id
                           and po.deleted_at is null and po.order_date between ? and ?) as order_count,
                       (select coalesce(sum(po.gross_amount), 0) from purchase_orders po
                         where po.supplier_id = s.id and po.tenant_id = s.tenant_id
                           and po.deleted_at is null and po.order_date between ? and ?) as order_volume,
                       (select count(*) from purchase_orders po
                         where po.supplier_id = s.id and po.tenant_id = s.tenant_id
                           and po.deleted_at is null
                           and po.expected_delivery_date < current_date
                           and po.status not in ('RECEIVED', 'CANCELLED', 'CLOSED')) as overdue_orders,
                       (select count(*) from invoices i
                         where i.supplier_id = s.id and i.tenant_id = s.tenant_id
                           and i.deleted_at is null and i.direction = 'INCOMING'
                           and i.issue_date between ? and ?) as invoice_count,
                       (select coalesce(sum(i.gross_amount), 0) from invoices i
                         where i.supplier_id = s.id and i.tenant_id = s.tenant_id
                           and i.deleted_at is null and i.direction = 'INCOMING'
                           and i.issue_date between ? and ?) as invoice_volume,
                       (select count(*) from invoices i
                         where i.supplier_id = s.id and i.tenant_id = s.tenant_id
                           and i.deleted_at is null and i.direction = 'INCOMING'
                           and i.due_date < current_date
                           and i.payment_status in ('UNPAID', 'PARTIALLY_PAID')) as overdue_invoices
                from suppliers s
                where s.tenant_id = ? and s.deleted_at is null
                """);
        List<Object> params = new ArrayList<>(List.of(from, to, from, to, from, to, from, to,
                context.tenantId()));
        if (!supplierIds.isEmpty()) {
            sql.append(" and s.id in (").append(String.join(",",
                    supplierIds.stream().map(id -> "?").toList())).append(")");
            params.addAll(supplierIds);
        }
        sql.append(" order by s.name");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        List<Scorecard> scorecards = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            int orders = number(row.get("order_count"));
            int overdueOrders = number(row.get("overdue_orders"));
            int invoices = number(row.get("invoice_count"));
            int overdueInvoices = number(row.get("overdue_invoices"));
            if (orders + invoices == 0) {
                continue;
            }
            Scorecard scorecard = score(row, orders, overdueOrders, invoices, overdueInvoices, weights);
            scorecards.add(scorecard);
        }
        if (scorecards.isEmpty()) {
            return AgentResult.skipped("Lieferantenbewertung: keine Bestellung und keine "
                    + "Eingangsrechnung im Zeitraum " + from + " bis " + to + ".");
        }
        scorecards.sort(Comparator.comparingDouble(Scorecard::score));
        String sourceKey = "logistics-supplier-scorecard:" + from + ":" + to + ":"
                + AgentInputs.key(scorecards.stream().map(s -> s.supplierId().toString()).toList().toString());
        String html = html(from, to, weights, scorecards);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Lieferantenbewertung " + from + " bis " + to, html,
                Map.of("from", from.toString(), "to", to.toString(),
                        "suppliers", scorecards.size()), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Lieferantenbewertung");
        }
        int tasks = 0;
        for (Scorecard scorecard : scorecards.stream().filter(s -> s.score() < 60).limit(20).toList()) {
            String title = "Lieferant prüfen: " + scorecard.name();
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    sourceKey + ":supplier:" + scorecard.supplierId(), title,
                    "Lieferant: " + scorecard.name()
                            + "\nZeitraum: " + from + " bis " + to
                            + "\nScore: " + scorecard.score() + "/100"
                            + "\nBestellungen: " + scorecard.orders() + ", davon überfällig "
                            + scorecard.overdueOrders()
                            + "\nEingangsrechnungen: " + scorecard.invoices() + ", davon überfällig "
                            + scorecard.overdueInvoices()
                            + "\n\nDie Bewertung ist eine Entscheidungshilfe, kein automatisches "
                            + "Ausschlusskriterium. Termintreue/Reklamationen sind mangels "
                            + "Wareneingangsdaten nicht messbar.",
                    Task.Priority.NORMAL, context.now().plus(Duration.ofDays(7)),
                    List.of("logistics", "supplier"),
                    Map.of("supplierId", scorecard.supplierId().toString(),
                            "score", scorecard.score()));
            if (task.created()) {
                tasks++;
                context.addProduced("task", task.id().toString(), title);
            }
            context.addProduced("supplier", scorecard.supplierId().toString(),
                    scorecard.name() + ": Score " + scorecard.score() + "/100");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("suppliers", String.valueOf(scorecards.size()));
        meta.put("tasksCreated", String.valueOf(tasks));
        meta.put("weights", weights.describe());
        meta.put("missingMetrics", "Termintreue, Vollständigkeit, Reklamationsquote, Reaktionszeit");
        return AgentResult.done("Lieferantenbewertung " + from + " bis " + to + ": "
                + scorecards.size() + " Lieferant(en) bewertet, " + tasks
                + " zur Prüfung vorgelegt; nicht messbare Kennzahlen sind ausgewiesen.",
                context.produced(), meta);
    }

    /** Computes the score from measurable rates; pure and testable. */
    public static Scorecard score(Map<String, Object> row, int orders, int overdueOrders,
                                  int invoices, int overdueInvoices, Weights weights) {
        double orderPenalty = orders == 0 ? 0
                : (double) overdueOrders / orders * 100 * weights.timeliness();
        double invoicePenalty = invoices == 0 ? 0
                : (double) overdueInvoices / invoices * 100 * weights.payment();
        int score = (int) Math.max(0, Math.round(100 - orderPenalty - invoicePenalty));
        BigDecimal orderVolume = decimal(row.get("order_volume"));
        BigDecimal invoiceVolume = decimal(row.get("invoice_volume"));
        return new Scorecard((UUID) row.get("id"), String.valueOf(row.get("name")), orders,
                overdueOrders, invoices, overdueInvoices, score, orderVolume, invoiceVolume);
    }

    private static Weights weights(String raw) {
        double timeliness = 0.6;
        double payment = 0.4;
        if (raw != null && !raw.isBlank()) {
            try {
                Map<String, Object> map = new ObjectMapper().readValue(raw,
                        new TypeReference<Map<String, Object>>() {
                        });
                timeliness = decimal(map.get("timeliness")) == null ? timeliness
                        : decimal(map.get("timeliness")).doubleValue();
                payment = decimal(map.get("payment")) == null ? payment
                        : decimal(map.get("payment")).doubleValue();
            }
            catch (Exception ignored) {
                // default weights
            }
        }
        double total = timeliness + payment;
        if (total <= 0) {
            timeliness = 0.6;
            payment = 0.4;
            total = 1;
        }
        return new Weights(timeliness / total, payment / total);
    }

    private static String html(LocalDate from, LocalDate to, Weights weights, List<Scorecard> scorecards) {
        StringBuilder html = new StringBuilder("<h1>Lieferantenbewertung ").append(from).append(" bis ")
                .append(to).append("</h1><p>Gewichtung: Termintreue ")
                .append(Math.round(weights.timeliness() * 100)).append("%, Zahlungsverhalten ")
                .append(Math.round(weights.payment() * 100)).append("%. Nur messbare Kennzahlen "
                        + "fließen ein.</p><table><tr><th>Lieferant</th><th>Score</th>"
                        + "<th>Bestellungen</th><th>überfällig</th><th>Rechnungen</th>"
                        + "<th>überfällig</th><th>Volumen</th></tr>");
        for (Scorecard scorecard : scorecards) {
            html.append("<tr><td>").append(AgentInputs.html(scorecard.name())).append("</td><td>")
                    .append(scorecard.score()).append("</td><td>").append(scorecard.orders())
                    .append("</td><td>").append(scorecard.overdueOrders()).append("</td><td>")
                    .append(scorecard.invoices()).append("</td><td>")
                    .append(scorecard.overdueInvoices()).append("</td><td>")
                    .append(scorecard.orderVolume()).append(" / ")
                    .append(scorecard.invoiceVolume()).append("</td></tr>");
        }
        html.append("</table><h2>Nicht messbare Kennzahlen</h2><p>Termintreue im Sinne von "
                + "Wareneingang, Vollständigkeit, Reklamationsquote und Reaktionszeit lassen sich "
                + "nicht berechnen, weil kein Wareneingangs- und Reklamationsdatum erfasst ist. "
                + "Sie werden nicht geschätzt.</p><p><em>Entscheidungshilfe, kein automatisches "
                + "Ausschlusskriterium.</em></p>");
        return html.toString();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
        }
        catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    /** The weights actually used, normalised to 1. */
    public record Weights(double timeliness, double payment) {

        String describe() {
            return "timeliness=" + Math.round(timeliness * 100) + "%,payment="
                    + Math.round(payment * 100) + "%";
        }
    }

    /** One supplier score. */
    public record Scorecard(UUID supplierId, String name, int orders, int overdueOrders,
                            int invoices, int overdueInvoices, int score, BigDecimal orderVolume,
                            BigDecimal invoiceVolume) {
    }
}
