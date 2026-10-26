package de.buerokratiecopilot.agents.finance;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.invoices.InvoiceRepository;
import de.buerokratiecopilot.invoices.InvoiceService;
import de.buerokratiecopilot.invoices.SupplierRepository;
import de.buerokratiecopilot.invoices.domain.Supplier;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * {@code invoice-capture} — Rechnungserfassung.
 *
 * <p>Reads the bookkeeping fields out of incoming invoice text (OCR) with deterministic rules and
 * records the invoice through {@link InvoiceService}. No external AI: the patterns are fixed and
 * explainable, exactly as declared in the manifest.</p>
 *
 * <p>Idempotency: before recording, an invoice with the same supplier and number is looked up; when it
 * exists the agent reports honestly instead of creating a duplicate. The module's own duplicate guard
 * is the second, independent stop.</p>
 */
@Component
public class InvoiceCaptureAgent implements Agent {

    private static final Pattern NUMBER_LABELED = Pattern.compile(
            "(?i)(?:rechnungs-?(?:nummer|nr\\.?)|rechnung)[ \\t]*[:#-]?[ \\t]+"
                    + "([A-Za-z0-9][A-Za-z0-9/._-]{2,40})");
    private static final Pattern NUMBER_BARE = Pattern.compile(
            "\\b[A-Z]{1,4}-\\d{2,4}-\\d{4}-\\d{1,8}\\b|\\b[A-Z]{1,4}-\\d{4}-\\d{2,8}\\b");
    private static final Pattern DATE_DE = Pattern.compile("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})\\b");
    private static final Pattern DATE_ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern AMOUNT = Pattern.compile(
            "(-?\\d{1,3}(?:\\.\\d{3})+,\\d{2}|-?\\d+(?:,\\d{2})|-?\\d{4,})");
    private static final Pattern IBAN = Pattern.compile("\\bDE\\d{2}(?: ?\\d{4}){4,7}\\b");

    private final InvoiceService invoiceService;
    private final InvoiceRepository invoices;
    private final SupplierRepository suppliers;

    public InvoiceCaptureAgent(InvoiceService invoiceService, InvoiceRepository invoices,
                               SupplierRepository suppliers) {
        this.invoiceService = invoiceService;
        this.invoices = invoices;
        this.suppliers = suppliers;
    }

    @Override
    public String id() {
        return "invoice-capture";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String rawText = FinanceSupport.firstNonBlank(context.input("rawText"), context.input("text"),
                context.input("content"));
        if (rawText == null) {
            return AgentResult.skipped("Rechnungserfassung: kein Text übergeben (Eingabe rawText fehlt) "
                    + "— es gibt nichts zu erfassen.");
        }

        Extraction extraction = extract(rawText, context);
        List<String> missing = extraction.missing();
        if (!missing.isEmpty()) {
            return new AgentResult(false,
                    "Rechnungserfassung unvollständig: es fehlen " + String.join(", ", missing)
                            + ". Erkannt: " + extraction.describe(),
                    List.of(), extraction.asMeta(missing));
        }

        boolean record = !"false".equalsIgnoreCase(context.input("record"));
        if (!record) {
            return AgentResult.done("Felder erkannt (keine Erfassung gewünscht): " + extraction.describe(),
                    List.of(new AgentResult.Produced("capture", "raw-text", extraction.describe())),
                    extraction.asMeta(List.of()));
        }

        UUID supplierId = FinanceSupport.parseUuid(context.input("supplierId"));
        Supplier supplier = supplierId != null
                ? suppliers.findByIdAndTenantId(supplierId, context.tenantId()).orElse(null)
                : (extraction.supplierName() == null ? null
                        : suppliers.findByTenantIdAndName(context.tenantId(), extraction.supplierName().strip())
                                .orElse(null));

        if (supplier != null) {
            var existing = invoices.findByTenantIdAndSupplierIdAndInvoiceNumber(context.tenantId(),
                    supplier.getId(), extraction.invoiceNumber().strip());
            if (existing.isPresent()) {
                Map<String, String> meta = extraction.asMeta(List.of());
                meta.put("existingInvoiceId", existing.get().getId().toString());
                return new AgentResult(false,
                        "Rechnung " + extraction.invoiceNumber() + " ist bereits erfasst ("
                                + existing.get().getId() + ") — keine Dublette angelegt.",
                        List.of(new AgentResult.Produced("invoice", existing.get().getId().toString(),
                                "bereits erfasst")),
                        meta);
            }
        }

        try {
            InvoiceService.InvoiceRequest request = new InvoiceService.InvoiceRequest(
                    context.tenantId(), extraction.invoiceNumber().strip(), supplierId,
                    extraction.supplierName(), "INCOMING", "INVOICE", "EUR",
                    extraction.netAmount(), extraction.taxAmount(), extraction.grossAmount(),
                    extraction.iban(), null, null,
                    extraction.issueDate(), extraction.deliveryDate(), extraction.dueDate(), null,
                    FinanceSupport.parseUuid(context.input("documentId")), null, List.of());
            InvoiceService.Recorded recorded = invoiceService.record(request, context.actorUserId());
            Map<String, String> meta = extraction.asMeta(List.of());
            meta.put("invoiceId", recorded.invoice().getId().toString());
            meta.put("validationState", String.valueOf(recorded.validation().state()));
            return AgentResult.done(
                    "Rechnung " + recorded.invoice().getInvoiceNumber() + " erfasst ("
                            + recorded.validation().state() + ").",
                    List.of(new AgentResult.Produced("invoice", recorded.invoice().getId().toString(),
                            "Rechnung " + recorded.invoice().getInvoiceNumber() + " erfasst")),
                    meta);
        }
        catch (ApiException ex) {
            if (ex.errorCode() == ErrorCode.DUPLICATE_RESOURCE) {
                return AgentResult.skipped("Rechnung bereits erfasst: " + ex.getMessage());
            }
            throw ex;
        }
    }

