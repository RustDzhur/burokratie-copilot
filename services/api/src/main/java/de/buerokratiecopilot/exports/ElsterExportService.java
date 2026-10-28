package de.buerokratiecopilot.exports;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.settings.TaxSettingsService;
import de.buerokratiecopilot.settings.TaxSettingsService.TaxSettings;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Builds the ELSTER/DATEV export of one business year (docs/ELSTER_MAPPING.md §5).
 *
 * <p>The rows come from the category of a document and the amounts of the document itself or of the
 * invoice read from it. The category contributes the EÜR line, the Kennzahl and the DATEV accounts; the
 * export does not hard-code them, because V81 made the catalogue a table. The direction of a row
 * ({@code booking_kind}) decides whether its amount belongs in the expenses at all:</p>
 *
 * <ul>
 *   <li>{@code fahrzeug.privatnutzung} is a business <em>income</em> (EÜR line 20, Kz 106), never an
 *       expense, even though the catalogue marks it as one so that it reaches a person for review;</li>
 *   <li>{@code privatentnahme.*} is an Entnahme and is not part of the EÜR expense lines;</li>
 *   <li>an investment ({@code anlagevermoegen.*}) becomes an asset, not an expense — the expense is the
 *       AfA, which this export cannot compute (it needs the AVEÜR);</li>
 *   <li>everything else is an ordinary expense.</li>
 * </ul>
 *
 * <p>Bewirtung is split 70/30 (EÜR line 63, Kz 175/165) when the client has not switched the split off;
 * a gift above 50 € net is exported as a warning and without input tax (§ 4 Abs. 5 Satz 1 Nr. 1 EStG).
 * A Kleinunternehmer (§ 19 UStG) exports gross amounts and no VAT at all.</p>
 *
 * <p>Nothing here blocks: an incomplete receipt becomes a row with a warning. The export is a working
 * aid for a person, not a filed tax return.</p>
 */
@Service
public class ElsterExportService {

    /** The Soll/Haben direction of a row; it decides whether the amount reaches an expense line. */
    public enum BookingKind {
        AUSGABE, EINNAHME, ENTNAHME, AFA, GWG
    }

    /** One line of the export. */
    public record Row(String categoryCode, String euerLine, String euerKz, String datevSkr03, String datevSkr04,
                      BookingKind bookingKind, BigDecimal netto, BigDecimal ust, BigDecimal brutto,
                      boolean vorsteuerAbziehbar, BigDecimal bewirtungAbziehbar70,
                      BigDecimal bewirtungNichtAbziehbar30, UUID documentId, LocalDate date) {
    }

    /** A finding a person must look at; it never removes a row from the export. */
    public record Warning(String code, String message, UUID documentId, Map<String, Object> details) {
    }

    /** The totals an adviser checks first. Expense totals exclude income, Entnahmen and investments. */
    public record Summary(int rows, BigDecimal netto, BigDecimal ust, BigDecimal brutto,
                          BigDecimal expensesNetto, BigDecimal expensesUst, BigDecimal expensesBrutto,
                          BigDecimal vorsteuerAbziehbar, BigDecimal bewirtungAbziehbar70,
                          BigDecimal bewirtungNichtAbziehbar30, BigDecimal incomeNetto,
                          BigDecimal entnahmeNetto, BigDecimal investmentsNetto, List<Warning> warnings) {
    }

    /** Everything the endpoint returns as JSON. */
    public record Export(int year, TaxSettings settings, Summary summary, List<Row> rows) {
    }

    private static final BigDecimal GIFT_LIMIT = new BigDecimal("50.00");
    private static final BigDecimal SEVENTY_PERCENT = new BigDecimal("0.70");

    private final JdbcTemplate jdbc;
    private final TaxSettingsService taxSettings;

    public ElsterExportService(JdbcTemplate jdbc, TaxSettingsService taxSettings) {
        this.jdbc = jdbc;
        this.taxSettings = taxSettings;
    }

    /** One document of the year, before the direction and the tax rules are applied. */
    private record Raw(UUID documentId, LocalDate date, String categoryCode, String euerLine, String euerKz,
                       String skr03, String skr04, boolean businessExpense,
                       BigDecimal invoiceNet, BigDecimal invoiceTax, BigDecimal invoiceGross,
                       String extractedNet, String extractedTax, String extractedGross,
                       String recipient, boolean dateMissing) {
    }

    private record Amounts(BigDecimal netto, BigDecimal ust, BigDecimal brutto) {
    }

