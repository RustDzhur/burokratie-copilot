package de.buerokratiecopilot.agents.banking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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

/**
 * {@code banking-fee-audit} — Kontogebühren-Audit.
 *
 * <p>Reads the bank transactions of the period and finds debits whose purpose, counterparty or
 * reference contains a fee keyword. Debits are grouped by counterparty, so a monthly account fee is
 * one line with twelve bookings instead of twelve findings. Read-only: nothing is booked, disputed
 * or refunded.</p>
 */
@Component
public class BankFeeAuditAgent implements Agent {

    static final int DEFAULT_LOOKBACK_DAYS = 365;
    static final List<String> DEFAULT_KEYWORDS = List.of("entgelt", "gebühr", "gebuehr",
            "kontoführung", "kontofuehrung", "buchung", "auslandseinsatz", "dispo", "sepa",
            "rücklastschrift", "ruecklastschrift", "servicepauschale");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final int MAX_GROUPS = 30;

    private final JdbcTemplate jdbc;

    public BankFeeAuditAgent(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "banking-fee-audit";
    }

    /** One debit as it was read from the bank transaction. */
    public record FeeRow(UUID id, LocalDate bookingDate, BigDecimal amount, String counterparty,
                         String purpose) {
    }

    /** All fee-like debits to one counterparty, summed. */
    public record FeeGroup(String counterparty, int bookings, BigDecimal total, LocalDate firstDate,
                           LocalDate lastDate) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate today = LocalDate.ofInstant(context.now(), BERLIN);
        LocalDate from;
        LocalDate to;
        try {
            from = dateOr(context.input("periodFrom"), today.minusDays(DEFAULT_LOOKBACK_DAYS));
            to = dateOr(context.input("periodTo"), today);
        } catch (DateTimeParseException e) {
            return AgentResult.failed("Der Zeitraum ist ungültig: " + e.getParsedString());
        }
        if (to.isBefore(from)) {
            return AgentResult.failed("Das Ende liegt vor dem Anfang.");
        }
        UUID accountId = null;
        if (context.input("accountId") != null && !context.input("accountId").isBlank()) {
            try {
                accountId = UUID.fromString(context.input("accountId").strip());
            } catch (IllegalArgumentException e) {
                return AgentResult.failed("Die Konto-ID ist keine gültige UUID.");
            }
        }
        List<String> keywords = keywords(context.input("keywords"));
        BigDecimal tolerance = amountOr(context.input("toleranceAmount"), new BigDecimal("1.00"));

        List<FeeRow> rows = new ArrayList<>();
        for (FeeRow row : load(context.tenantId(), from, to, accountId)) {
            String text = row.counterparty() + " " + row.purpose();
            if (matches(text, keywords)) {
                rows.add(row);
            }
        }
        List<FeeGroup> groups = summarize(rows, tolerance);
        if (groups.isEmpty()) {
            return AgentResult.skipped("Im Zeitraum " + from + " bis " + to + " wurden keine "
                    + "Gebührenbuchungen gefunden.");
        }

        for (FeeGroup group : groups.stream().limit(MAX_GROUPS).toList()) {
            context.addProduced("fee-group", group.counterparty(),
                    group.counterparty() + ": " + group.total().toPlainString() + " EUR aus "
                            + group.bookings() + " Buchung(en) " + group.firstDate() + " bis "
                            + group.lastDate());
        }
        BigDecimal total = groups.stream().map(FeeGroup::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("periodFrom", from.toString());
        meta.put("periodTo", to.toString());
        meta.put("transactions", String.valueOf(rows.size()));
        meta.put("groups", String.valueOf(groups.size()));
        meta.put("totalAmount", total.setScale(2, RoundingMode.HALF_UP).toPlainString());
        meta.put("keywords", String.join(",", keywords));
        return AgentResult.done("Gebühren-Audit für " + from + " bis " + to + ": " + rows.size()
                        + " Buchung(en) in " + groups.size() + " Empfängergruppe(n), zusammen "
                        + total.setScale(2, RoundingMode.HALF_UP).toPlainString() + " EUR.",
                context.produced(), meta);
    }