    /** What the rules read out of the text, with explicit inputs taking precedence. */
    private record Extraction(String invoiceNumber, LocalDate issueDate, LocalDate deliveryDate,
                              LocalDate dueDate, BigDecimal netAmount, BigDecimal taxAmount,
                              BigDecimal grossAmount, String supplierName, String iban) {

        List<String> missing() {
            List<String> missing = new ArrayList<>();
            if (invoiceNumber == null || invoiceNumber.isBlank()) {
                missing.add("Rechnungsnummer");
            }
            if (netAmount == null && grossAmount == null) {
                missing.add("Betrag (netto oder brutto)");
            }
            return missing;
        }

        Map<String, String> asMeta(List<String> missing) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("invoiceNumber", nz(invoiceNumber));
            meta.put("issueDate", nz(issueDate));
            meta.put("deliveryDate", nz(deliveryDate));
            meta.put("dueDate", nz(dueDate));
            meta.put("netAmount", nz(netAmount));
            meta.put("taxAmount", nz(taxAmount));
            meta.put("grossAmount", nz(grossAmount));
            meta.put("supplierName", nz(supplierName));
            meta.put("iban", nz(iban));
            meta.put("missingFields", String.join(",", missing));
            return meta;
        }

        String describe() {
            List<String> parts = new ArrayList<>();
            parts.add("Nr. " + nz(invoiceNumber));
            if (netAmount != null) {
                parts.add("netto " + FinanceSupport.money(netAmount));
            }
            if (taxAmount != null) {
                parts.add("USt " + FinanceSupport.money(taxAmount));
            }
            if (grossAmount != null) {
                parts.add("brutto " + FinanceSupport.money(grossAmount));
            }
            if (issueDate != null) {
                parts.add("vom " + issueDate);
            }
            if (dueDate != null) {
                parts.add("fällig " + dueDate);
            }
            if (supplierName != null) {
                parts.add(supplierName);
            }
            return String.join(", ", parts);
        }

