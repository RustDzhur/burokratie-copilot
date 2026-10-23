package de.buerokratiecopilot.exports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.BelegStatus;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Builds DATEV export packages from the vouchers of one Kanzlei (Kanzlei edition, §5.6).
 *
 * <p><strong>The format is provisional.</strong> §12.1 says the exact field profile and encoding are fixed
 * together with the first Kanzlei in Appendix 1. They are not agreed. This service therefore produces the
 * documented working profile in {@link DatevCsvWriter}, and every file carries
 * {@value DatevCsvWriter#PROVISIONAL_NOTE} in its first line. Nobody may present it as the final DATEV
 * format.</p>
 *
 * <p><strong>What may leave the house.</strong> A voucher is considered only when it stands in
 * {@link BelegStatus#ZU_PRUEFEN} and its document date lies inside the requested period. Two rules then decide
 * whether it reaches the file:</p>
 * <ul>
 *   <li>a voucher without a client is never exported - it is recorded as {@code SKIPPED} with the reason
 *       "Ohne Mandant", the same state the queue calls by that name (§5.2);</li>
 *   <li>a voucher whose supplier/category has no usable {@code konto_mapping} is never exported - it is
 *       recorded as {@code SKIPPED} with "Kein Konto-Mapping".</li>
 * </ul>
 * <p>Both decisions are stored in {@code export_batch_items}, so a package explains itself later. Skipped
 * vouchers keep their status and can be completed and exported in a later package; successfully exported ones
 * are moved to {@link BelegStatus#EXPORTIERT} through {@link DocumentService#changeBelegStatus}, which applies
 * the client rule and writes the audit trail. A repeated export of the same period therefore does not pick them
 * up again.</p>
 *
 * <p><strong>The file is the artifact.</strong> The exact CSV bytes are stored in the batch. A download later
 * returns the file that was produced, not a new rendering from data that may have changed since.</p>
 *
 * <p><strong>Failure honesty.</strong> If the generation stops after the attempt began, a batch with status
 * {@code FAILED} is recorded in its own transaction, so the history shows the attempt even though the business
 * transaction rolled back and no file exists.</p>
 */
@Service
public class DatevExportService {

    private static final Logger log = LoggerFactory.getLogger(DatevExportService.class);

    /** The reason shown for a voucher without a client (§5.2). */
    public static final String REASON_NO_MANDANT = "Ohne Mandant";

    /** The reason shown for a voucher whose supplier/category has no usable account mapping. */
    public static final String REASON_NO_KONTO_MAPPING = "Kein Konto-Mapping";

    private static final String ITEM_OK = "OK";
    private static final String ITEM_SKIPPED = "SKIPPED";

    private final JdbcTemplate jdbc;
    private final DocumentService documents;
    private final AuditService audit;
    private final TransactionTemplate failureTransaction;

    public DatevExportService(JdbcTemplate jdbc, DocumentService documents, AuditService audit,
                              PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.audit = audit;
        this.failureTransaction = new TransactionTemplate(transactionManager);
        // A failed attempt must survive the rollback of the business transaction that failed.
        this.failureTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A voucher that was deliberately not put into the file, with the sentence the screen shows. */
    public record SkippedDocument(UUID documentId, String reason) {
    }

    /** The answer to "create a package": what went in, what did not, and how to download it. */
    public record ExportResult(UUID id, int belegCount, BigDecimal totalAmount, String filename,
                               List<SkippedDocument> skipped) {
    }

    /** One package as the history screen lists it. */
    public record BatchSummary(UUID id, Instant createdAt, UUID createdBy, String createdByName,
                               LocalDate periodFrom, LocalDate periodTo, UUID mandantId,
                               String mandantName, int belegCount, BigDecimal totalAmount,
                               String status, String filename, int skippedCount) {
    }

    /** The downloadable file. */
    public record Download(String filename, byte[] content) {
    }

    private record Candidate(UUID id, String belegnummer, LocalDate documentDate, String sender,
                             UUID mandantId, String mandantName, String category, String amount,
                             String currency, String supplierFromExtraction) {

        String supplier() {
            if (sender != null && !sender.isBlank()) {
                return sender.strip();
            }
            return supplierFromExtraction == null ? "" : supplierFromExtraction.strip();
        }
    }

    private record Mapping(UUID mandantId, String supplier, String category, String sollkonto,
                           String habenkonto, String steuerschluessel) {
    }

    private record Item(UUID documentId, String status, String message) {
    }

    /**
     * Creates one package and moves the exported vouchers to {@code EXPORTIERT}.
     *
     * @param tenantId  the account; always from the authenticated session, never from the request body
     * @param actor     the person starting the export, for {@code created_by} and the audit trail
     * @param periodFrom first document date (inclusive)
     * @param periodTo   last document date (inclusive)
     * @param mandantId  optional client filter; {@code null} means all clients
     */
    @Transactional
    public ExportResult create(UUID tenantId, UUID actor, LocalDate periodFrom, LocalDate periodTo,
                               UUID mandantId) {
        requireTenant(tenantId);
        requirePeriod(periodFrom, periodTo);
        requireOwnMandant(tenantId, mandantId);

        UUID batchId = UUID.randomUUID();
        String filename = buildFilename(periodFrom, periodTo, batchId);
        try {
            List<Candidate> candidates = loadCandidates(tenantId, periodFrom, periodTo, mandantId);
            List<Mapping> mappings = loadMappings(tenantId);

            List<SkippedDocument> skipped = new ArrayList<>();
            List<DatevCsvWriter.Row> rows = new ArrayList<>();
            List<UUID> exported = new ArrayList<>();
            List<Item> items = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;

            for (Candidate candidate : candidates) {
                // §5.2 / §11.7: no client, no export. The voucher stays where it is and is named here.
                if (candidate.mandantId() == null || candidate.mandantName() == null
                        || candidate.mandantName().isBlank()) {
                    skipped.add(new SkippedDocument(candidate.id(), REASON_NO_MANDANT));
                    items.add(new Item(candidate.id(), ITEM_SKIPPED, REASON_NO_MANDANT));
                    continue;
                }
                Mapping mapping = bestMapping(mappings, candidate.mandantId(), candidate.supplier(),
                        candidate.category());
                if (mapping == null) {
                    skipped.add(new SkippedDocument(candidate.id(), REASON_NO_KONTO_MAPPING));
                    items.add(new Item(candidate.id(), ITEM_SKIPPED, REASON_NO_KONTO_MAPPING));
                    continue;
                }

                BigDecimal amount = parseAmount(candidate.amount());
                rows.add(new DatevCsvWriter.Row(
                        candidate.belegnummer() == null ? "" : candidate.belegnummer(),
                        candidate.documentDate(),
                        candidate.mandantName(),
                        candidate.supplier(),
                        amount,
                        currency(candidate.currency()),
                        mapping.sollkonto(),
                        mapping.habenkonto(),
                        mapping.steuerschluessel()));
                if (amount != null) {
                    total = total.add(amount);
                }
                exported.add(candidate.id());
                items.add(new Item(candidate.id(), ITEM_OK, null));
            }

            byte[] csv = DatevCsvWriter.write(rows);
            BigDecimal totalAmount = total.setScale(2, RoundingMode.HALF_UP);

            insertBatch(tenantId, actor, batchId, periodFrom, periodTo, mandantId,
                    exported.size(), totalAmount, "CREATED", filename, csv);
            insertItems(tenantId, batchId, items);

            // Through the service, not by SQL: this is where the "no client, no export" rule and the audit
            // entry live. The vouchers were selected as ZU_PRUEFEN, so the transition is the allowed one.
            for (UUID documentId : exported) {
                documents.changeBelegStatus(documentId, BelegStatus.EXPORTIERT);
            }

            audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.EXPORT, "DATEV_EXPORT_CREATED")
                    .tenant(tenantId)
                    .entity("export_batches", batchId, filename)
                    .description("DATEV-Export erstellt: " + exported.size() + " Belege, Summe "
                            + totalAmount.toPlainString() + " EUR, " + skipped.size() + " übersprungen")
                    .changes(Map.of(
                            "belegCount", exported.size(),
                            "totalAmount", totalAmount.toPlainString(),
                            "skippedCount", skipped.size(),
                            "filename", filename))
                    .build());

            return new ExportResult(batchId, exported.size(), totalAmount, filename, List.copyOf(skipped));
        }
        catch (RuntimeException ex) {
            recordFailedAttempt(tenantId, actor, batchId, periodFrom, periodTo, mandantId, filename, ex);
            throw ex;
        }
    }

    /** The packages of one account, newest first. */
    @Transactional(readOnly = true)
    public List<BatchSummary> history(UUID tenantId) {
        requireTenant(tenantId);
        return jdbc.query("""
                select b.id, b.created_at, b.created_by, u.display_name as created_by_name,
                       b.period_from, b.period_to, b.mandant_filter, c.display_name as mandant_name,
                       b.beleg_count, b.total_amount, b.status, b.filename,
                       (select count(*) from export_batch_items i
                         where i.batch_id = b.id and i.status = 'SKIPPED') as skipped_count
                  from export_batches b
                  left join users u on u.id = b.created_by
                  left join contacts c on c.id = b.mandant_filter and c.tenant_id = b.tenant_id
                 where b.tenant_id = ?
                 order by b.created_at desc
                """, (rs, index) -> new BatchSummary(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("created_by", UUID.class),
                rs.getString("created_by_name"),
                rs.getObject("period_from", LocalDate.class),
                rs.getObject("period_to", LocalDate.class),
                rs.getObject("mandant_filter", UUID.class),
                rs.getString("mandant_name"),
                rs.getInt("beleg_count"),
                rs.getBigDecimal("total_amount"),
                rs.getString("status"),
                rs.getString("filename"),
                rs.getInt("skipped_count")), tenantId);
    }

    /** The stored file of one package. A foreign identifier simply does not exist. */
    @Transactional(readOnly = true)
    public Download download(UUID tenantId, UUID batchId) {
        requireTenant(tenantId);
        List<Download> found = jdbc.query("""
                select filename, content
                  from export_batches
                 where id = ? and tenant_id = ? and content is not null
                """, (rs, index) -> new Download(rs.getString("filename"), rs.getBytes("content")),
                batchId, tenantId);
        if (found.isEmpty()) {
            throw ApiException.notFound("Export batch");
        }
        return found.getFirst();
    }

    // ------------------------------------------------------------------ selection and matching

    private List<Candidate> loadCandidates(UUID tenantId, LocalDate from, LocalDate to, UUID mandantId) {
        // The optional client filter is appended instead of being expressed as "(? is null or …)": a nullable
        // UUID parameter is bound as an untyped value by the driver and PostgreSQL refuses to compare it.
        String mandantClause = mandantId == null ? "" : " and d.mandant_id = ?";
        Object[] arguments;
        if (mandantId == null) {
            arguments = new Object[] {tenantId, from, to};
        }
        else {
            arguments = new Object[] {tenantId, from, to, mandantId};
        }

        // One query for the whole package. The correlated subselects read the extracted values the voucher
        // card already showed; they do not invent a value the document does not carry. Amount preference:
        // gross before generic before outstanding, then verified before unverified.
        return jdbc.query("""
                select d.id,
                       coalesce(nullif(d.document_number, ''),
                                (select x.normalized_value
                                   from document_extractions x
                                  where x.document_id = d.id
                                    and x.field_key in ('Rechnungsnummer', 'Aktenzeichen')
                                  order by x.verified_at desc nulls last, x.created_at desc
                                  limit 1)) as belegnummer,
                       -- The voucher's own date. documents.document_date is the authoritative column but the
                       -- pipeline never writes it, so a filter on it alone selected nothing: the export looked
                       -- broken for every real workspace. The extracted date is the fallback, in the same order
                       -- the orchestrator uses.
                       coalesce(d.document_date,
                                (select (x.normalized_value)::date
                                   from document_extractions x
                                  where x.document_id = d.id
                                    and x.field_key in ('Rechnungsdatum', 'Bescheiddatum', 'Schreibendatum')
                                    and x.value_type = 'DATE'
                                  order by case x.field_key
                                               when 'Rechnungsdatum' then 0
                                               when 'Bescheiddatum' then 1
                                               else 2
                                           end,
                                           x.verified_at desc nulls last,
                                           x.created_at desc
                                  limit 1)) as beleg_datum,
                       d.sender,
                       d.mandant_id,
                       c.display_name as mandant_name,
                       t.category as category,
                       (select x.normalized_value
                          from document_extractions x
                         where x.document_id = d.id
                           and x.field_key in ('Brutto', 'Betrag', 'OffenerBetrag')
                         order by case x.field_key when 'Brutto' then 0 when 'Betrag' then 1 else 2 end,
                                  x.verified_at desc nulls last,
                                  x.confidence desc nulls last,
                                  x.created_at desc
                         limit 1) as amount,
                       (select x.normalized_value
                          from document_extractions x
                         where x.document_id = d.id and x.field_key = 'Waehrung'
                         order by x.created_at desc limit 1) as currency,
                       (select x.normalized_value
                          from document_extractions x
                         where x.document_id = d.id and x.field_key = 'Lieferant'
                         order by x.created_at desc limit 1) as supplier_extraction
                  from documents d
                  left join contacts c on c.id = d.mandant_id and c.tenant_id = d.tenant_id
                  left join document_types t on t.id = d.document_type_id
                 where d.tenant_id = ?
                   and d.deleted_at is null
                   and d.beleg_status = 'ZU_PRUEFEN'
                   and coalesce(d.document_date,
                                (select (x.normalized_value)::date
                                   from document_extractions x
                                  where x.document_id = d.id
                                    and x.field_key in ('Rechnungsdatum', 'Bescheiddatum', 'Schreibendatum')
                                    and x.value_type = 'DATE'
                                  order by case x.field_key
                                               when 'Rechnungsdatum' then 0
                                               when 'Bescheiddatum' then 1
                                               else 2
                                           end,
                                           x.verified_at desc nulls last,
                                           x.created_at desc
                                  limit 1)) between ? and ?
                """ + mandantClause + """
                 order by beleg_datum nulls last, d.created_at, d.id
                """, (rs, index) -> new Candidate(
                rs.getObject("id", UUID.class),
                rs.getString("belegnummer"),
                rs.getObject("beleg_datum", LocalDate.class),
                rs.getString("sender"),
                rs.getObject("mandant_id", UUID.class),
                rs.getString("mandant_name"),
                rs.getString("category"),
                rs.getString("amount"),
                rs.getString("currency"),
                rs.getString("supplier_extraction")), arguments);
    }

    private List<Mapping> loadMappings(UUID tenantId) {
        // Newest first; the matcher keeps the first row of equal specificity.
        return jdbc.query("""
                select mandant_id, supplier, category, sollkonto, habenkonto, steuerschluessel
                  from konto_mapping
                 where tenant_id = ?
                 order by created_at desc
                """, (rs, index) -> new Mapping(
                rs.getObject("mandant_id", UUID.class),
                rs.getString("supplier"),
                rs.getString("category"),
                rs.getString("sollkonto"),
                rs.getString("habenkonto"),
                rs.getString("steuerschluessel")), tenantId);
    }

    /**
     * The most specific usable mapping for a voucher.
     *
     * <p>A row matches when every dimension it names equals the voucher's: a row with a client only applies to
     * that client, a row with a supplier only to that supplier. Specificity is client (4) over supplier (2)
     * over category (1), so a client's supplier mapping beats an account-wide supplier default. A row without
     * both accounts is not usable and is skipped rather than producing an empty booking row.</p>
     */
    private static Mapping bestMapping(List<Mapping> mappings, UUID mandantId, String supplier,
                                       String category) {
        Mapping best = null;
        int bestScore = -1;
        for (Mapping mapping : mappings) {
            if (mapping.mandantId() != null && !mapping.mandantId().equals(mandantId)) {
                continue;
            }
            if (mapping.supplier() != null
                    && (supplier == null || !mapping.supplier().strip().equalsIgnoreCase(supplier.strip()))) {
                continue;
            }
            if (mapping.category() != null
                    && (category == null || !mapping.category().strip().equalsIgnoreCase(category.strip()))) {
                continue;
            }
            if (mapping.sollkonto() == null || mapping.sollkonto().isBlank()
                    || mapping.habenkonto() == null || mapping.habenkonto().isBlank()) {
                continue;
            }
            int score = (mapping.mandantId() != null ? 4 : 0)
                    + (mapping.supplier() != null ? 2 : 0)
                    + (mapping.category() != null ? 1 : 0);
            if (score > bestScore) {
                best = mapping;
                bestScore = score;
            }
        }
        return best;
    }

    private static BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            // Extracted normalised amounts are ISO decimals. A German comma is accepted as a courtesy, but a
            // value that cannot be read stays unknown instead of becoming a plausible zero.
            String normalized = value.strip();
            if (normalized.indexOf(',') >= 0) {
                normalized = normalized.replace(".", "").replace(',', '.');
            }
            return new BigDecimal(normalized);
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String currency(String value) {
        if (value == null || value.isBlank()) {
            return "EUR";
        }
        String currency = value.strip().toUpperCase(java.util.Locale.ROOT);
        return currency.matches("[A-Z]{3}") ? currency : "EUR";
    }

    // ------------------------------------------------------------------ persistence

    private void insertBatch(UUID tenantId, UUID actor, UUID batchId, LocalDate from, LocalDate to,
                             UUID mandantId, int belegCount, BigDecimal totalAmount, String status,
                             String filename, byte[] content) {
        jdbc.update("""
                insert into export_batches (id, tenant_id, created_by, period_from, period_to, mandant_filter,
                                            beleg_count, total_amount, status, filename, content)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, batchId, tenantId, actor, from, to, mandantId, belegCount, totalAmount, status,
                filename, content);
    }

    private void insertItems(UUID tenantId, UUID batchId, List<Item> items) {
        if (items.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                insert into export_batch_items (tenant_id, batch_id, document_id, status, error_message)
                values (?, ?, ?, ?, ?)
                """, items, 100, (statement, item) -> {
            statement.setObject(1, tenantId);
            statement.setObject(2, batchId);
            statement.setObject(3, item.documentId());
            statement.setString(4, item.status());
            statement.setString(5, item.message());
        });
    }

    private void recordFailedAttempt(UUID tenantId, UUID actor, UUID batchId, LocalDate from, LocalDate to,
                                     UUID mandantId, String filename, RuntimeException cause) {
        try {
            failureTransaction.executeWithoutResult(status -> insertBatch(
                    tenantId, actor, batchId, from, to, mandantId, 0, BigDecimal.ZERO.setScale(2),
                    "FAILED", filename, null));
        }
        catch (RuntimeException recordingFailure) {
            // Never hide the original failure behind the bookkeeping of the failure.
            log.error("Could not record the failed DATEV export {}: {}", batchId, recordingFailure.getMessage());
        }
        log.warn("DATEV export {} failed: {}", batchId, cause.getMessage());
    }

    // ------------------------------------------------------------------ validation

    private static void requireTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "No tenant in context");
        }
    }

    private static void requirePeriod(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Zeitraum mit Anfang und Ende ist erforderlich.");
        }
        if (from.isAfter(to)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Zeitraum beginnt nach seinem Ende.");
        }
    }

    /**
     * A client filter must name a client of this account.
     *
     * <p>The foreign key alone is not enough: PostgreSQL checks {@code contacts (id)} without applying the
     * contacts policy, so an identifier from another account would be accepted by the database. The explicit
     * lookup is the second guard and the one that decides.</p>
     */
    private void requireOwnMandant(UUID tenantId, UUID mandantId) {
        if (mandantId == null) {
            return;
        }
        Long count = jdbc.queryForObject("""
                select count(*) from contacts
                 where id = ? and tenant_id = ? and deleted_at is null
                """, Long.class, mandantId, tenantId);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der gewählte Mandant gehört nicht zu diesem Konto.");
        }
    }

    private static String buildFilename(LocalDate from, LocalDate to, UUID batchId) {
        return "DATEV-Export_" + from + "_bis_" + to + "_" + batchId.toString().substring(0, 8) + ".csv";
    }
}