    /**
     * The export of one year. A year without a single categorised document is a valid answer: an empty
     * list and zero totals, never an error.
     */
    @Transactional(readOnly = true)
    public Export export(int year) {
        UUID tenantId = RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
        TaxSettings settings = taxSettings.current();

        LocalDate from = LocalDate.of(year, 1, 1);
        List<Raw> documents = jdbc.query("""
                        select d.id as document_id,
                               coalesce(d.document_date, d.received_at::date) as document_date,
                               d.document_date is null as date_missing,
                               c.code as category_code,
                               c.euer_line,
                               c.euer_kz,
                               c.datev_skr03,
                               c.datev_skr04,
                               c.is_business_expense,
                               i.net_amount as invoice_net,
                               i.tax_amount as invoice_tax,
                               i.gross_amount as invoice_gross,
                               (select e.normalized_value from document_extractions e
                                 where e.document_id = d.id and e.field_key = 'Netto'
                                 order by e.created_at desc, e.id desc limit 1) as extracted_net,
                               (select e.normalized_value from document_extractions e
                                 where e.document_id = d.id and e.field_key = 'Umsatzsteuer'
                                 order by e.created_at desc, e.id desc limit 1) as extracted_tax,
                               coalesce(
                                   (select e.normalized_value from document_extractions e
                                     where e.document_id = d.id and e.field_key = 'Brutto'
                                     order by e.created_at desc, e.id desc limit 1),
                                   (select e.normalized_value from document_extractions e
                                     where e.document_id = d.id and e.field_key = 'Gesamtbetrag'
                                     order by e.created_at desc, e.id desc limit 1),
                                   (select e.normalized_value from document_extractions e
                                     where e.document_id = d.id and e.field_key = 'Betrag'
                                     order by e.created_at desc, e.id desc limit 1)
                               ) as extracted_gross,
                               d.recipient as recipient
                        from documents d
                        join expense_categories c on c.id = d.expense_category_id
                        left join lateral (
                            select i.net_amount, i.tax_amount, i.gross_amount
                            from invoices i
                            where i.document_id = d.id and i.direction = 'INCOMING'
                              and i.status <> 'CANCELLED' and i.deleted_at is null
                            order by i.created_at desc
                            limit 1
                        ) i on true
                        where d.tenant_id = ?
                          and d.deleted_at is null
                          and coalesce(d.document_date, d.received_at::date) >= ?
                          and coalesce(d.document_date, d.received_at::date) < ?
                        order by d.document_date, c.code, d.id
                        """,
                (rs, rowNum) -> new Raw(
                        rs.getObject("document_id", UUID.class),
                        rs.getObject("document_date", LocalDate.class),
                        rs.getString("category_code"),
                        rs.getString("euer_line"),
                        rs.getString("euer_kz"),
                        rs.getString("datev_skr03"),
                        rs.getString("datev_skr04"),
                        rs.getBoolean("is_business_expense"),
                        rs.getBigDecimal("invoice_net"),
                        rs.getBigDecimal("invoice_tax"),
                        rs.getBigDecimal("invoice_gross"),
                        rs.getString("extracted_net"),
                        rs.getString("extracted_tax"),
                        rs.getString("extracted_gross"),
                        rs.getString("recipient"),
                        rs.getBoolean("date_missing")),
                tenantId, from, from.plusYears(1));

        List<Warning> warnings = new ArrayList<>();
        List<Row> rows = new ArrayList<>(documents.size());
        Map<String, GiftTotal> giftsByRecipient = new LinkedHashMap<>();
        for (Raw document : documents) {
            Amounts amounts = amountsOf(document, settings, warnings);
            BookingKind kind = bookingKind(document);
            boolean gift = "betriebsausgaben.geschenke".equals(document.categoryCode());
            boolean deductibleInputTax = inputTaxDeductible(document, kind, amounts, gift, settings);
            BigDecimal[] entertainment = entertainmentSplit(document, amounts.netto(), settings);

            if (document.dateMissing()) {
                warnings.add(new Warning("DOCUMENT_DATE_MISSING",
                        "Belegdatum fehlt; fuer die Jahreszuordnung wurde das Eingangsdatum verwendet.",
                        document.documentId(), Map.of()));
            }

            Row row = new Row(document.categoryCode(), blankToNull(document.euerLine()),
                    blankToNull(document.euerKz()), blankToNull(document.skr03()), blankToNull(document.skr04()),
                    kind, amounts.netto(), amounts.ust(), amounts.brutto(), deductibleInputTax,
                    entertainment[0], entertainment[1], document.documentId(), document.date());
            rows.add(row);

            if (row.euerLine() == null && kind != BookingKind.ENTNAHME) {
                warnings.add(new Warning("CATEGORY_WITHOUT_EUER_LINE",
                        "Kategorie ohne EÜR-Zeile (Sammelkategorie); der Beleg ist nicht in den Ausgaben enthalten.",
                        document.documentId(), Map.of("categoryCode", String.valueOf(document.categoryCode()))));
            }

            if (gift) {
                warnAboutGift(document, amounts, warnings);
                collectGift(giftsByRecipient, document, amounts.netto());
            }
        }
        warnAboutGiftRecipients(giftsByRecipient, warnings);

        Summary summary = summaryOf(rows, warnings);
        return new Export(year, settings, summary, rows);
    }

