package de.buerokratiecopilot.settings;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The account mapping of the DATEV export (Kanzlei edition, §5.6 and §8.4).
 *
 * <p><strong>Why this table decides whether an export exists at all.</strong> A voucher may only become a
 * booking row when somebody said which accounts it belongs to. Migration V79 created {@code konto_mapping}
 * for that knowledge; without a row supplier/category → Sollkonto/Habenkonto the export skips every voucher
 * with "Kein Konto-Mapping". The table is therefore not bookkeeping decoration but the precondition of the
 * whole feature, which is why the screen above this service says so and why every change is audited
 * ({@code KONTO_MAPPING_CHANGED}).</p>
 *
 * <p><strong>What a row means.</strong> A row may name a client, a supplier, a category, or any combination.
 * The export picks the most specific matching row; {@code DatevExportService} owns that selection, this
 * service only stores and validates. A row without both accounts is refused here, because it would be stored
 * unusable and silently skip vouchers.</p>
 *
 * <p><strong>Everything is tenant-scoped.</strong> The controller takes the account from the session, the
 * queries always carry {@code tenant_id}, and the row level security policy of V79 is the second guard. An
 * identifier from another account is not found rather than forbidden, so the answer does not confirm that a
 * foreign row exists. A client identifier is checked against {@code contacts} of this account as well: the
 * foreign key alone would accept another account's contact, because PostgreSQL checks the target table
 * without applying its policy (the same second guard the export uses).</p>
 */
@Service
public class KontoMappingService {

    /** The audit action of every change to the mapping (§21.4). */
    public static final String AUDIT_ACTION = "KONTO_MAPPING_CHANGED";

    /** Account columns are {@code varchar(20)}; an account longer than that cannot be a DATEV account here. */
    private static final int MAX_ACCOUNT_LENGTH = 20;

    /** Column widths of the table, so a too-long value is a 400 instead of a failed insert. */
    private static final int MAX_SUPPLIER_LENGTH = 400;
    private static final int MAX_CATEGORY_LENGTH = 40;

    /** A mapping file is a few hundred lines; 2 MB is generous and keeps a wrong upload from becoming work. */
    private static final int MAX_IMPORT_BYTES = 2 * 1024 * 1024;

    /** The CSV header the Kanzlei receives from us and may send back. Order is not significant. */
    private static final List<String> CSV_COLUMNS = List.of(
            "mandant", "lieferant", "kategorie", "sollkonto", "habenkonto", "steuerschluessel");

