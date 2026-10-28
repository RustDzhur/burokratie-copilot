package de.buerokratiecopilot.agents.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService.DraftRequest;

/**
 * {@code dunning} — Mahnwesen.
 *
 * <p>Finds overdue outgoing invoices, groups them per customer, picks the dunning level by days
 * overdue and prepares one draft letter per customer and level through the mail module. Nothing is
 * ever sent: a draft is a draft, and only a person releases it.</p>
 *
 * <p>Idempotency: the draft carries an idempotency key per customer and level, and the mail module
 * refuses to create a second draft under the same key; the agent additionally checks for an existing
 * draft before creating one.</p>
 */
@Component
public class DunningAgent implements Agent {

    /** One dunning stage; {@code minDays} is the days-overdue threshold where the stage applies. */
    private record Level(int minDays, String label, BigDecimal feeEur) {
    }

    private static final List<Level> LEVELS = List.of(
            new Level(7, "Zahlungserinnerung", BigDecimal.ZERO),
            new Level(21, "1. Mahnung", new BigDecimal("5.00")),
            new Level(42, "2. Mahnung", new BigDecimal("10.00")));

    private final JdbcTemplate jdbc;
    private final OutboundMessageService outbound;

    public DunningAgent(JdbcTemplate jdbc, OutboundMessageService outbound) {
        this.jdbc = jdbc;
        this.outbound = outbound;
    }

    @Override
    public String id() {
        return "dunning";
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate today = LocalDate.ofInstant(context.now(), ZoneId.systemDefault());
        List<Map<String, Object>> rows = overdueInvoices(context.tenantId(), today);
        if (rows.isEmpty()) {
            return AgentResult.skipped(
                    "Mahnwesen: keine überfälligen Ausgangsrechnungen — nichts vorzubereiten.");
        }

        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = row.get("customer_id") == null ? "unassigned"
                    : String.valueOf(row.get("customer_id"));
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        int created = 0;
        int coveredInvoices = 0;
        int noEmail = 0;
        int alreadyDrafted = 0;
        int tooFresh = 0;
        List<AgentResult.Produced> produced = new ArrayList<>();

        for (List<Map<String, Object>> invoices : groups.values()) {
            String email = firstNonBlank(invoices, "email");
            if (email == null) {
                noEmail += invoices.size();
                continue;
            }
            long maxDays = 0;
            for (Map<String, Object> row : invoices) {
                LocalDate due = FinanceSupport.toLocalDate(row.get("due_date"));
                if (due != null) {
                    maxDays = Math.max(maxDays, ChronoUnit.DAYS.between(due, today));
                }
            }
            int levelIndex = levelFor(maxDays);
            if (levelIndex < 0) {
                tooFresh += invoices.size();
                continue;
            }
            Level level = LEVELS.get(levelIndex);
            String customerKey = invoices.getFirst().get("customer_id") == null ? "unassigned"
                    : String.valueOf(invoices.getFirst().get("customer_id"));
            String idempotencyKey = "dunning-v1:" + customerKey + ":L" + levelIndex;

            List<UUID> existing = jdbc.query("""
                    select id from outbound_messages
                    where tenant_id = ? and idempotency_key = ? and deleted_at is null
                    """, (rs, rowNum) -> rs.getObject("id", UUID.class), context.tenantId(),
                    idempotencyKey);
            if (!existing.isEmpty()) {
                alreadyDrafted += invoices.size();
                continue;
            }

            String customerName = firstNonBlank(invoices, "customer_name");
            UUID messageId = outbound.draft(new DraftRequest(context.tenantId(), null, "EMAIL",
                    level.label() + " – offene Rechnungen", body(invoices, level), null, null,
                    List.of(email), List.of(), null, idempotencyKey, context.actorUserId()));
            created++;
            coveredInvoices += invoices.size();
            produced.add(new AgentResult.Produced("dunning-draft", messageId.toString(),
                    level.label() + " an " + (customerName == null ? email : customerName) + " ("
                            + invoices.size() + " Rechnung(en))"));
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("created", String.valueOf(created));
        meta.put("coveredInvoices", String.valueOf(coveredInvoices));
        meta.put("noEmail", String.valueOf(noEmail));
        meta.put("alreadyDrafted", String.valueOf(alreadyDrafted));
        meta.put("tooFresh", String.valueOf(tooFresh));
        if (created == 0) {
            return new AgentResult(false,
                    "Mahnwesen: keine neuen Entwürfe (" + alreadyDrafted + " bereits erstellt, " + noEmail
                            + " ohne hinterlegte E-Mail, " + tooFresh + " unterhalb der ersten Mahnstufe).",
                    produced, meta);
        }
        return AgentResult.done(
                "Mahnwesen: " + created + " Entwurf/Entwürfe für " + coveredInvoices
                        + " Rechnung(en) vorbereitet (kein Versand).",
                produced, meta);
    }

    private List<Map<String, Object>> overdueInvoices(UUID tenantId, LocalDate today) {
        return jdbc.queryForList("""
                select i.id, i.invoice_number, i.gross_amount, i.paid_amount, i.due_date,
                       i.customer_id, c.email::text as email, c.name as customer_name
                from invoices i
                left join customers c on c.id = i.customer_id
                where i.tenant_id = ? and i.direction = 'OUTGOING' and i.deleted_at is null
                  and i.due_date < ?
                  and i.status in ('APPROVED', 'SCHEDULED', 'PARTIALLY_PAID', 'OVERDUE')
                  and i.payment_status in ('UNPAID', 'PARTIALLY_PAID')
                  and coalesce(i.gross_amount, 0) - coalesce(i.paid_amount, 0) > 0
                order by i.due_date
                """, tenantId, today);
    }

    private static int levelFor(long daysOverdue) {
        int level = -1;
        for (int i = 0; i < LEVELS.size(); i++) {
            if (daysOverdue >= LEVELS.get(i).minDays()) {
                level = i;
            }
        }
        return level;
    }

    private static String firstNonBlank(List<Map<String, Object>> rows, String column) {
        for (Map<String, Object> row : rows) {
            Object value = row.get(column);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String body(List<Map<String, Object>> invoices, Level level) {
        StringBuilder body = new StringBuilder();
        body.append("Sehr geehrte Damen und Herren,\n\n");
        body.append("unsere Unterlagen zeigen folgende noch offene Rechnungen:\n\n");
        for (Map<String, Object> row : invoices) {
            BigDecimal gross = FinanceSupport.nvl(row.get("gross_amount"));
            BigDecimal paid = FinanceSupport.nvl(row.get("paid_amount"));
            BigDecimal open = gross.subtract(paid);
            body.append("- ").append(row.get("invoice_number")).append(", fällig am ")
                    .append(row.get("due_date")).append(", offen ")
                    .append(FinanceSupport.money(open)).append(" EUR\n");
        }
        body.append("\nBitte überweisen Sie den offenen Betrag innerhalb von 14 Tagen auf das bekannte Konto.");
        if (level.feeEur().signum() > 0) {
            body.append("\nZusätzlich fällt eine Mahngebühr in Höhe von ")
                    .append(FinanceSupport.money(level.feeEur())).append(" EUR an.");
        }
        body.append("\n\nMit freundlichen Grüßen");
        return body.toString();
    }
}
