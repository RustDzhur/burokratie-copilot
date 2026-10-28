package de.buerokratiecopilot.memory;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * <p>Only what is already recorded is shown, and every event names where it comes from: a deadline, an obligation of a
 * contract, an unpaid invoice, a task, a case, a letter still waiting for its answer. An event that cannot name its source
 * is not given out. What is not known is said so: a waiting reply has no date and is listed apart, a deadline nobody
 * confirmed carries that mark, a contract that renews but whose notice period is missing says that. Nothing is estimated.</p>
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
                                              UUID documentId, UUID caseId, UUID contractId) {
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
        map.put("contractId", contractId);
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
                                (UUID) row.get("id"), (UUID) row.get("source_document_id"), (UUID) row.get("case_id"), null));
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
                    (UUID) row.get("source_document_id"), (UUID) row.get("case_id"), null)));
        }

        if (has("contracts.view")) {
            jdbc.queryForList("""
                    select o.id, o.due_date, o.title, o.amount, o.currency, o.contract_id
                    from contract_obligations o join contracts c on c.id = o.contract_id
                    where o.tenant_id = ? and o.status = 'OPEN' and c.deleted_at is null and o.due_date between ? and ? """,
                    tenantId, today, to).forEach(row -> events.add(event("OBLIGATION", ((java.sql.Date) row.get("due_date")).toLocalDate(),
                    String.valueOf(row.get("title")), row.get("amount"), amountCurrency(row), "PLANNED",
                    row.get("amount") == null ? List.of("Betrag unbekannt") : List.of(), "CONTRACT_OBLIGATION",
                    (UUID) row.get("id"), null, null, (UUID) row.get("contract_id"))));

            jdbc.queryForList("""
                    select c.id, c.title, c.end_date, c.auto_renewal, c.renewal_period_months, c.notice_deadline_date,
                           c.notice_period_value, c.status
                    from contracts c where c.tenant_id = ? and c.deleted_at is null and c.status in ('ACTIVE', 'PENDING_TERMINATION')
                      and (c.notice_deadline_date between ? and ? or c.end_date between ? and ?) """,
                    tenantId, today, to, today, to).forEach(row -> {
                LocalDate notice = date(row.get("notice_deadline_date"));
                LocalDate end = date(row.get("end_date"));
                UUID id = (UUID) row.get("id");
                String title = String.valueOf(row.get("title"));
                if (notice != null && !notice.isBefore(today) && !notice.isAfter(to)) {
                    events.add(event("CONTRACT_NOTICE", notice, "Kündigungsfrist: " + title, null, null, "RECORDED", List.of(),
                            "CONTRACT", id, null, null, id));
                }
                if (end != null && !end.isBefore(today) && !end.isAfter(to)) {
                    List<String> unknown = new ArrayList<>();
                    if (Boolean.TRUE.equals(row.get("auto_renewal")) && notice == null) {
                        unknown.add("Der Vertrag verlängert sich, die Kündigungsfrist ist nicht erfasst");
                    }
                    if (row.get("auto_renewal") == null) {
                        unknown.add("Ob sich der Vertrag verlängert, ist nicht erfasst");
                    }
                    events.add(event("CONTRACT_END", end, "Vertragsende: " + title, null, null, "RECORDED", unknown, "CONTRACT", id,
                            null, null, id));
                }
            });
        }

        if (has("finance.view")) {
            jdbc.queryForList("""
                    select i.id, i.due_date, i.invoice_number, i.gross_amount, i.paid_amount, i.currency, i.document_id, i.contract_id,
                           i.case_id
                    from invoices i where i.tenant_id = ? and i.deleted_at is null and i.direction = 'INCOMING'
                      and i.payment_status in ('UNPAID', 'PARTIALLY_PAID') and i.due_date between ? and ? """,
                    tenantId, today, to).forEach(row -> {
                BigDecimal gross = (BigDecimal) row.get("gross_amount");
                BigDecimal open = gross == null ? null : gross.subtract((BigDecimal) row.get("paid_amount"));
                events.add(event("INVOICE_DUE", ((java.sql.Date) row.get("due_date")).toLocalDate(),
                        "Rechnung " + (row.get("invoice_number") == null ? "" : row.get("invoice_number")) + " fällig", open,
                        String.valueOf(row.get("currency")).trim(), "RECORDED", open == null ? List.of("Betrag unbekannt") : List.of(),
                        "INVOICE", (UUID) row.get("id"), (UUID) row.get("document_id"), (UUID) row.get("case_id"),
                        (UUID) row.get("contract_id")));
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
                            null, (UUID) row.get("id"), null)));

            List<Object> waitArgs = new ArrayList<>(List.of(tenantId));
            String waitGuard = caseGuard("k.case_id", userId, waitArgs);
            jdbc.queryForList("""
                    select k.id, k.description, k.case_id, k.outbound_message_id
                    from case_completion_criteria k join cases c on c.id = k.case_id
                    where k.tenant_id = ? and k.kind = 'REPLY_RECEIVED' and k.status = 'OPEN' and c.deleted_at is null
                      and c.status not in ('COMPLETED', 'CLOSED') """ + waitGuard, waitArgs.toArray())
                    .forEach(row -> undated.add(event("EXPECTED_REPLY", null, String.valueOf(row.get("description")), null, null,
                            "UNKNOWN_DATE", List.of("Wann die Antwort kommt, ist nicht bekannt"), "CASE_CRITERION",
                            (UUID) row.get("id"), null, (UUID) row.get("case_id"), null)));
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

    private static String amountCurrency(Map<String, Object> row) {
        return row.get("currency") == null ? null : String.valueOf(row.get("currency")).trim();
    }

    private static LocalDate date(Object value) {
        return value == null ? null : ((java.sql.Date) value).toLocalDate();
    }

    // ------------------------------------------------------------------------------------ what-if

    /** What a person wants to look at; {@code percent} and {@code days} are assumptions the person states. */
    public record Scenario(String type, UUID contractId, UUID invoiceId, BigDecimal percent, Integer days) {
    }

    private static int monthsPer(String interval) {
        return switch (interval) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            case "SEMIANNUAL" -> 6;
            case "YEARLY" -> 12;
            default -> 0;
        };
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
            case "NOTICE_MISSED" -> {
                Map<String, Object> c = contract(tenantId, scenario.contractId());
                LocalDate notice = date(c.get("notice_deadline_date"));
                LocalDate end = date(c.get("end_date"));
                Boolean auto = (Boolean) c.get("auto_renewal");
                Integer months = (Integer) c.get("renewal_period_months");
                BigDecimal cost = (BigDecimal) c.get("cost_amount");
                String interval = (String) c.get("cost_interval");
                if (notice == null) {
                    missing.add("Kündigungsfrist");
                }
                if (end == null) {
                    missing.add("Vertragsende");
                }
                if (auto == null) {
                    missing.add("automatische Verlängerung (ja/nein)");
                }
                else if (!auto) {
                    missing.add("Der Vertrag verlängert sich laut Erfassung nicht; dieses Szenario trifft nicht zu");
                }
                if (months == null) {
                    missing.add("Dauer der Verlängerung");
                }
                if (missing.isEmpty()) {
                    out.put("baseline", Map.of("endDate", end.toString(), "note", "Der Vertrag endet zum erfassten Datum, wenn rechtzeitig gekündigt wird"));
                    Map<String, Object> alt = new LinkedHashMap<>();
                    alt.put("endDate", end.plusMonths(months).toString());
                    alt.put("extraMonths", months);
                    if (cost != null && interval != null && monthsPer(interval) > 0) {
                        BigDecimal extra = cost.multiply(BigDecimal.valueOf(months)).divide(BigDecimal.valueOf(monthsPer(interval)), 2, RoundingMode.HALF_UP);
                        alt.put("extraCost", extra);
                        alt.put("currency", String.valueOf(c.get("cost_currency")).trim());
                    }
                    else {
                        alt.put("unknown", List.of("Kosten der Verlängerung (Betrag oder Zahlungsintervall nicht erfasst)"));
                    }
                    out.put("alternative", alt);
                    out.put("assumptions", List.of("Die erfasste Kündigungsfrist " + notice + " verstreicht ohne Kündigung"));
                }
            }
            case "PRICE_CHANGE" -> {
                Map<String, Object> c = contract(tenantId, scenario.contractId());
                BigDecimal cost = (BigDecimal) c.get("cost_amount");
                String interval = (String) c.get("cost_interval");
                if (scenario.percent() == null) {
                    missing.add("Prozentsatz der Preisänderung (Annahme der Person)");
                }
                if (cost == null) {
                    missing.add("Kosten des Vertrags");
                }
                if (interval == null || monthsPer(interval) == 0) {
                    missing.add("Zahlungsintervall des Vertrags");
                }
                if (missing.isEmpty()) {
                    BigDecimal factor = BigDecimal.valueOf(12).divide(BigDecimal.valueOf(monthsPer(interval)), 8, RoundingMode.HALF_UP);
                    BigDecimal yearly = cost.multiply(factor).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal changed = yearly.multiply(BigDecimal.ONE.add(scenario.percent().movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);
                    out.put("baseline", Map.of("yearlyCost", yearly));
                    out.put("alternative", Map.of("yearlyCost", changed));
                    out.put("difference", changed.subtract(yearly));
                    out.put("currency", String.valueOf(c.get("cost_currency")).trim());
                    out.put("assumptions", List.of("Preisänderung um " + scenario.percent().stripTrailingZeros().toPlainString() + " % (Annahme der Person)"));
                }
            }
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

    private Map<String, Object> contract(UUID tenantId, UUID contractId) {
        if (contractId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Vertrag fehlt");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select notice_deadline_date, end_date, auto_renewal, renewal_period_months, cost_amount, cost_currency, cost_interval
                from contracts where id = ? and tenant_id = ? and deleted_at is null
                """, contractId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Contract");
        }
        return rows.getFirst();
    }
}
