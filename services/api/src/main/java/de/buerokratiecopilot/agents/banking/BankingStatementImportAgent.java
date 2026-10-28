package de.buerokratiecopilot.agents.banking;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.banking.PaymentMatchingService;

/**
 * {@code banking-statement-import} — Kontoauszug-Import.
 *
 * <p>Accepts a CAMT.053 XML by delegating to the banking module's own parser/importer, and a plain
 * CSV export through a small column-mapping reader. In both paths duplicates are recognised before
 * a row is stored (the bank reference where present, otherwise account + booking date + amount +
 * reference), and no transaction is matched or booked by this agent.</p>
 *
 * <p>There is no network call and no bank API: the statement content comes in as text (raw XML/CSV
 * or Base64). A file that cannot be parsed is a failure with the parser's reason, never an empty
 * success.</p>
 */
@Component
public class BankingStatementImportAgent implements Agent {

    private static final int MAX_ROWS = 5000;
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final JdbcTemplate jdbc;
    private final PaymentMatchingService matching;

    public BankingStatementImportAgent(JdbcTemplate jdbc, PaymentMatchingService matching) {
        this.jdbc = jdbc;
        this.matching = matching;
    }

    @Override
    public String id() {
        return "banking-statement-import";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID accountId = AgentInputs.uuid(context.input("accountId"));
        if (accountId == null) {
            return AgentResult.skipped("Kontoauszug-Import: 'accountId' fehlt oder ist keine gültige UUID.");
        }
        if (!accountExists(context.tenantId(), accountId)) {
            return AgentResult.skipped("Kontoauszug-Import: Finanzkonto " + accountId
                    + " ist im Mandanten nicht vorhanden.");
        }
        String content = AgentInputs.clean(context.input("content"));
        if (content == null) {
            content = AgentInputs.clean(context.input("file"));
        }
        if (content == null) {
            return AgentResult.skipped("Kontoauszug-Import: keine Auszugsdaten übergeben "
                    + "('content' oder 'file').");
        }
        String format = resolveFormat(context.input("format"), content);
        try {
            return switch (format) {
                case "CAMT053", "CAMT052" -> importCamt(context, accountId, content);
                case "CSV" -> importCsv(context, accountId, content);
                default -> AgentResult.failed("Kontoauszug-Import: unbekanntes Format '" + format
                        + "' — erwartet CAMT053, CAMT052 oder CSV.");
            };
        }
        catch (RuntimeException ex) {
            return AgentResult.failed("Kontoauszug-Import fehlgeschlagen: " + ex.getMessage());
        }
    }

    private AgentResult importCamt(AgentContext context, UUID accountId, String content) {
        byte[] bytes = decode(content, AgentInputs.bool(context.input("base64"), false));
        PaymentMatchingService.ImportResult result;
        try {
            result = matching.importCamt(context.tenantId(), accountId, bytes, context.actorUserId());
        }
        catch (java.io.IOException ex) {
            return AgentResult.failed("CAMT-Datei nicht lesbar: " + ex.getMessage());
        }
        if (result.transactions() == 0) {
            return new AgentResult(false, "CAMT-Datei gelesen, aber keine neue Buchung importiert ("
                    + result.duplicatesSkipped() + " Dublette(n)).", List.of(),
                    Map.of("statementId", result.statementId().toString(),
                            "transactions", "0", "duplicates", String.valueOf(result.duplicatesSkipped())));
        }
        context.addProduced("bank_statement", result.statementId().toString(),
                "CAMT-Auszug: " + result.transactions() + " Buchung(en), "
                        + result.duplicatesSkipped() + " Dublette(n) übersprungen");
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("statementId", result.statementId().toString());
        meta.put("format", "CAMT053");
        meta.put("transactions", String.valueOf(result.transactions()));
        meta.put("duplicatesSkipped", String.valueOf(result.duplicatesSkipped()));
        meta.put("matchedAutomatically", String.valueOf(result.matchedAutomatically()));
        meta.put("proposed", String.valueOf(result.proposed()));
        return AgentResult.done("Kontoauszug importiert: " + result.transactions()
                + " Buchung(en), " + result.duplicatesSkipped() + " Dublette(n) übersprungen.", context.produced(), meta);
    }