    private List<FeeRow> load(UUID tenantId, LocalDate from, LocalDate to, UUID accountId) {
        String base = """
                select id, booking_date, amount, counterparty_name, purpose
                from bank_transactions
                where tenant_id = ? and amount < 0 and booking_date between ? and ?
                """;
        if (accountId == null) {
            return jdbc.query(base + " order by booking_date",
                    (rs, row) -> map(rs.getObject("id", UUID.class),
                            rs.getDate("booking_date").toLocalDate(), rs.getBigDecimal("amount"),
                            rs.getString("counterparty_name"), rs.getString("purpose")),
                    tenantId, Date.valueOf(from), Date.valueOf(to));
        }
        return jdbc.query(base + " and financial_account_id = ? order by booking_date",
                (rs, row) -> map(rs.getObject("id", UUID.class),
                        rs.getDate("booking_date").toLocalDate(), rs.getBigDecimal("amount"),
                        rs.getString("counterparty_name"), rs.getString("purpose")),
                tenantId, Date.valueOf(from), Date.valueOf(to), accountId);
    }

    private static FeeRow map(UUID id, LocalDate date, BigDecimal amount, String counterparty,
                              String purpose) {
        return new FeeRow(id, date, amount, counterparty, purpose);
    }

    /** Case-insensitive keyword match over the free-text fields. Visible for tests. */
    public static boolean matches(String text, List<String> keywords) {
        if (text == null || keywords == null || keywords.isEmpty()) {
            return false;
        }
        String haystack = text.toLowerCase(Locale.GERMAN);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && haystack.contains(keyword.strip().toLowerCase(Locale.GERMAN))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Groups the debits by counterparty and keeps the groups whose total reaches the tolerance.
     * Debits arrive as negative amounts; the report shows the absolute amount. Visible for tests.
     */
    public static List<FeeGroup> summarize(List<FeeRow> rows, BigDecimal tolerance) {
        Map<String, List<FeeRow>> byCounterparty = new LinkedHashMap<>();
        for (FeeRow row : rows) {
            String key = row.counterparty() == null || row.counterparty().isBlank()
                    ? "Unbekannter Empfänger" : row.counterparty().strip();
            byCounterparty.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        BigDecimal threshold = tolerance == null ? BigDecimal.ZERO : tolerance.abs();
        List<FeeGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<FeeRow>> entry : byCounterparty.entrySet()) {
            BigDecimal total = BigDecimal.ZERO;
            LocalDate first = null;
            LocalDate last = null;
            for (FeeRow row : entry.getValue()) {
                total = total.add(row.amount() == null ? BigDecimal.ZERO : row.amount().abs());
                if (row.bookingDate() != null) {
                    if (first == null || row.bookingDate().isBefore(first)) {
                        first = row.bookingDate();
                    }
                    if (last == null || row.bookingDate().isAfter(last)) {
                        last = row.bookingDate();
                    }
                }
            }
            if (total.compareTo(threshold) >= 0) {
                groups.add(new FeeGroup(entry.getKey(), entry.getValue().size(),
                        total.setScale(2, RoundingMode.HALF_UP), first, last));
            }
        }
        groups.sort(Comparator.comparing(FeeGroup::total).reversed());
        return List.copyOf(groups);
    }

    private static List<String> keywords(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_KEYWORDS;
        }
        List<String> keywords = new ArrayList<>();
        for (String keyword : raw.split(",")) {
            if (!keyword.isBlank()) {
                keywords.add(keyword.strip());
            }
        }
        return keywords.isEmpty() ? DEFAULT_KEYWORDS : List.copyOf(keywords);
    }

    private static LocalDate dateOr(String raw, LocalDate fallback) {
        return raw == null || raw.isBlank() ? fallback : LocalDate.parse(raw.strip());
    }

    private static BigDecimal amountOr(String raw, BigDecimal fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