    /** The columns the API and the CSV promise; the order is the one a DATEV clerk reads. */
    public static final List<String> CSV_HEADER = List.of(
            "category_code", "euer_line", "euer_kz", "datev_skr03", "datev_skr04", "booking_kind",
            "netto", "ust", "brutto", "vorsteuer_abziehbar", "bewirtung_abziehbar_70",
            "bewirtung_nicht_abziehbar_30", "document_id", "date");

    private Summary summaryOf(List<Row> rows, List<Warning> warnings) {
        BigDecimal netto = BigDecimal.ZERO;
        BigDecimal ust = BigDecimal.ZERO;
        BigDecimal brutto = BigDecimal.ZERO;
        BigDecimal expensesNetto = BigDecimal.ZERO;
        BigDecimal expensesUst = BigDecimal.ZERO;
        BigDecimal expensesBrutto = BigDecimal.ZERO;
        BigDecimal inputTax = BigDecimal.ZERO;
        BigDecimal entertainment = BigDecimal.ZERO;
        BigDecimal notDeductibleEntertainment = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal entnahme = BigDecimal.ZERO;
        BigDecimal investments = BigDecimal.ZERO;
        for (Row row : rows) {
            netto = netto.add(row.netto());
            ust = ust.add(row.ust());
            brutto = brutto.add(row.brutto());
            switch (row.bookingKind()) {
                case EINNAHME -> income = income.add(row.netto());
                case ENTNAHME -> entnahme = entnahme.add(row.netto());
                case AFA -> investments = investments.add(row.netto());
                default -> {
                    if (row.euerLine() != null) {
                        expensesNetto = expensesNetto.add(row.netto());
                        expensesUst = expensesUst.add(row.ust());
                        expensesBrutto = expensesBrutto.add(row.brutto());
                    }
                }
            }
            if (row.vorsteuerAbziehbar()) {
                inputTax = inputTax.add(row.ust());
            }
            entertainment = entertainment.add(row.bewirtungAbziehbar70());
            notDeductibleEntertainment = notDeductibleEntertainment.add(row.bewirtungNichtAbziehbar30());
        }
        return new Summary(rows.size(), scale(netto), scale(ust), scale(brutto), scale(expensesNetto),
                scale(expensesUst), scale(expensesBrutto), scale(inputTax), scale(entertainment),
                scale(notDeductibleEntertainment), scale(income), scale(entnahme), scale(investments),
                List.copyOf(warnings));
    }

    /**
     * Prefer the invoice read from the document; fall back to the amounts the document itself states.
     * A receipt without any readable amount becomes 0,00 € plus a warning — the row is still exported,
     * because hiding it would hide a receipt from the adviser.
     */
    private Amounts amountsOf(Raw document, TaxSettings settings, List<Warning> warnings) {
        BigDecimal net = document.invoiceNet();
        BigDecimal tax = document.invoiceTax();
        BigDecimal gross = document.invoiceGross();
        boolean missing = net == null && tax == null && gross == null;
        if (missing) {
            net = parseAmount(document.extractedNet());
            tax = parseAmount(document.extractedTax());
            gross = parseAmount(document.extractedGross());
            missing = net == null && tax == null && gross == null;
            if (missing) {
                warnings.add(new Warning("DOCUMENT_AMOUNT_MISSING",
                        "Beleg ohne lesbaren Betrag; der Betrag ist 0,00 € und muss geprüft werden.",
                        document.documentId(), Map.of()));
            }
        }
        if (gross == null && net != null) {
            gross = net.add(tax == null ? BigDecimal.ZERO : tax);
        }
        if (net == null && gross != null) {
            net = tax == null ? gross : gross.subtract(tax);
        }
        if (tax == null) {
            tax = gross != null && net != null ? gross.subtract(net) : BigDecimal.ZERO;
        }
        if (net == null) {
            net = BigDecimal.ZERO;
        }
        if (gross == null) {
            gross = net.add(tax);
        }
        // § 19 UStG: a Kleinunternehmer states gross amounts and no VAT — both in the EÜR and in the export.
        if (settings.kleinunternehmer()) {
            net = gross;
            tax = BigDecimal.ZERO;
        }
        return new Amounts(scale(net), scale(tax), scale(gross));
    }