        private static String nz(Object value) {
            return value == null ? "" : String.valueOf(value);
        }
    }

    private Extraction extract(String rawText, AgentContext context) {
        String text = rawText.replace('\u00a0', ' ');
        String invoiceNumber = FinanceSupport.firstNonBlank(context.input("invoiceNumber"),
                labeledNumber(text), bareNumber(text));
        LocalDate issueDate = firstOf(context.input("issueDate"),
                dateAfter(text, "rechnungsdatum"));
        LocalDate deliveryDate = firstOf(context.input("deliveryDate"),
                dateAfter(text, "leistungsdatum", "lieferdatum"));
        LocalDate dueDate = firstOf(context.input("dueDate"),
                dateAfter(text, "fällig", "faellig", "fälligkeitsdatum", "faelligkeitsdatum",
                        "zahlbar bis", "zahlungsziel"));
        BigDecimal net = firstOf(context.input("netAmount"),
                amountAfter(text, "netto", "nettobetrag"));
        BigDecimal tax = firstOf(context.input("taxAmount"),
                amountAfter(text, "umsatzsteuer", "mehrwertsteuer", "mwst", "mws"));
        BigDecimal gross = firstOf(context.input("grossAmount"),
                amountAfter(text, "brutto", "bruttobetrag", "gesamtbetrag", "gesamt", "summe",
                        "endbetrag", "zu zahlen", "rechnungsbetrag"));
        String supplierName = FinanceSupport.firstNonBlank(context.input("supplierName"),
                lineAfter(text, "lieferant", "absender"));
        String iban = FinanceSupport.firstNonBlank(context.input("iban"), findIban(text));
        return new Extraction(invoiceNumber, issueDate, deliveryDate, dueDate, net, tax, gross,
                supplierName, iban);
    }

    private static LocalDate firstOf(String input, LocalDate computed) {
        LocalDate parsed = FinanceSupport.parseDate(input);
        return parsed != null ? parsed : computed;
    }

    private static BigDecimal firstOf(String input, BigDecimal computed) {
        BigDecimal parsed = FinanceSupport.parseAmount(input);
        return parsed != null ? parsed : computed;
    }

    private static String labeledNumber(String text) {
        Matcher matcher = NUMBER_LABELED.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (candidate.equalsIgnoreCase("nr") || candidate.equalsIgnoreCase("nr.")) {
                continue; // the label ran into "Nr." — not a number
            }
            if (matcher.end() < text.length() && text.charAt(matcher.end()) == ',') {
                continue; // the label was followed by an amount, not a number
            }
            if (FinanceSupport.parseDate(candidate) != null) {
                continue; // the label was followed by a date
            }
            return candidate;
        }
        return null;
    }

    private static String bareNumber(String text) {
        Matcher matcher = NUMBER_BARE.matcher(text);
        return matcher.find() ? matcher.group().trim() : null;
    }

    private static String findIban(String text) {
        Matcher matcher = IBAN.matcher(text.toUpperCase(Locale.ROOT));
        return matcher.find() ? matcher.group().replace(" ", "") : null;
    }

    private static int indexOfAny(String text, String... labels) {
        String lower = text.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String label : labels) {
            int index = lower.indexOf(label.toLowerCase(Locale.ROOT));
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private static LocalDate dateAfter(String text, String... labels) {
        int index = indexOfAny(text, labels);
        if (index < 0) {
            return null;
        }
        String window = text.substring(index, Math.min(text.length(), index + 150));
        Matcher german = DATE_DE.matcher(window);
        while (german.find()) {
            LocalDate parsed = parseGermanDate(german);
            if (parsed != null) {
                return parsed;
            }
        }
        Matcher iso = DATE_ISO.matcher(window);
        while (iso.find()) {
            LocalDate parsed = FinanceSupport.parseDate(iso.group());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static LocalDate parseGermanDate(Matcher matcher) {
        try {
            int day = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int year = Integer.parseInt(matcher.group(3));
            if (year < 100) {
                year += year >= 70 ? 1900 : 2000;
            }
            return LocalDate.of(year, month, day);
        }
        catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal amountAfter(String text, String... labels) {
        int index = indexOfAny(text, labels);
        if (index < 0) {
            return null;
        }
        String window = text.substring(index, Math.min(text.length(), index + 220));
        Matcher matcher = AMOUNT.matcher(window);
        while (matcher.find()) {
            // A date fragment ("15.03.2026" → "2026") or an invoice number ("RE-2026-0412" → "2026")
            // must not be read as an amount.
            int start = matcher.start(1);
            if (start > 0) {
                char before = window.charAt(start - 1);
                if (before == '.' || before == '-' || Character.isLetterOrDigit(before)) {
                    continue;
                }
            }
            BigDecimal parsed = FinanceSupport.parseAmount(matcher.group(1));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static String lineAfter(String text, String... labels) {
        int index = indexOfAny(text, labels);
        if (index < 0) {
            return null;
        }
        int lineEnd = text.indexOf('\n', index);
        String rest = (lineEnd < 0 ? text : text.substring(0, lineEnd)).substring(index);
        String lower = rest.toLowerCase(Locale.ROOT);
        for (String label : labels) {
            int at = lower.indexOf(label.toLowerCase(Locale.ROOT));
            if (at >= 0) {
                rest = rest.substring(at + label.length());
                break;
            }
        }
        rest = rest.replaceFirst("^[\\s:#\\-–]+", "").trim();
        if (rest.isBlank() || rest.length() > 120) {
            return null;
        }
        if (DATE_DE.matcher(rest).matches() || rest.matches("[\\d.,€\\s]+")) {
            return null;
        }
        return rest;
    }
}