    private AgentResult importCsv(AgentContext context, UUID accountId, String content) {
        List<Map<String, String>> rows = parseCsv(content);
        if (rows.isEmpty()) {
            return AgentResult.failed("CSV-Auszug enthält keine Datenzeilen.");
        }
        List<CsvTransaction> transactions = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (int i = 0; i < rows.size() && transactions.size() < MAX_ROWS; i++) {
            CsvTransaction parsed = CsvTransaction.from(rows.get(i));
            if (parsed == null) {
                rejected.add("Zeile " + (i + 2));
            }
            else {
                transactions.add(parsed);
            }
        }
        if (transactions.isEmpty()) {
            return AgentResult.failed("CSV-Auszug: keine Zeile enthält Datum und Betrag ("
                    + String.join(", ", rejected) + ").");
        }
        LocalDate from = AgentInputs.date(context.input("periodFrom"));
        LocalDate to = AgentInputs.date(context.input("periodTo"));
        List<CsvTransaction> inPeriod = transactions.stream()
                .filter(t -> from == null || !t.bookingDate().isBefore(from))
                .filter(t -> to == null || !t.bookingDate().isAfter(to))
                .toList();
        if (inPeriod.isEmpty()) {
            return AgentResult.skipped("CSV-Auszug: alle " + transactions.size()
                    + " Buchung(en) liegen außerhalb des Zeitraums.");
        }

        UUID statementId = jdbc.queryForObject("""
                insert into bank_statements (id, tenant_id, financial_account_id, format, period_start,
                                             period_end, currency, status, imported_by)
                values (gen_random_uuid(), ?, ?, 'CSV', ?, ?, 'EUR', 'IMPORTED', ?)
                returning id
                """, UUID.class, context.tenantId(), accountId,
                inPeriod.stream().map(CsvTransaction::bookingDate).min(LocalDate::compareTo).orElse(null),
                inPeriod.stream().map(CsvTransaction::bookingDate).max(LocalDate::compareTo).orElse(null),
                context.actorUserId());

        int imported = 0;
        int duplicates = 0;
        for (CsvTransaction transaction : inPeriod) {
            String externalId = "CSV-" + AgentInputs.key(String.valueOf(accountId),
                    transaction.bookingDate().toString(), transaction.amount().toPlainString(),
                    transaction.reference(), transaction.purpose());
            if (isDuplicate(context.tenantId(), accountId, externalId, transaction)) {
                duplicates++;
                continue;
            }
            jdbc.update("""
                    insert into bank_transactions (id, statement_id, tenant_id, financial_account_id,
                                                   booking_date, value_date, amount, currency,
                                                   counterparty_name, purpose, reference, status,
                                                   external_id, metadata)
                    values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'EUR', ?, ?, ?, 'UNMATCHED', ?,
                            '{}'::jsonb)
                    """, statementId, context.tenantId(), accountId, transaction.bookingDate(),
                    transaction.bookingDate(), transaction.amount(), transaction.counterparty(),
                    transaction.purpose(), transaction.reference(), externalId);
            imported++;
        }
        context.addProduced("bank_statement", statementId.toString(),
                "CSV-Auszug: " + imported + " Buchung(en) importiert, " + duplicates + " Dublette(n)");
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("statementId", statementId.toString());
        meta.put("format", "CSV");
        meta.put("transactions", String.valueOf(imported));
        meta.put("duplicatesSkipped", String.valueOf(duplicates));
        meta.put("rejectedRows", String.valueOf(rejected.size()));
        if (imported == 0) {
            return new AgentResult(false, "CSV-Auszug gelesen, aber keine neue Buchung importiert ("
                    + duplicates + " Dublette(n)).", context.produced(), meta);
        }
        return AgentResult.done("CSV-Kontoauszug importiert: " + imported + " Buchung(en), "
                + duplicates + " Dublette(n) übersprungen, " + rejected.size()
                + " unlesbare Zeile(n).", context.produced(), meta);
    }

