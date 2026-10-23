package de.buerokratiecopilot.contacts;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.contacts.ContactService.Kind;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Creates the clients of a Kanzlei from a CSV file (§6.1 step 2): {@code name;email;city}.
 *
 * <p>The wizard offers the same two ways a person would do it by hand: the form on the screen, or a file.
 * The import reports every line it did <em>not</em> create, with the reason, because a silent skip is the
 * one outcome a bookkeeper cannot work with - they would believe the client exists and might not look
 * again.</p>
 *
 * <p><strong>Idempotent by name.</strong> A second run of the same file creates nothing and reports the
 * lines as skipped ("existiert bereits"). The name is what a person recognises a client by; comparing the
 * e-mail address would let the same client in twice as soon as the file spells the address differently.</p>
 *
 * <p>The format is deliberately the simple one of the specification: three columns separated by semicolons.
 * Quoted fields without a semicolon inside are accepted; a full CSV parser is not, because a wizard that
 * needs a manual cannot keep the promise of five minutes.</p>
 */
@Service
public class ContactImportService {

    /** A file larger than this is an export from somewhere else, not the first clients of a new office. */
    public static final int MAX_DATA_LINES = 500;

    private static final int MAX_NAME = 300;
    private static final int MAX_EMAIL = 320;
    private static final int MAX_CITY = 120;

    public enum LineStatus {
        CREATED,
        SKIPPED
    }

    /** One data line and what happened to it. The line number is the one the person sees in the file. */
    public record ImportLine(int line, LineStatus status, String name, String reason) {
    }

    /** The honest answer: how many lines were read, created, and why the rest were not. */
    public record ImportReport(int total, int created, int skipped, List<ImportLine> lines) {
    }

    private record Row(int line, String name, String email, String city) {
    }

    private final ContactService contacts;
    private final JdbcTemplate jdbc;

    public ContactImportService(ContactService contacts, JdbcTemplate jdbc) {
        this.contacts = contacts;
        this.jdbc = jdbc;
    }

    @Transactional
    public ImportReport importCsv(UUID tenantId, String csv, UUID createdBy) {
        List<Row> rows = parse(csv);
        List<ImportLine> lines = new ArrayList<>(rows.size());
        int created = 0;
        for (Row row : rows) {
            String problem = problemWith(row);
            if (problem == null && alreadyExists(tenantId, row.name())) {
                problem = "Der Mandant existiert bereits";
            }
            if (problem != null) {
                lines.add(new ImportLine(row.line(), LineStatus.SKIPPED, row.name(), problem));
                continue;
            }
            contacts.create(tenantId, new ContactService.Draft(row.name(), Kind.ORGANISATION,
                    blankToNull(row.email()), null, blankToNull(row.city())), createdBy);
            lines.add(new ImportLine(row.line(), LineStatus.CREATED, row.name(), null));
            created++;
        }
        return new ImportReport(rows.size(), created, rows.size() - created, lines);
    }

    /** Reads the file into rows; refuses a file that is empty or too large before anything is created. */
    private List<Row> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Datei ist leer");
        }
        String content = csv.startsWith("\uFEFF") ? csv.substring(1) : csv;
        String[] all = content.split("\\R", -1);
        List<Row> rows = new ArrayList<>();
        boolean firstRow = true;
        for (int index = 0; index < all.length; index++) {
            String raw = all[index];
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String[] fields = raw.split(";", -1);
            if (firstRow) {
                firstRow = false;
                if (isHeader(fields)) {
                    continue;
                }
            }
            if (rows.size() >= MAX_DATA_LINES) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Die Datei hat mehr als " + MAX_DATA_LINES + " Mandanten; bitte in Teilen importieren");
            }
            rows.add(new Row(index + 1, field(fields, 0), field(fields, 1), field(fields, 2)));
        }
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Datei enthält keine Mandanten");
        }
        return rows;
    }

    /** The first non-blank line may be the column titles of an export; it is not a client. */
    private static boolean isHeader(String[] fields) {
        String first = field(fields, 0);
        return first.equalsIgnoreCase("name") || first.equalsIgnoreCase("mandant")
                || first.equalsIgnoreCase("firma");
    }

    private static String field(String[] fields, int index) {
        return index < fields.length ? clean(fields[index]) : "";
    }

    private static String clean(String value) {
        String text = value == null ? "" : value.strip();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).replace("\"\"", "\"").strip();
        }
        return text;
    }

    private static String problemWith(Row row) {
        if (row.name().isEmpty()) {
            return "Der Name fehlt";
        }
        if (row.name().length() > MAX_NAME) {
            return "Der Name ist zu lang (höchstens " + MAX_NAME + " Zeichen)";
        }
        if (!row.email().isEmpty()) {
            if (row.email().length() > MAX_EMAIL) {
                return "Die E-Mail-Adresse ist zu lang";
            }
            if (!row.email().matches("^[^@\\s]+@[^@\\s]+$")) {
                return "Die E-Mail-Adresse ist ungültig";
            }
        }
        if (row.city().length() > MAX_CITY) {
            return "Der Ort ist zu lang (höchstens " + MAX_CITY + " Zeichen)";
        }
        return null;
    }

    private boolean alreadyExists(UUID tenantId, String name) {
        Integer count = jdbc.queryForObject("""
                select count(*) from contacts
                where tenant_id = ? and deleted_at is null and lower(display_name) = lower(?)
                """, Integer.class, tenantId, name);
        return count != null && count > 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