    /** A mapping as the screen shows it; {@code mandantName} is read from the contact, never stored twice. */
    public record Mapping(UUID id, UUID mandantId, String mandantName, String supplier, String category,
                          String sollkonto, String habenkonto, String steuerschluessel,
                          Instant createdAt) {

        /** The shape the settings API serves. Kept here so the controller and the tests see one definition. */
        public Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id.toString());
            row.put("mandantId", mandantId == null ? null : mandantId.toString());
            row.put("mandantName", mandantName);
            row.put("supplier", supplier);
            row.put("category", category);
            row.put("sollkonto", sollkonto);
            row.put("habenkonto", habenkonto);
            row.put("steuerschluessel", steuerschluessel);
            row.put("createdAt", createdAt == null ? null : createdAt.toString());
            return row;
        }
    }

    /** The full state of one row: what a create sends and what an update replaces. */
    public record Change(UUID mandantId, String supplier, String category, String sollkonto,
                         String habenkonto, String steuerschluessel) {
    }

    /** One CSV line that was deliberately not written, with the sentence the screen shows. */
    public record SkippedRow(int line, String reason) {

        public Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("line", line);
            row.put("reason", reason);
            return row;
        }
    }

    /** What an import did: rows written, rows changed, rows left alone with a reason. */
    public record ImportResult(int created, int updated, List<SkippedRow> skipped) {

        public Map<String, Object> asMap() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("created", created);
            body.put("updated", updated);
            body.put("skipped", skipped.stream().map(SkippedRow::asMap).toList());
            return body;
        }
    }

    /** A validated row, ready to be written. */
    private record Valid(UUID mandantId, String supplier, String category, String sollkonto,
                         String habenkonto, String steuerschluessel) {
    }

    /** One parsed CSV record with the line it started on. */
    private record CsvRecord(int line, List<String> fields) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public KontoMappingService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    /**
     * The mappings of one account, optionally filtered.
     *
     * @param mandantId only rows for this client (exact)
     * @param supplier  case-insensitive part of the supplier name
     * @param category  case-insensitive category, exact
     */
    @Transactional(readOnly = true)
    public List<Mapping> list(UUID tenantId, UUID mandantId, String supplier, String category) {
        requireTenant(tenantId);
        StringBuilder sql = new StringBuilder("""
                select m.id, m.mandant_id, c.display_name as mandant_name, m.supplier, m.category,
                       m.sollkonto, m.habenkonto, m.steuerschluessel, m.created_at
                  from konto_mapping m
                  left join contacts c on c.id = m.mandant_id and c.tenant_id = m.tenant_id
                 where m.tenant_id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(tenantId);
        if (mandantId != null) {
            sql.append(" and m.mandant_id = ?");
            arguments.add(mandantId);
        }
        if (supplier != null && !supplier.isBlank()) {
            // ilike with the default escape character: a '%' in the search is a literal percent, not "all".
            sql.append(" and m.supplier ilike ?");
            arguments.add("%" + escapeLike(supplier.strip()) + "%");
        }
        if (category != null && !category.isBlank()) {
            sql.append(" and lower(m.category) = lower(?)");
            arguments.add(category.strip());
        }
        // The most general rows first: a reader sees what applies to everyone before the client-specific rows.
        sql.append("""
                 order by (c.display_name is null), lower(c.display_name),
                          (m.supplier is null), lower(m.supplier),
                          (m.category is null), lower(m.category), m.created_at
                """);
        return jdbc.query(sql.toString(), (rs, index) -> new Mapping(
                rs.getObject("id", UUID.class),
                rs.getObject("mandant_id", UUID.class),
                rs.getString("mandant_name"),
                rs.getString("supplier"),
                rs.getString("category"),
                rs.getString("sollkonto"),
                rs.getString("habenkonto"),
                rs.getString("steuerschluessel"),
                rs.getTimestamp("created_at").toInstant()), arguments.toArray());
    }

    /**
     * The categories a mapping may name, as the document taxonomy currently knows them.
     *
     * <p>Read from {@code document_types} instead of repeating the list in the interface: the matching of
     * {@code DatevExportService} compares the mapping's category with the voucher's document type category,
     * so the offered values have to be the ones that actually occur.</p>
     */
    @Transactional(readOnly = true)
    public List<String> categories(UUID tenantId) {
        requireTenant(tenantId);
        return jdbc.queryForList(
                "select distinct category from document_types order by category", String.class);
    }

    /** One row of this account; a foreign or unknown identifier is not found. */
    @Transactional(readOnly = true)
    public Mapping get(UUID tenantId, UUID id) {
        requireTenant(tenantId);
        List<Mapping> found = jdbc.query("""
                select m.id, m.mandant_id, c.display_name as mandant_name, m.supplier, m.category,
                       m.sollkonto, m.habenkonto, m.steuerschluessel, m.created_at
                  from konto_mapping m
                  left join contacts c on c.id = m.mandant_id and c.tenant_id = m.tenant_id
                 where m.id = ? and m.tenant_id = ?
                """, (rs, index) -> new Mapping(
                rs.getObject("id", UUID.class),
                rs.getObject("mandant_id", UUID.class),
                rs.getString("mandant_name"),
                rs.getString("supplier"),
                rs.getString("category"),
                rs.getString("sollkonto"),
                rs.getString("habenkonto"),
                rs.getString("steuerschluessel"),
                rs.getTimestamp("created_at").toInstant()), id, tenantId);
        if (found.isEmpty()) {
            throw ApiException.notFound("Konto-Zuordnung");
        }
        return found.getFirst();
    }

    // ------------------------------------------------------------------ writing

    /** Adds a row to the mapping. Both accounts are mandatory; everything else is optional. */
    @Transactional
    public Mapping create(UUID tenantId, UUID actor, Change change) {
        requireTenant(tenantId);
        Valid valid = validate(tenantId, change);
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into konto_mapping (tenant_id, mandant_id, supplier, category, sollkonto,
                                               habenkonto, steuerschluessel)
                    values (?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, UUID.class, tenantId, valid.mandantId(), valid.supplier(), valid.category(),
                    valid.sollkonto(), valid.habenkonto(), valid.steuerschluessel());
        }
        catch (DuplicateKeyException ex) {
            throw duplicate();
        }
        Mapping created = get(tenantId, id);
        audit(tenantId, actor, "angelegt", created, null, null);
        return created;
    }

    /**
     * Replaces the state of one row.
     *
     * <p>The body is the complete desired row, not a set of fields: the dialog sends every field anyway, and
     * "field absent" versus "field cleared" cannot be told apart in JSON without inventing a second
     * convention. A missing optional dimension therefore clears it, which is exactly what the dialog means
     * when a person empties the supplier.</p>
     */
    @Transactional
    public Mapping update(UUID tenantId, UUID actor, UUID id, Change change) {
        requireTenant(tenantId);
        Mapping before = get(tenantId, id);
        Valid valid = validate(tenantId, change);
        try {
            int changed = jdbc.update("""
                    update konto_mapping
                       set mandant_id = ?, supplier = ?, category = ?, sollkonto = ?, habenkonto = ?,
                           steuerschluessel = ?
                     where id = ? and tenant_id = ?
                    """, valid.mandantId(), valid.supplier(), valid.category(), valid.sollkonto(),
                    valid.habenkonto(), valid.steuerschluessel(), id, tenantId);
            if (changed == 0) {
                throw ApiException.notFound("Konto-Zuordnung");
            }
        }
        catch (DuplicateKeyException ex) {
            throw duplicate();
        }
        Mapping after = get(tenantId, id);
        audit(tenantId, actor, "geändert", after, before, null);
        return after;
    }

    /** Removes one row. A foreign identifier is not found, not a permission error. */
    @Transactional
    public void delete(UUID tenantId, UUID actor, UUID id) {
        requireTenant(tenantId);
        Mapping before = get(tenantId, id);
        int changed = jdbc.update("delete from konto_mapping where id = ? and tenant_id = ?", id, tenantId);
        if (changed == 0) {
            throw ApiException.notFound("Konto-Zuordnung");
        }
        audit(tenantId, actor, "gelöscht", before, before, null);
    }

    // ------------------------------------------------------------------ import

    /**
     * Imports the mapping table from the CSV the Kanzlei exchanges ({@code ;}, header optional in the
     * documented six columns).
     *
     * <p><strong>The whole file or nothing.</strong> The import runs in one transaction: a row that cannot be
     * written (a duplicate combination created concurrently, a database failure) aborts the file instead of
     * leaving half a mapping behind. Business-level problems — an unknown client, an empty account, a line
     * with the wrong number of columns — are rows the caller has to know about, not reasons to fail: they are
     * named in {@code skipped} with their line and a sentence, and the remaining lines are still imported.</p>
     *
     * <p><strong>An existing combination is updated, not duplicated.</strong> The unique index of V79 makes a
     * combination (client, supplier, category) one row; importing a changed account for the same combination
     * is the normal case ("the Kanzlei corrected the account"), so it updates. A row whose values are already
     * stored is reported as skipped with "unverändert" rather than counted as an update, because the report is
     * the only evidence a person has that an import changed anything.</p>
     */
    @Transactional
    public ImportResult importCsv(UUID tenantId, UUID actor, byte[] content) {
        requireTenant(tenantId);
        if (content == null || content.length == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Datei ist leer.");
        }
        if (content.length > MAX_IMPORT_BYTES) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Datei ist größer als 2 MB. Bitte in Teilen importieren.");
        }
        List<CsvRecord> records = parseCsv(decode(content));
        if (records.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Datei enthält keine Zeilen.");
        }
        Header header = header(records.getFirst());
        if (records.size() - 1 > 5000) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Datei enthält mehr als 5000 Zeilen. Bitte in Teilen importieren.");
        }

        int created = 0;
        int updated = 0;
        List<SkippedRow> skipped = new ArrayList<>();
        for (CsvRecord record : records.subList(1, records.size())) {
            RowOutcome outcome = importRow(tenantId, actor, header, record);
            created += outcome.created();
            updated += outcome.updated();
            if (outcome.reason() != null) {
                skipped.add(new SkippedRow(record.line(), outcome.reason()));
            }
        }
        return new ImportResult(created, updated, List.copyOf(skipped));
    }

    /** The header of one CSV file: where a column is. */
    private record Header(Map<String, Integer> index) {

        int column(String name) {
            Integer position = index.get(name);
            return position == null ? -1 : position;
        }
    }

    /** What one imported line did: written as a new row, written as a change, or left alone with a reason. */
    private record RowOutcome(int created, int updated, String reason) {

        static RowOutcome asCreated() {
            return new RowOutcome(1, 0, null);
        }

        static RowOutcome asUpdated() {
            return new RowOutcome(0, 1, null);
        }

        static RowOutcome asSkipped(String reason) {
            return new RowOutcome(0, 0, reason);
        }
    }

    /** Writes one CSV line; a business problem is a reason for this line, not for the whole file. */
    private RowOutcome importRow(UUID tenantId, UUID actor, Header header, CsvRecord record) {
        List<String> line = record.fields();
        int needed = header.index.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        if (line.size() < needed) {
            return RowOutcome.asSkipped("Zu wenige Spalten (" + line.size() + " statt " + needed + ").");
        }
        for (int extra = needed; extra < line.size(); extra += 1) {
            if (!line.get(extra).isBlank()) {
                return RowOutcome.asSkipped("Zu viele Spalten (" + line.size() + " statt " + needed + ").");
            }
        }

        String mandantName = value(line, header, "mandant");
        String supplier = value(line, header, "lieferant");
        String category = value(line, header, "kategorie");
        String sollkonto = value(line, header, "sollkonto");
        String habenkonto = value(line, header, "habenkonto");
        String steuerschluessel = value(line, header, "steuerschluessel");

        UUID mandantId = null;
        if (!mandantName.isBlank()) {
            // The client is found by the name as written on the voucher; two contacts with the same name
            // would make the line ambiguous, and guessing one of them would book the wrong client.
            List<UUID> matches = jdbc.queryForList("""
                    select id
                      from contacts
                     where tenant_id = ? and deleted_at is null
                       and lower(btrim(display_name)) = lower(?)
                     order by created_at
                    """, UUID.class, tenantId, mandantName.strip());
            if (matches.isEmpty()) {
                return RowOutcome.asSkipped("Unbekannter Mandant „" + mandantName.strip() + "“.");
            }
            if (matches.size() > 1) {
                return RowOutcome.asSkipped("Mandant „" + mandantName.strip() + "“ ist nicht eindeutig ("
                        + matches.size() + " Kontakte mit diesem Namen).");
            }
            mandantId = matches.getFirst();
        }

        Valid valid;
        try {
            valid = validate(tenantId, new Change(mandantId, supplier, category, sollkonto,
                    habenkonto, steuerschluessel));
        }
        catch (ApiException ex) {
            return RowOutcome.asSkipped(ex.getMessage());
        }

        List<UUID> existing = jdbc.queryForList("""
                select id from konto_mapping
                 where tenant_id = ? and mandant_id is not distinct from ?
                   and supplier is not distinct from ? and category is not distinct from ?
                """, UUID.class, tenantId, valid.mandantId(), valid.supplier(), valid.category());
        if (existing.isEmpty()) {
            UUID id = jdbc.queryForObject("""
                    insert into konto_mapping (tenant_id, mandant_id, supplier, category, sollkonto,
                                               habenkonto, steuerschluessel)
                    values (?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, UUID.class, tenantId, valid.mandantId(), valid.supplier(), valid.category(),
                    valid.sollkonto(), valid.habenkonto(), valid.steuerschluessel());
            audit(tenantId, actor, "angelegt", get(tenantId, id), null, record.line());
            return RowOutcome.asCreated();
        }

        UUID id = existing.getFirst();
        Mapping before = get(tenantId, id);
        if (same(before, valid)) {
            return RowOutcome.asSkipped("Unverändert – die Zuordnung bestand bereits.");
        }
        jdbc.update("""
                update konto_mapping
                   set sollkonto = ?, habenkonto = ?, steuerschluessel = ?
                 where id = ? and tenant_id = ?
                """, valid.sollkonto(), valid.habenkonto(), valid.steuerschluessel(), id, tenantId);
        audit(tenantId, actor, "geändert", get(tenantId, id), before, record.line());
        return RowOutcome.asUpdated();
    }

    private static String value(List<String> line, Header header, String column) {
        int position = header.column(column);
        return position < 0 || position >= line.size() ? "" : line.get(position).strip();
    }

    /** The row was found by this combination, so only the accounts and the tax key can differ. */
    private static boolean same(Mapping before, Valid valid) {
        return before.sollkonto().equals(valid.sollkonto())
                && before.habenkonto().equals(valid.habenkonto())
                && java.util.Objects.equals(before.steuerschluessel(), valid.steuerschluessel());
    }

    // ------------------------------------------------------------------ CSV parsing

    private static String decode(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        // Excel writes a BOM; without removing it the first header name would be wrong.
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /**
     * Splits a semicolon separated file into records, honouring quoted fields.
     *
     * <p>DATEV data is not hand-written, but it is edited in Excel, and Excel quotes a supplier that contains a
     * semicolon. A parser that splits on every semicolon would silently cut that supplier in two and map the
     * wrong accounts; embedded newlines inside quotes are handled for the same reason, and the recorded line
     * number of a record stays the physical line the record starts on so a skip message points at something a
     * person can find.</p>
     */
    private static List<CsvRecord> parseCsv(String text) {
        List<CsvRecord> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        int line = 1;
        int recordLine = 1;
        boolean quoted = false;
        boolean afterComma = true;

        for (int position = 0; position < text.length(); position += 1) {
            char current = text.charAt(position);
            if (quoted) {
                if (current == '"') {
                    if (position + 1 < text.length() && text.charAt(position + 1) == '"') {
                        field.append('"');
                        position += 1;
                    }
                    else {
                        quoted = false;
                    }
                }
                else {
                    if (current == '\n') {
                        line += 1;
                    }
                    field.append(current);
                }
                continue;
            }
            if (current == '"' && afterComma && field.isEmpty()) {
                quoted = true;
                continue;
            }
            if (current == ';') {
                fields.add(field.toString());
                field.setLength(0);
                afterComma = true;
                continue;
            }
            if (current == '\r') {
                continue;
            }
            if (current == '\n') {
                fields.add(field.toString());
                addRecord(records, recordLine, fields);
                fields = new ArrayList<>();
                field.setLength(0);
                line += 1;
                recordLine = line;
                afterComma = true;
                continue;
            }
            field.append(current);
            afterComma = false;
        }
        // A file without a trailing newline still ends with a record.
        fields.add(field.toString());
        addRecord(records, recordLine, fields);
        return records;
    }

    private static void addRecord(List<CsvRecord> records, int line, List<String> fields) {
        boolean empty = fields.stream().allMatch(String::isBlank);
        if (!empty) {
            records.add(new CsvRecord(line, List.copyOf(fields)));
        }
    }

    private static Header header(CsvRecord first) {
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int position = 0; position < first.fields().size(); position += 1) {
            String name = first.fields().get(position).strip().toLowerCase(Locale.ROOT);
            index.putIfAbsent(name, position);
        }
        List<String> missing = CSV_COLUMNS.stream().filter(column -> !index.containsKey(column)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Kopfzeile muss die Spalten " + String.join(";", CSV_COLUMNS)
                            + " enthalten. Es fehlt: " + String.join(", ", missing) + ".");
        }
        return new Header(index);
    }

    // ------------------------------------------------------------------ validation

    private Valid validate(UUID tenantId, Change change) {
        if (change == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurden keine Angaben übermittelt.");
        }
        String sollkonto = account(change.sollkonto(), "Sollkonto");
        String habenkonto = account(change.habenkonto(), "Habenkonto");
        String supplier = optional(change.supplier(), MAX_SUPPLIER_LENGTH, "Lieferant");
        String category = optional(change.category(), MAX_CATEGORY_LENGTH, "Kategorie");
        if (category != null) {
            // The export matches case-insensitively, but storing one spelling makes two rows that mean the
            // same for every reader also look the same in the table.
            category = category.toUpperCase(Locale.ROOT);
        }
        String steuerschluessel = optional(change.steuerschluessel(), MAX_ACCOUNT_LENGTH, "Steuerschlüssel");
        UUID mandantId = requireOwnMandant(tenantId, change.mandantId());
        return new Valid(mandantId, supplier, category, sollkonto, habenkonto, steuerschluessel);
    }

    private static String account(String value, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, label + " fehlt.");
        }
        if (normalized.length() > MAX_ACCOUNT_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    label + " darf höchstens " + MAX_ACCOUNT_LENGTH + " Zeichen haben.");
        }
        return normalized;
    }

    private static String optional(String value, int maxLength, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    label + " darf höchstens " + maxLength + " Zeichen haben.");
        }
        return normalized;
    }

    /**
     * A client filter must name a client of this account.
     *
     * <p>The foreign key alone is not enough: PostgreSQL checks {@code contacts (id)} without applying the
     * contacts policy, so an identifier from another account would be accepted by the database. The explicit
     * lookup is the second guard and the one that decides. It is also what makes an unknown client a 400 with
     * a sentence instead of a 409 from a constraint.</p>
     */
    private UUID requireOwnMandant(UUID tenantId, UUID mandantId) {
        if (mandantId == null) {
            return null;
        }
        Long count = jdbc.queryForObject("""
                select count(*) from contacts
                 where id = ? and tenant_id = ? and deleted_at is null
                """, Long.class, mandantId, tenantId);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der gewählte Mandant gehört nicht zu diesem Konto.");
        }
        return mandantId;
    }

    // ------------------------------------------------------------------ audit

    private void audit(UUID tenantId, UUID actor, String what, Mapping row, Mapping before, Integer line) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("source", line == null ? "UI" : "CSV");
        if (line != null) {
            changes.put("line", line);
        }
        changes.put("mandantId", row.mandantId() == null ? null : row.mandantId().toString());
        changes.put("mandantName", row.mandantName());
        changes.put("supplier", row.supplier());
        changes.put("category", row.category());
        changes.put("sollkonto", row.sollkonto());
        changes.put("habenkonto", row.habenkonto());
        changes.put("steuerschluessel", row.steuerschluessel());
        if (before != null) {
            changes.put("beforeSollkonto", before.sollkonto());
            changes.put("beforeHabenkonto", before.habenkonto());
            changes.put("beforeSteuerschluessel", before.steuerschluessel());
        }
        if (actor != null) {
            changes.put("changedBy", actor.toString());
        }
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.SETTINGS, AUDIT_ACTION)
                .tenant(tenantId)
                .entity("konto_mapping", row.id(), entityLabel(row))
                .description("Konto-Zuordnung " + what + ": " + entityLabel(row)
                        + " → " + row.sollkonto() + " / " + row.habenkonto()
                        + (line == null ? "" : " (Import, Zeile " + line + ")"))
                .changes(changes)
                .build());
    }

    /** A readable identity of a row: who, supplier, category — never secrets, at most the column's width. */
    private static String entityLabel(Mapping row) {
        List<String> parts = new ArrayList<>();
        if (row.mandantName() != null && !row.mandantName().isBlank()) {
            parts.add("Mandant „" + row.mandantName().strip() + "“");
        }
        else if (row.mandantId() != null) {
            parts.add("Mandant " + row.mandantId());
        }
        if (row.supplier() != null) {
            parts.add("Lieferant „" + row.supplier() + "“");
        }
        if (row.category() != null) {
            parts.add("Kategorie " + row.category());
        }
        String label = parts.isEmpty() ? "alle Belege" : String.join(", ", parts);
        return label.length() > 300 ? label.substring(0, 297) + "…" : label;
    }

    // ------------------------------------------------------------------ helpers

    private static ApiException duplicate() {
        return new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                "Für diese Kombination aus Mandant, Lieferant und Kategorie gibt es bereits eine Zuordnung. "
                        + "Ändern Sie die vorhandene Zeile, statt eine zweite anzulegen.");
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static void requireTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "No tenant in context");
        }
    }
}