    private boolean accountExists(UUID tenantId, UUID accountId) {
        List<UUID> rows = jdbc.query("""
                select id from financial_accounts
                where tenant_id = ? and id = ? and deleted_at is null
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, accountId);
        return !rows.isEmpty();
    }

    private boolean isDuplicate(UUID tenantId, UUID accountId, String externalId,
                                CsvTransaction transaction) {
        Integer byExternal = jdbc.queryForObject("""
                select count(*) from bank_transactions
                where tenant_id = ? and external_id = ?
                """, Integer.class, tenantId, externalId);
        if (byExternal != null && byExternal > 0) {
            return true;
        }
        Integer byFingerprint = jdbc.queryForObject("""
                select count(*) from bank_transactions
                where tenant_id = ? and financial_account_id = ? and booking_date = ? and amount = ?
                  and coalesce(reference, '') = coalesce(?, '')
                  and coalesce(purpose, '') = coalesce(?, '')
                """, Integer.class, tenantId, accountId, transaction.bookingDate(),
                transaction.amount(), transaction.reference(), transaction.purpose());
        return byFingerprint != null && byFingerprint > 0;
    }

    static String resolveFormat(String raw, String content) {
        String value = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!value.isEmpty()) {
            return value.equals("CAMT") ? "CAMT053" : value;
        }
        return content.contains("<") && (content.contains("camt") || content.contains("BkToCstmrStmt"))
                ? "CAMT053" : "CSV";
    }

    static byte[] decode(String content, boolean base64) {
        boolean looksBase64 = !content.contains("<") && content.strip()
                .matches("^[A-Za-z0-9+/=\\s]+$");
        if (base64 || looksBase64 && content.strip().length() > 32) {
            try {
                return Base64.getMimeDecoder().decode(content);
            }
            catch (IllegalArgumentException ignored) {
                // Fall through: the caller said Base64 but the value is plain text.
            }
        }
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /** A CSV reader for the exports banks actually produce: header names are mapped, not positions. */
    static List<Map<String, String>> parseCsv(String content) {
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        if (lines.length < 2) {
            return List.of();
        }
        char delimiter = lines[0].contains(";") ? ';' : ',';
        List<String> header = splitLine(lines[0], delimiter);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> values = splitLine(lines[i], delimiter);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(normalizeHeader(header.get(c)), c < values.size() ? values.get(c) : null);
            }
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static List<String> splitLine(String line, char delimiter) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                }
                else {
                    quoted = !quoted;
                }
            }
            else if (c == delimiter && !quoted) {
                values.add(current.toString().strip());
                current.setLength(0);
            }
            else {
                current.append(c);
            }
        }
        values.add(current.toString().strip());
        return values;
    }

    private static String normalizeHeader(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replaceAll("[^a-z0-9]", "");
    }

    /** One CSV row, normalised to the fields a bank transaction needs. */
    record CsvTransaction(LocalDate bookingDate, BigDecimal amount, String counterparty,
                          String purpose, String reference) {

        static CsvTransaction from(Map<String, String> row) {
            LocalDate date = date(value(row, "bookingdate", "buchungstag", "datum", "valutadatum",
                    "date"));
            BigDecimal amount = amount(value(row, "amount", "betrag", "umsatz"));
            if (date == null || amount == null) {
                return null;
            }
            return new CsvTransaction(date, amount,
                    value(row, "counterparty", "auftraggeber", "empfaenger", "beguenstigter", "name"),
                    value(row, "purpose", "verwendungszweck", "buchungstext"),
                    value(row, "reference", "referenz", "kundenreferenz"));
        }

        private static String value(Map<String, String> row, String... names) {
            for (String name : names) {
                String value = row.get(name);
                if (value != null && !value.isBlank()) {
                    return value.strip();
                }
            }
            return null;
        }

        private static LocalDate date(String raw) {
            if (raw == null) {
                return null;
            }
            try {
                return LocalDate.parse(raw.strip());
            }
            catch (DateTimeParseException ignored) {
                try {
                    return LocalDate.parse(raw.strip(), GERMAN_DATE);
                }
                catch (DateTimeParseException ex) {
                    return null;
                }
            }
        }

        private static BigDecimal amount(String raw) {
            if (raw == null) {
                return null;
            }
            String value = raw.replace("EUR", "").replace("€", "").replace(" ", "").strip();
            if (value.contains(",") && value.contains(".")) {
                value = value.replace(".", "").replace(",", ".");
            }
            else if (value.contains(",")) {
                value = value.replace(",", ".");
            }
            try {
                return new BigDecimal(value);
            }
            catch (NumberFormatException ex) {
                return null;
            }
        }
    }
}