    private BookingKind bookingKind(Raw document) {
        String code = document.categoryCode();
        if ("fahrzeug.privatnutzung".equals(code)) {
            return BookingKind.EINNAHME;
        }
        if (code != null && code.startsWith("privatentnahme")) {
            return BookingKind.ENTNAHME;
        }
        if (!document.businessExpense()) {
            return BookingKind.ENTNAHME;
        }
        if ("anlagevermoegen.gwg".equals(code) || "anlagevermoegen.gwg_sammelposten".equals(code)) {
            return BookingKind.GWG;
        }
        if (code != null && (code.startsWith("anlagevermoegen.")
                || "betriebsausgaben.abschreibungen".equals(code))) {
            return BookingKind.AFA;
        }
        return BookingKind.AUSGABE;
    }

    /**
     * § 15 UStG: input tax is deductible on an ordinary business expense with VAT. A gift above 50 € net
     * is not deductible at all (§ 4 Abs. 5 Satz 1 Nr. 1 EStG), and a Kleinunternehmer has no input tax.
     */
    private boolean inputTaxDeductible(Raw document, BookingKind kind, Amounts amounts, boolean gift,
                                       TaxSettings settings) {
        if (settings.kleinunternehmer() || !document.businessExpense()
                || kind == BookingKind.EINNAHME || kind == BookingKind.ENTNAHME) {
            return false;
        }
        if (amounts.ust().signum() <= 0) {
            return false;
        }
        return !(gift && amounts.netto().compareTo(GIFT_LIMIT) > 0);
    }

    /** Bewirtung line 63 has two amount columns: 70 % deductible (Kz 175) and 30 % not (Kz 165). */
    private BigDecimal[] entertainmentSplit(Raw document, BigDecimal net, TaxSettings settings) {
        if (!"betriebsausgaben.bewirtung".equals(document.categoryCode()) || !settings.bewirtungSplit()) {
            return new BigDecimal[] {BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2)};
        }
        BigDecimal deductible = scale(net.multiply(SEVENTY_PERCENT));
        return new BigDecimal[] {deductible, scale(net.subtract(deductible))};
    }

    private void warnAboutGift(Raw document, Amounts amounts, List<Warning> warnings) {
        if (amounts.netto().compareTo(GIFT_LIMIT) > 0) {
            warnings.add(new Warning("GIFT_OVER_50_SINGLE",
                    "Geschenk über 50,00 € netto: kein Betriebsausgaben- und kein Vorsteuerabzug (Kz 164).",
                    document.documentId(), Map.of("netto", amounts.netto())));
        }
    }

    private static final class GiftTotal {
        private BigDecimal netto = BigDecimal.ZERO;
        private final List<UUID> documents = new ArrayList<>();
    }

    private void collectGift(Map<String, GiftTotal> giftsByRecipient, Raw document, BigDecimal netto) {
        String recipient = document.recipient() == null ? "" : document.recipient().strip();
        if (recipient.isEmpty()) {
            return;
        }
        GiftTotal total = giftsByRecipient.computeIfAbsent(recipient, key -> new GiftTotal());
        total.netto = total.netto.add(netto);
        total.documents.add(document.documentId());
    }

    /**
     * The 50 € limit applies per recipient and business year, so one 30 € gift is only harmless until a
     * second one goes to the same person. The export warns instead of guessing whether the limit is met.
     */
    private void warnAboutGiftRecipients(Map<String, GiftTotal> giftsByRecipient, List<Warning> warnings) {
        giftsByRecipient.forEach((recipient, total) -> {
            if (total.netto.compareTo(GIFT_LIMIT) > 0) {
                warnings.add(new Warning("GIFT_OVER_50_RECIPIENT",
                        "Geschenke an denselben Empfänger überschreiten im Wirtschaftsjahr 50,00 € netto; "
                                + "Abzug und Vorsteuer prüfen.",
                        null, Map.of("recipient", recipient, "netto", scale(total.netto),
                        "documentIds", total.documents.stream().map(UUID::toString).toList())));
            }
        });
    }

    /** Reads a German or plain decimal amount ({@code 1.234,56}, {@code 1234.56}, {@code 119 €}). */
    static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replaceAll("[^0-9,.-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".") || cleaned.equals(",")) {
            return null;
        }
        boolean comma = cleaned.indexOf(',') >= 0;
        if (comma) {
            cleaned = cleaned.replace(".", "").replace(',', '.');
        }
        else {
            int lastDot = cleaned.lastIndexOf('.');
            int firstDot = cleaned.indexOf('.');
            if (firstDot != lastDot) {
                cleaned = cleaned.substring(0, lastDot).replace(".", "") + cleaned.substring(lastDot);
            }
        }
        try {
            return scale(new BigDecimal(cleaned));
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
