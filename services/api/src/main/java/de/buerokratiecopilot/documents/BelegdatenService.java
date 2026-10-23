package de.buerokratiecopilot.documents;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.documents.fields.FieldValueParser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The figures the Posteingang shows next to a voucher (Kanzlei edition, §5.1): amount, voucher date,
 * supplier and the next deadline.
 *
 * <p>Everything here is a reading of values the platform already extracted, never a guess. A document
 * whose extraction found no amount shows no amount; a document without a deadline shows no badge. That
 * is the difference between an empty cell and an invented one.</p>
 *
 * <p>The list is read with one query for the whole page. A per-row lookup would turn a list of 100
 * vouchers into 400 round trips, which is exactly the kind of screen that looks fine in a test and
 * crawls in an office. The lateral subqueries choose one value per document, so the page is one query
 * regardless of its size.</p>
 */
@Service
public class BelegdatenService {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /**
     * The amount the office books. The preference is the one the DATEV export already uses: the gross
     * total before a generic amount before an outstanding one, and a confirmed value before an
     * unconfirmed one. Reading a different value here than the export would show one number in the
     * queue and write another into the CSV.
     */
    private static final String FOR_DOCUMENT_IDS = """
            select d.id as document_id,
                   d.document_date as stored_document_date,
                   document_date.normalized_value as extracted_document_date,
                   amount.normalized_value as amount_value,
                   amount.field_value as amount_original,
                   currency.normalized_value as currency,
                   supplier.normalized_value as supplier,
                   deadline.due_on as deadline_on
              from documents d
              -- The date of the voucher itself. The column is the authoritative one (it may have been
              -- corrected by hand); the extracted date is the fallback, read with the same precedence the
              -- orchestrator uses for the document's own date.
              left join lateral (
                   select x.normalized_value
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
                    limit 1) document_date on true
              left join lateral (
                   select x.normalized_value, x.field_value
                     from document_extractions x
                    where x.document_id = d.id
                      and x.field_key in ('Brutto', 'Betrag', 'OffenerBetrag')
                    order by case x.field_key
                                 when 'Brutto' then 0
                                 when 'Betrag' then 1
                                 else 2
                             end,
                             x.verified_at desc nulls last,
                             x.confidence desc nulls last,
                             x.created_at desc
                    limit 1) amount on true
              left join lateral (
                   select x.normalized_value
                     from document_extractions x
                    where x.document_id = d.id and x.field_key = 'Waehrung'
                    order by x.verified_at desc nulls last, x.created_at desc
                    limit 1) currency on true
              left join lateral (
                   select x.normalized_value
                     from document_extractions x
                    where x.document_id = d.id and x.field_key = 'Lieferant'
                    order by x.verified_at desc nulls last, x.created_at desc
                    limit 1) supplier on true
              -- The next open deadline of the document. A deadline of a deleted task is not one the
              -- office can act on, and an inactive one was explicitly withdrawn: neither may raise a
              -- red badge.
              left join lateral (
                   select coalesce(dd.due_date, (dd.due_at at time zone 'Europe/Berlin')::date) as due_on
                     from task_deadlines dd
                     join tasks t on t.id = dd.task_id and t.deleted_at is null
                    where dd.source_document_id = d.id
                      and dd.tenant_id = d.tenant_id
                      and dd.is_active
                    order by coalesce(dd.due_date, (dd.due_at at time zone 'Europe/Berlin')::date) asc
                    limit 1) deadline on true
             where d.tenant_id = ? and d.deleted_at is null
            """;

    private final JdbcTemplate jdbc;

    public BelegdatenService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One voucher's figures. A deadline that lies in the past is kept: "overdue" is a state the office
     * must see, not a deadline that silently disappears once its date has passed.
     */
    public record Belegdaten(BigDecimal amount, String amountCurrency, LocalDate documentDate,
                             String supplierName, LocalDate deadlineDate, Integer deadlineDaysLeft) {

        static final Belegdaten EMPTY = new Belegdaten(null, null, null, null, null, null);
    }

    /** The figures of a whole page, keyed by document id; documents without values simply have none. */
    @Transactional(readOnly = true)
    public Map<UUID, Belegdaten> forDocuments(Collection<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        UUID tenantId = tenant();
        List<UUID> ids = new ArrayList<>(new java.util.LinkedHashSet<>(documentIds));
        StringBuilder placeholders = new StringBuilder();
        Object[] arguments = new Object[ids.size() + 1];
        arguments[0] = tenantId;
        for (int i = 0; i < ids.size(); i++) {
            placeholders.append(i == 0 ? "?" : ",?");
            arguments[i + 1] = ids.get(i);
        }

        Map<UUID, Belegdaten> answer = new LinkedHashMap<>();
        jdbc.query(FOR_DOCUMENT_IDS + " and d.id in (" + placeholders + ")",
                rs -> {
                    UUID id = rs.getObject("document_id", UUID.class);
                    BigDecimal amount = decimal(rs.getString("amount_value"));
                    LocalDate deadline = rs.getObject("deadline_on", LocalDate.class);
                    LocalDate documentDate = rs.getObject("stored_document_date", LocalDate.class);
                    if (documentDate == null) {
                        documentDate = date(rs.getString("extracted_document_date"));
                    }
                    answer.put(id, new Belegdaten(
                            amount,
                            amount == null ? null : currency(rs.getString("currency"), rs.getString("amount_original")),
                            documentDate,
                            blankToNull(rs.getString("supplier")),
                            deadline,
                            deadline == null
                                    ? null
                                    : (int) ChronoUnit.DAYS.between(LocalDate.now(BERLIN), deadline)));
                }, arguments);
        return answer;
    }

    /** The figures of one voucher, or the empty set when it carries none. */
    @Transactional(readOnly = true)
    public Belegdaten forDocument(UUID documentId) {
        return forDocuments(List.of(documentId)).getOrDefault(documentId, Belegdaten.EMPTY);
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }

    /**
     * A decimal the extractor stored as text. A value that cannot be read is null, not zero: a zero
     * amount is a statement about the voucher and must not stand in for "unknown".
     */
    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.strip());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    /** An ISO date the extractor stored as text; anything else is "no date" rather than a guessed one. */
    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        }
        catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    /**
     * The currency of the amount. The extraction stores it in its own field only when the document
     * names it next to a "Währung" label, which most invoices do not; the symbol next to the amount is
     * then the evidence, and {@link FieldValueParser} already knows how to read it. Without either,
     * the answer is null — the screen decides how to write an amount without a currency, the API does
     * not invent one.
     */
    private static String currency(String extracted, String amountOriginal) {
        if (extracted != null && extracted.strip().matches("[A-Z]{3}")) {
            return extracted.strip();
        }
        FieldValueParser.Parsed parsed = FieldValueParser.parse(amountOriginal, "CURRENCY");
        return parsed.valid() ? parsed.normalized() : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
