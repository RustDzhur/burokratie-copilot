package de.buerokratiecopilot.memory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The Future Timeline (SM-01) and the read-only what-if scenarios (SM-03).
 *
 * <p>Only what is already recorded is shown, and every event names where it comes from: a deadline, an unpaid invoice, a
 * task, a case, a letter still waiting for its answer. An event that cannot name its source is not given out. What is not
 * known is said so: a waiting reply has no date and is listed apart, a deadline nobody confirmed carries that mark.
 * Nothing is estimated.</p>
 *
 * <p>A scenario is arithmetic over recorded values and the assumption the person states. It writes nothing, and when a value
 * it needs is missing it answers {@code INSUFFICIENT_DATA} and lists what is missing instead of a number.</p>
 */
@Service
public class FutureService {

    public static final Set<Integer> HORIZONS = Set.of(30, 60, 90);
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    private final JdbcTemplate jdbc;

    public FutureService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Map<String, Object> event(String kind, LocalDate date, String title, Object amount, String currency,
                                              String certainty, List<String> unknown, String sourceType, UUID sourceId,
                                              UUID documentId, UUID caseId) {
        if (sourceId == null) {
            throw new IllegalStateException("An event without a source is not given out");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", kind + ":" + sourceId);
        map.put("kind", kind);
        map.put("date", date == null ? null : date.toString());
        map.put("title", title);
        map.put("amount", amount);
        map.put("currency", currency);
        map.put("certainty", certainty);
        map.put("unknown", unknown);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", sourceType);
        source.put("id", sourceId);
        map.put("source", source);
        map.put("documentId", documentId);
        map.put("caseId", caseId);
        return map;
    }

    private static boolean has(String permission) {
        return ReaderRights.held().contains(permission);
    }

    /** The SQL that limits cases to those the reader may see; the arguments are appended in order. */
    private static String caseGuard(String column, UUID userId, List<Object> args) {
        if (ReaderRights.seesEveryCase()) {
            return "";
        }
        args.add(userId);
        args.add(userId);
        args.add(userId);
        return " and (" + column + " is null or exists (select 1 from cases vc where vc.id = " + column
                + " and (vc.owner_user_id = ? or vc.responsible_user_id = ? or exists (select 1 from case_participants p"
                + " where p.case_id = vc.id and p.user_id = ? and p.revoked_at is null"
                + " and (p.expires_at is null or p.expires_at > now()))))) ";
    }

    @Transactional(readOnly = true)
    public Map<String, Object> future(UUID tenantId, UUID userId, int horizon) {
        if (!HORIZONS.contains(horizon)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Zeitraum muss 30, 60 oder 90 Tage sein");
        }
        LocalDate today = LocalDate.now(ZONE);
        LocalDate to = today.plusDays(horizon);
        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> undated = new ArrayList<>();

        if (has("tasks.view")) {
            List<Object> args = new ArrayList<>(List.of(tenantId, today, to));
            String guard = caseGuard("t.case_id", userId, args);
            jdbc.queryForList("""
                    select d.id, coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date) as day, t.title, d.deadline_type,
                           d.confirmed_at, d.requires_verification, d.source_document_id, t.case_id
                    from task_deadlines d join tasks t on t.id = d.task_id
                    where d.tenant_id = ? and d.is_active and t.deleted_at is null and t.status not in ('DONE', 'CANCELLED')
                      and coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date) between ? and ? """ + guard, args.toArray())
                    .forEach(row -> {
                        boolean confirmed = row.get("confirmed_at") != null;
                        List<String> unknown = confirmed ? List.of() : List.of("Frist nicht bestätigt: bitte prüfen");
                        events.add(event("DEADLINE", ((java.sql.Date) row.get("day")).toLocalDate(), String.valueOf(row.get("title")),
                                null, null, confirmed ? "CONFIRMED" : "UNCONFIRMED", unknown, "TASK_DEADLINE",
                                (UUID) row.get("id"), (UUID) row.get("source_document_id"), (UUID) row.get("case_id")));
                    });

            List<Object> taskArgs = new ArrayList<>(List.of(tenantId, today, to));
            String taskGuard = caseGuard("t.case_id", userId, taskArgs);
            jdbc.queryForList("""
                    select t.id, (t.due_at at time zone 'Europe/Berlin')::date as day, t.title, t.case_id, t.source_document_id
                    from tasks t where t.tenant_id = ? and t.deleted_at is null and t.status not in ('DONE', 'CANCELLED')
                      and t.due_at is not null and (t.due_at at time zone 'Europe/Berlin')::date between ? and ?
                      and not exists (select 1 from task_deadlines d where d.task_id = t.id and d.is_active) """ + taskGuard,
                    taskArgs.toArray()).forEach(row -> events.add(event("TASK", ((java.sql.Date) row.get("day")).toLocalDate(),
                    String.valueOf(row.get("title")), null, null, "PLANNED", List.of(), "TASK", (UUID) row.get("id"),
                    (UUID) row.get("source_document_id"), (UUID) row.get("case_id"))));
        }

        if (has("finance.view")) {
            jdbc.queryForList("""
                    select i.id, i.due_date, i.invoice_number, i.gross_amount, i.paid_amount, i.currency, i.document_id,
                           i.case_id
                    from invoices i where i.tenant_id = ? and i.deleted_at is null and i.direction = 'INCOMING'
                      and i.payment_status in ('UNPAID', 'PARTIALLY_PAID') and i.due_date between ? and ? """,
                    tenantId, today, to).forEach(row -> {
                BigDecimal gross = (BigDecimal) row.get("gross_amount");
                BigDecimal open = gross == null ? null : gross.subtract((BigDecimal) row.get("paid_amount"));
                events.add(event("INVOICE_DUE", ((java.sql.Date) row.get("due_date")).toLocalDate(),
                        "Rechnung " + (row.get("invoice_number") == null ? "" : row.get("invoice_number")) + " fällig", open,
                        String.valueOf(row.get("currency")).trim(), "RECORDED", open == null ? List.of("Betrag unbekannt") : List.of(),
                        "INVOICE", (UUID) row.get("id"), (UUID) row.get("document_id"), (UUID) row.get("case_id")));
            });
        }

        if (has("cases.view")) {
            List<Object> args = new ArrayList<>(List.of(tenantId, today, to));
            String guard = caseGuard("c.id", userId, args);
            jdbc.queryForList("""
                    select c.id, (c.due_at at time zone 'Europe/Berlin')::date as day, c.title, c.reference
                    from cases c where c.tenant_id = ? and c.deleted_at is null and c.status not in ('COMPLETED', 'CLOSED')
                      and c.due_at is not null and (c.due_at at time zone 'Europe/Berlin')::date between ? and ? """ + guard, args.toArray())
                    .forEach(row -> events.add(event("CASE_DUE", ((java.sql.Date) row.get("day")).toLocalDate(),
                            row.get("reference") + " " + row.get("title"), null, null, "PLANNED", List.of(), "CASE", (UUID) row.get("id"),
                            null, (UUID) row.get("id"))));

            List<Object> waitArgs = new ArrayList<>(List.of(tenantId));
            String waitGuard = caseGuard("k.case_id", userId, waitArgs);
            jdbc.queryForList("""
                    select k.id, k.description, k.case_id, k.outbound_message_id
                    from case_completion_criteria k join cases c on c.id = k.case_id
                    where k.tenant_id = ? and k.kind = 'REPLY_RECEIVED' and k.status = 'OPEN' and c.deleted_at is null
                      and c.status not in ('COMPLETED', 'CLOSED') """ + waitGuard, waitArgs.toArray())
                    .forEach(row -> undated.add(event("EXPECTED_REPLY", null, String.valueOf(row.get("description")), null, null,
                            "UNKNOWN_DATE", List.of("Wann die Antwort kommt, ist nicht bekannt"), "CASE_CRITERION",
                            (UUID) row.get("id"), null, (UUID) row.get("case_id"))));
        }

        events.sort(Comparator.comparing((Map<String, Object> e) -> String.valueOf(e.get("date"))).thenComparing(e -> String.valueOf(e.get("id"))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("horizon", horizon);
        result.put("from", today.toString());
        result.put("to", to.toString());
        result.put("events", events);
        result.put("undated", undated);
        return result;
    }

    private static LocalDate date(Object value) {
        return value == null ? null : ((java.sql.Date) value).toLocalDate();
    }

    // ------------------------------------------------------------------------------------ what-if

    /** What a person wants to look at; {@code days} is an assumption the person states. */
    public record Scenario(String type, UUID invoiceId, Integer days) {
    }

    @Transactional(readOnly = true)
    public Map<String, Object> scenario(UUID tenantId, Scenario scenario) {
        if (scenario == null || scenario.type() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Szenariotyp fehlt");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", scenario.type());
        out.put("readOnly", true);
        List<String> missing = new ArrayList<>();
        switch (scenario.type()) {
            case "PAYMENT_LATE" -> {
                if (scenario.invoiceId() == null) {
                    throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Rechnung fehlt");
                }
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "select due_date, gross_amount, paid_amount, currency from invoices where id = ? and tenant_id = ? and deleted_at is null",
                        scenario.invoiceId(), tenantId);
                if (rows.isEmpty()) {
                    throw ApiException.notFound("Invoice");
                }
                LocalDate due = date(rows.getFirst().get("due_date"));
                if (due == null) {
                    missing.add("Fälligkeitsdatum der Rechnung");
                }
                if (scenario.days() == null || scenario.days() < 1) {
                    missing.add("Anzahl der Tage Verzug (Annahme der Person)");
                }
                if (missing.isEmpty()) {
                    out.put("baseline", Map.of("paidOn", due.toString()));
                    out.put("alternative", Map.of("paidOn", due.plusDays(scenario.days()).toString(), "daysLate", scenario.days()));
                    // What a late payment costs depends on the agreement and the sender's terms; none is guessed.
                    out.put("unknown", List.of("Mahngebühren und Verzugszinsen: nicht berechnet, weil sie vom Vertrag und den Bedingungen des Absenders abhängen"));
                    out.put("assumptions", List.of("Zahlung " + scenario.days() + " Tage nach Fälligkeit (Annahme der Person)"));
                }
            }
            default -> throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Szenariotyp: " + scenario.type());
        }
        if (!missing.isEmpty()) {
            out.put("status", "INSUFFICIENT_DATA");
            out.put("missing", missing);
        }
        else {
            out.put("status", "COMPUTED");
        }
        return out;
    }
}
