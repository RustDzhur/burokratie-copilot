package de.buerokratiecopilot.billing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.mail.EmailMessage;
import de.buerokratiecopilot.shared.mail.EmailSender;

/**
 * Invoices the platform issues to its customers, and what happens when one is paid.
 *
 * <p>Two ways to pay, neither through a payment provider: a bank transfer against an invoice (the PDF carries the
 * bank details and the payment reference) or a transfer to a crypto wallet (the invoice names asset, network,
 * address and amount). Nothing here can see a payment arrive; the platform administrator confirms it, and
 * confirming is what moves the end of the access forward. An invoice can only be created when the payee data
 * has been filled in, so the platform never hands out an invoice that says nowhere to pay.</p>
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int DUE_DAYS = 14;

    private final JdbcTemplate jdbc;
    private final PlatformSettings settings;
    private final SubscriptionService subscriptions;
    private final EmailSender emailSender;

    public BillingService(JdbcTemplate jdbc, PlatformSettings settings, SubscriptionService subscriptions,
                          EmailSender emailSender) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.subscriptions = subscriptions;
        this.emailSender = emailSender;
    }

    /** What a customer enters to get an invoice. */
    public record InvoiceRequest(String method, Integer months, String customerName, String customerAddress,
                                 String customerCountry, String customerVatId) {
    }

    public boolean invoiceConfigured() {
        Map<String, Object> payee = settings.object("billing.payee");
        return notBlank(payee.get("name")) && notBlank(payee.get("iban"));
    }

    public boolean cryptoConfigured() {
        Map<String, Object> crypto = settings.object("billing.crypto");
        return notBlank(crypto.get("asset")) && notBlank(crypto.get("address")) && notBlank(crypto.get("eurPerUnit"));
    }

    @Transactional
    public Map<String, Object> create(UUID tenantId, String customerEmail, InvoiceRequest request) {
        String method = request.method() == null ? "" : request.method().toUpperCase(Locale.ROOT);
        if (!"INVOICE".equals(method) && !"CRYPTO".equals(method)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Zahlungsart muss INVOICE oder CRYPTO sein");
        }
        if ("INVOICE".equals(method) && !invoiceConfigured()) {
            throw ApiException.conflict("Die Zahlung per Rechnung ist noch nicht eingerichtet");
        }
        if ("CRYPTO".equals(method) && !cryptoConfigured()) {
            throw ApiException.conflict("Die Zahlung per Krypto-Wallet ist noch nicht eingerichtet");
        }
        int months = request.months() == null ? 1 : request.months();
        if (months < 1 || months > 24) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Laufzeit muss zwischen 1 und 24 Monaten liegen");
        }
        String name = required(request.customerName(), "Name oder Firma", 200);
        String address = required(request.customerAddress(), "Anschrift", 500);
        String country = required(request.customerCountry(), "Land", 2).toUpperCase(Locale.ROOT);
        if (country.length() != 2) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Das Land wird als Ländercode mit zwei Buchstaben angegeben");
        }
        String vatId = request.customerVatId() == null || request.customerVatId().isBlank() ? null
                : request.customerVatId().trim();

        Map<String, Object> plan = jdbc.queryForMap("""
                select p.code, p.price_cents from subscriptions s join plans p on p.code = s.plan_code
                where s.tenant_id = ?
                """, tenantId);
        String planCode = (String) plan.get("code");
        int amount = ((Number) plan.get("price_cents")).intValue() * months;

        // The same open invoice is returned instead of a second one: pressing the button twice must not
        // litter the books with invoices nobody will pay.
        List<UUID> open = jdbc.query("""
                select id from billing_invoices where tenant_id = ? and status = 'ISSUED' and method = ?
                  and plan_code = ? and period_months = ? and amount_cents = ?
                """, (rs, i) -> rs.getObject("id", UUID.class), tenantId, method, planCode, months, amount);
        if (!open.isEmpty()) {
            return get(tenantId, open.getFirst());
        }

        long sequence = jdbc.queryForObject("select nextval('billing_invoice_seq')", Long.class);
        String number = "BC-%d-%05d".formatted(LocalDate.now(ZoneId.of("Europe/Berlin")).getYear(), sequence);
        Instant due = Instant.now().plus(DUE_DAYS, ChronoUnit.DAYS);

        String asset = null;
        String network = null;
        String walletAddress = null;
        BigDecimal cryptoAmount = null;
        if ("CRYPTO".equals(method)) {
            Map<String, Object> crypto = settings.object("billing.crypto");
            asset = String.valueOf(crypto.get("asset"));
            network = crypto.get("network") == null ? null : String.valueOf(crypto.get("network"));
            walletAddress = String.valueOf(crypto.get("address"));
            BigDecimal rate = new BigDecimal(String.valueOf(crypto.get("eurPerUnit")).trim().replace(',', '.'));
            if (rate.signum() <= 0) {
                throw ApiException.conflict("Der Umrechnungskurs der Krypto-Zahlung ist ungültig");
            }
            cryptoAmount = BigDecimal.valueOf(amount).movePointLeft(2).divide(rate, 6, RoundingMode.UP);
        }

        UUID id = jdbc.queryForObject("""
                insert into billing_invoices (tenant_id, invoice_number, plan_code, period_months, amount_cents,
                    status, method, reference, customer_name, customer_address, customer_country, customer_vat_id,
                    customer_email, crypto_asset, crypto_network, crypto_address, crypto_amount, due_at)
                values (?, ?, ?, ?, ?, 'ISSUED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, tenantId, number, planCode, months, amount, method, number, name, address, country,
                vatId, customerEmail, asset, network, walletAddress, cryptoAmount, Timestamp.from(due));
        return get(tenantId, id);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId) {
        return jdbc.queryForList("""
                select id, invoice_number, plan_code, period_months, amount_cents, currency, status, method,
                       reference, issued_at, due_at, paid_at, crypto_asset, crypto_network, crypto_address,
                       crypto_amount
                from billing_invoices where tenant_id = ? order by issued_at desc
                """, tenantId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID tenantId, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, invoice_number, plan_code, period_months, amount_cents, currency, status, method,
                       reference, issued_at, due_at, paid_at, crypto_asset, crypto_network, crypto_address,
                       crypto_amount
                from billing_invoices where tenant_id = ? and id = ?
                """, tenantId, id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("invoice");
        }
        return rows.getFirst();
    }

    /**
     * Confirms that an invoice was paid: the access is extended by the paid period. Idempotent per invoice.
     * Must be called in the system context (no tenant), because it works on the invoice of another tenant.
     */
    @Transactional
    public void markPaid(UUID invoiceId, UUID admin) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select tenant_id, period_months, status, invoice_number, customer_email
                from billing_invoices where id = ? for update
                """, invoiceId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("invoice");
        }
        Map<String, Object> invoice = rows.getFirst();
        if (!"ISSUED".equals(invoice.get("status"))) {
            throw ApiException.conflict("Diese Rechnung ist nicht mehr offen");
        }
        UUID tenantId = (UUID) invoice.get("tenant_id");
        jdbc.update("update billing_invoices set status = 'PAID', paid_at = now(), confirmed_by = ? where id = ?",
                admin, invoiceId);
        subscriptions.extend(tenantId, ((Number) invoice.get("period_months")).intValue());
        notifyPaid((String) invoice.get("customer_email"), (String) invoice.get("invoice_number"));
    }

    @Transactional
    public void cancel(UUID invoiceId) {
        int changed = jdbc.update("update billing_invoices set status = 'CANCELLED' where id = ? and status = 'ISSUED'",
                invoiceId);
        if (changed == 0) {
            throw ApiException.conflict("Nur eine offene Rechnung kann storniert werden");
        }
    }

    private void notifyPaid(String email, String number) {
        if (email == null || email.isBlank()) {
            return;
        }
        try {
            emailSender.send(EmailMessage.of(email, "Zahlung eingegangen – Rechnung " + number, """
                    Guten Tag,

                    die Zahlung zur Rechnung %s ist bei uns eingegangen. Ihr Zugang zum Bürokratie-Copilot wurde verlängert.

                    Vielen Dank!
                    """.formatted(number)));
        }
        catch (RuntimeException ex) {
            log.warn("Payment confirmation mail for {} could not be sent", number, ex);
        }
    }

    // ------------------------------------------------------------------------------------------------ PDF

    @Transactional(readOnly = true)
    public byte[] pdf(UUID tenantId, UUID invoiceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from billing_invoices where tenant_id = ? and id = ?",
                tenantId, invoiceId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("invoice");
        }
        return render(rows.getFirst());
    }

    @Transactional(readOnly = true)
    public byte[] pdfAsAdmin(UUID invoiceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from billing_invoices where id = ?", invoiceId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("invoice");
        }
        return render(rows.getFirst());
    }

    private byte[] render(Map<String, Object> invoice) {
        Map<String, Object> payee = settings.object("billing.payee");
        double vatPercent = settings.vatPercent();
        int gross = ((Number) invoice.get("amount_cents")).intValue();
        String planName = jdbc.queryForObject("select name from plans where code = ?", String.class,
                invoice.get("plan_code"));
        int months = ((Number) invoice.get("period_months")).intValue();

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try (PDPageContentStream c = new PDPageContentStream(document, page)) {
                float y = 790;
                y = line(c, bold, 16, 56, y, "Rechnung " + invoice.get("invoice_number"));
                y -= 6;
                y = line(c, regular, 10, 56, y, text(payee.get("name")));
                for (String part : String.valueOf(payee.getOrDefault("address", "")).split("\\R")) {
                    y = line(c, regular, 10, 56, y, part);
                }
                if (notBlank(payee.get("taxId"))) {
                    y = line(c, regular, 10, 56, y, "Steuernummer: " + payee.get("taxId"));
                }
                if (notBlank(payee.get("vatId"))) {
                    y = line(c, regular, 10, 56, y, "USt-IdNr.: " + payee.get("vatId"));
                }
                y -= 18;
                y = line(c, bold, 10, 56, y, "Rechnungsempfänger");
                y = line(c, regular, 10, 56, y, text(invoice.get("customer_name")));
                for (String part : text(invoice.get("customer_address")).split("\\R")) {
                    y = line(c, regular, 10, 56, y, part);
                }
                y = line(c, regular, 10, 56, y, "Land: " + invoice.get("customer_country"));
                if (invoice.get("customer_vat_id") != null) {
                    y = line(c, regular, 10, 56, y, "USt-IdNr.: " + invoice.get("customer_vat_id"));
                }
                y -= 18;
                y = line(c, regular, 10, 56, y, "Rechnungsdatum: " + date(invoice.get("issued_at"))
                        + "      Fällig am: " + date(invoice.get("due_at")));
                y -= 14;
                y = line(c, bold, 10, 56, y, "Leistung");
                y = line(c, regular, 10, 56, y, "Bürokratie-Copilot, Tarif " + planName + ", " + months
                        + (months == 1 ? " Monat" : " Monate"));
                y = line(c, regular, 10, 56, y, "Betrag: " + euro(gross));
                if (vatPercent > 0) {
                    BigDecimal net = BigDecimal.valueOf(gross).divide(BigDecimal.valueOf(1 + vatPercent / 100), 0,
                            RoundingMode.HALF_UP);
                    y = line(c, regular, 10, 56, y, "enthaltene Umsatzsteuer %s %%: %s".formatted(
                            trim(vatPercent), euro(gross - net.intValue())));
                }
                y -= 18;
                if ("CRYPTO".equals(invoice.get("method"))) {
                    y = line(c, bold, 10, 56, y, "Zahlung per Krypto-Wallet");
                    y = line(c, regular, 10, 56, y, "Betrag: " + invoice.get("crypto_amount") + " "
                            + invoice.get("crypto_asset"));
                    if (invoice.get("crypto_network") != null) {
                        y = line(c, regular, 10, 56, y, "Netzwerk: " + invoice.get("crypto_network"));
                    }
                    y = line(c, regular, 10, 56, y, "Adresse: " + invoice.get("crypto_address"));
                    y = line(c, regular, 10, 56, y, "Referenz (falls möglich angeben): " + invoice.get("reference"));
                }
                else {
                    y = line(c, bold, 10, 56, y, "Zahlung per Überweisung");
                    y = line(c, regular, 10, 56, y, "Empfänger: " + text(payee.get("name")));
                    y = line(c, regular, 10, 56, y, "IBAN: " + text(payee.get("iban")));
                    if (notBlank(payee.get("bic"))) {
                        y = line(c, regular, 10, 56, y, "BIC: " + payee.get("bic"));
                    }
                    y = line(c, regular, 10, 56, y, "Verwendungszweck: " + invoice.get("reference"));
                }
                if (notBlank(payee.get("note"))) {
                    y -= 14;
                    for (String part : String.valueOf(payee.get("note")).split("\\R")) {
                        y = line(c, regular, 9, 56, y, part);
                    }
                }
            }
            document.save(out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            throw new IllegalStateException("The invoice PDF could not be created", ex);
        }
    }

    /** Writes one line; characters the built-in font cannot show are replaced rather than failing the invoice. */
    private static float line(PDPageContentStream c, PDType1Font font, float size, float x, float y, String raw)
            throws IOException {
        c.beginText();
        c.setFont(font, size);
        c.newLineAtOffset(x, y);
        c.showText(encodable(font, raw));
        c.endText();
        return y - size * 1.5f;
    }

    private static String encodable(PDType1Font font, String raw) {
        StringBuilder safe = new StringBuilder();
        for (char ch : raw.toCharArray()) {
            try {
                font.encode(String.valueOf(ch));
                safe.append(ch);
            }
            catch (IOException | IllegalArgumentException ex) {
                safe.append('?');
            }
        }
        return safe.toString();
    }

    private static String euro(int cents) {
        return String.format(Locale.GERMANY, "%,.2f €", cents / 100.0);
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String date(Object value) {
        return value instanceof Timestamp ts ? DATE.format(ts.toInstant().atZone(ZoneId.of("Europe/Berlin"))) : "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean notBlank(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, label + " ist erforderlich");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, label + " ist zu lang");
        }
        return trimmed;
    }
}
