package de.buerokratiecopilot.agents.ecommerce;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code ecommerce-payment-check} — Zahlungsprüfung.
 *
 * <p>Works on the platform's own receivables and payment attempts: outgoing invoices that are still
 * open and past their due date, plus failed payment attempts recorded against them. The rule is
 * deliberately conservative — a reminder is prepared for an ordinary overdue invoice, a manual
 * cancellation review only after the configured number of failed attempts. The agent never books a
 * payment and never cancels an order.</p>
 */
@Component
public class EcommercePaymentCheckAgent implements Agent {

    private static final int MAX_INVOICES = 200;

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public EcommercePaymentCheckAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "ecommerce-payment-check";
    }

    @Override
    public AgentResult run(AgentContext context) {
        int retryLimit = AgentInputs.integer(context.input("retryLimit"), 3, 1, 20);
        java.time.Instant since = AgentInputs.instant(context.input("since"));
        if (context.input("since") != null && !context.input("since").isBlank() && since == null) {
            return AgentResult.failed("Zahlungsprüfung: 'since' ist kein gültiger Zeitpunkt.");
        }
        List<UUID> orderIds = AgentInputs.uuidList(context.input("orderIds"));
        String provider = AgentInputs.clean(context.input("paymentProvider"));

        StringBuilder sql = new StringBuilder("""
                select i.id, i.invoice_number, i.due_date, i.gross_amount, i.paid_amount, i.currency,
                       i.status, i.payment_status,
                       (select count(*) from payments p
                         where p.invoice_id = i.id and p.status = 'FAILED'
                           and p.tenant_id = i.tenant_id) as failed_attempts,
                       (select max(p.failure_reason) from payments p
                         where p.invoice_id = i.id and p.status = 'FAILED'
                           and p.tenant_id = i.tenant_id) as failure_reason
                from invoices i
                where i.tenant_id = ? and i.direction = 'OUTGOING' and i.deleted_at is null
                  and i.payment_status in ('UNPAID', 'PARTIALLY_PAID')
                  and i.due_date is not null and i.due_date < current_date
                """);
        List<Object> params = new ArrayList<>(List.of(context.tenantId()));
        if (!orderIds.isEmpty()) {
            sql.append(" and i.id in (");
            sql.append(String.join(",", orderIds.stream().map(id -> "?").toList()));
            sql.append(")");
            params.addAll(orderIds);
        }
        if (since != null) {
            sql.append(" and i.created_at >= ?");
            params.add(java.sql.Timestamp.from(since));
        }
        if (provider != null) {
            sql.append(" and i.metadata->>'paymentProvider' = ?");
            params.add(provider);
        }
        sql.append(" order by i.due_date limit ").append(MAX_INVOICES);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        if (rows.isEmpty()) {
            return AgentResult.skipped("Zahlungsprüfung: keine überfälligen offenen "
                    + "Ausgangsrechnungen im geprüften Datenbestand.");
        }

        List<AgentResult.Produced> produced = new ArrayList<>();
        int reminders = 0;
        int cancellations = 0;
        int createdTasks = 0;
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        for (Map<String, Object> row : rows) {
            UUID invoiceId = (UUID) row.get("id");
            String number = row.get("invoice_number") == null ? invoiceId.toString()
                    : String.valueOf(row.get("invoice_number"));
            BigDecimal gross = row.get("gross_amount") == null ? BigDecimal.ZERO
                    : (BigDecimal) row.get("gross_amount");
            BigDecimal paid = row.get("paid_amount") == null ? BigDecimal.ZERO
                    : (BigDecimal) row.get("paid_amount");
            BigDecimal open = gross.subtract(paid);
            int failed = row.get("failed_attempts") == null ? 0
                    : ((Number) row.get("failed_attempts")).intValue();
            LocalDate due = AgentInputs.date(row.get("due_date"));
            long daysOverdue = due == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(due, today);
            boolean cancelReview = failed >= retryLimit;
            String action = cancelReview ? "CANCEL_REVIEW" : "REMINDER";
            Task.Priority priority = cancelReview || daysOverdue > 14
                    ? Task.Priority.HIGH : Task.Priority.NORMAL;
            String title = (cancelReview ? "Storno prüfen: " : "Zahlungserinnerung: ")
                    + number + " / " + open + " " + row.get("currency");
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    "ecommerce-payment-check:" + invoiceId + ":" + action, title,
                    "Rechnung " + number + ", offen " + open + " " + row.get("currency")
                            + ", überfällig seit " + daysOverdue + " Tag(en)."
                            + "\nFehlgeschlagene Zahlungsversuche: " + failed
                            + (row.get("failure_reason") == null ? ""
                                    : "\nLetzter Fehlergrund: " + row.get("failure_reason"))
                            + (cancelReview
                                    ? "\n\nDer Agent storniert nicht. Bitte Storno fachlich prüfen "
                                            + "und freigeben."
                                    : "\n\nDer Agent bucht keine Zahlung; Erinnerung vorbereiten."),
                    priority, context.now().plus(Duration.ofDays(cancelReview ? 1 : 3)),
                    List.of("ecommerce", "payment", action.toLowerCase(java.util.Locale.ROOT)),
                    Map.of("invoiceId", invoiceId.toString(), "action", action,
                            "failedAttempts", failed, "daysOverdue", daysOverdue));
            if (cancelReview) {
                cancellations++;
            }
            else {
                reminders++;
            }
            if (task.created()) {
                createdTasks++;
                produced.add(new AgentResult.Produced("task", task.id().toString(), title));
            }
            produced.add(new AgentResult.Produced("invoice", invoiceId.toString(),
                    number + ": " + action + " (" + open + " offen)"));
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("scanned", String.valueOf(rows.size()));
        meta.put("reminders", String.valueOf(reminders));
        meta.put("cancellationReviews", String.valueOf(cancellations));
        meta.put("tasksCreated", String.valueOf(createdTasks));
        meta.put("retryLimit", String.valueOf(retryLimit));
        meta.put("autoCancel", "false");
        return AgentResult.done("Zahlungsprüfung: " + rows.size() + " überfällige Rechnung(en), "
                + reminders + " Erinnerung(en) und " + cancellations + " Storno-Prüfung(en) "
                + "vorbereitet; keine Zahlung gebucht, kein Storno ausgeführt.", produced, meta);
    }
}
