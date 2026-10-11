package de.buerokratiecopilot.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.invoices.InvoiceValidation.Flag;
import de.buerokratiecopilot.invoices.InvoiceValidation.Subject;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.invoices.domain.InvoiceLine;
import de.buerokratiecopilot.invoices.domain.Supplier;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * Incoming invoices: recording, checking and approving them (spec §14, D-003).
 *
 * <p>Two acceptances are implemented here.</p>
 *
 * <p><strong>A discrepancy between the line items and the total is marked.</strong> The invoice is not
 * corrected: the platform reports both numbers and lets a person decide, because the paper is what gets
 * paid.</p>
 *
 * <p><strong>A suspicious change of bank details blocks automatic posting.</strong> The supplier's last
 * known account is remembered, and an invoice naming a different one cannot be approved without a
 * person. That is the whole point of the field: a warning that can be clicked away is not a control.</p>
 *
 * <p>No tax rule is applied anywhere. The platform adds up what is written on the invoice and compares
 * it with what the invoice states.</p>
 */
@Service
public class InvoiceService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

    /** Rounding tolerance, configurable per tenant because it is an accounting decision. */
    private static final String TOLERANCE_SETTING = "invoices.amount_tolerance";
    private static final BigDecimal DEFAULT_TOLERANCE = new BigDecimal("0.01");

    private final InvoiceRepository invoices;
    private final InvoiceLineRepository lines;
    private final SupplierRepository suppliers;
    private final SettingsService settings;

    public InvoiceService(InvoiceRepository invoices, InvoiceLineRepository lines,
                          SupplierRepository suppliers, SettingsService settings) {
        this.invoices = invoices;
        this.lines = lines;
        this.suppliers = suppliers;
        this.settings = settings;
    }

    /** A line as it is submitted. */
    public record LineRequest(Integer lineNumber, String description, BigDecimal quantity, String unit,
                              BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxRatePercent,
                              BigDecimal taxAmount, BigDecimal grossAmount, String costCategory) {
    }

    /** An invoice as it is submitted. */
    public record InvoiceRequest(UUID tenantId, String invoiceNumber, UUID supplierId, String supplierName,
                                 String direction, String invoiceType, String currency,
                                 BigDecimal netAmount, BigDecimal taxAmount, BigDecimal grossAmount,
                                 String iban, String bic, String paymentReference, LocalDate issueDate,
                                 LocalDate deliveryDate, LocalDate dueDate, Integer paymentTermsDays,
                                 UUID documentId, UUID contractId, List<LineRequest> lines) {
    }

    /** What recording an invoice produced. */
    public record Recorded(Invoice invoice, List<InvoiceLine> lines, InvoiceValidation.Result validation) {

        public Map<String, Object> asDiagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("invoiceId", invoice.getId().toString());
            details.put("state", validation.state());
            details.put("blocksAutomaticPosting", validation.blocksAutomaticPosting());
            details.put("flags", validation.flags().stream().map(Flag::code).toList());
            return details;
        }
    }

    /**
     * Records an incoming invoice, validates it and stores the outcome.
     *
     * <p>Validation happens before the invoice is usable, not on demand: an invoice whose totals were
     * never checked is one somebody has already paid.</p>
     */
    @Transactional
    public Recorded record(InvoiceRequest request, UUID userId) {
        if (request.invoiceNumber() == null || request.invoiceNumber().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "An invoice needs a number");
        }

        Supplier supplier = resolveSupplier(request.tenantId(), request.supplierId(),
                request.supplierName(), userId);

        invoices.findByTenantIdAndSupplierIdAndInvoiceNumber(request.tenantId(),
                        supplier == null ? null : supplier.getId(), request.invoiceNumber().strip())
                .ifPresent(existing -> {
                    throw ApiException.duplicate("Diese Rechnung ist bereits erfasst (Nummer "
                            + existing.getInvoiceNumber() + ")");
                });

        Invoice invoice = new Invoice(UUID.randomUUID(), request.tenantId(),
                request.invoiceNumber().strip());
        invoice.setSupplierId(supplier == null ? null : supplier.getId());
        invoice.setDirection(request.direction() == null ? Invoice.Direction.INCOMING
                : Invoice.Direction.valueOf(request.direction()));
        invoice.setInvoiceType(request.invoiceType() == null ? Invoice.InvoiceType.INVOICE
                : Invoice.InvoiceType.valueOf(request.invoiceType()));
        if (request.currency() != null && !request.currency().isBlank()) {
            invoice.setCurrency(request.currency().toUpperCase(java.util.Locale.ROOT));
        }
        invoice.setNetAmount(request.netAmount());
        invoice.setTaxAmount(request.taxAmount());
        invoice.setGrossAmount(request.grossAmount());
        invoice.setIban(normalizeIban(request.iban()));
        invoice.setBic(request.bic());
        invoice.setPaymentReference(request.paymentReference());
        invoice.setIssueDate(request.issueDate());
        invoice.setDeliveryDate(request.deliveryDate());
        invoice.setDueDate(request.dueDate());
        invoice.setPaymentTermsDays(request.paymentTermsDays());
        invoice.setDocumentId(request.documentId());
        invoice.setContractId(request.contractId());
        invoice.setSource(request.documentId() == null ? Invoice.Source.MANUAL : Invoice.Source.DOCUMENT);
        invoice.setCreatedBy(userId);

        List<InvoiceLine> savedLines = new ArrayList<>();
        Invoice stored = invoices.save(invoice);
        if (request.lines() != null) {
            int number = 1;
            for (LineRequest line : request.lines()) {
                InvoiceLine entity = new InvoiceLine(UUID.randomUUID(), request.tenantId(), stored.getId(),
                        line.lineNumber() == null ? number : line.lineNumber(),
                        line.description() == null ? "" : line.description());
                entity.setQuantity(line.quantity());
                entity.setUnit(line.unit());
                entity.setUnitPrice(line.unitPrice());
                entity.setNetAmount(line.netAmount());
                entity.setTaxRatePercent(line.taxRatePercent());
                entity.setTaxAmount(line.taxAmount());
                entity.setGrossAmount(line.grossAmount());
                entity.setCostCategory(line.costCategory());
                savedLines.add(lines.save(entity));
                number++;
            }
        }

        InvoiceValidation.Result validation = validateAgainst(stored, savedLines, supplier);
        stored.setValidationState(validation.asState());
        stored.setValidationFlags(validation.flags().stream().map(Flag::asMap).toList());
        stored.setBankDetailsChanged(validation.flags().stream()
                .anyMatch(flag -> "IBAN_CHANGED".equals(flag.code())));
        if (stored.blocksAutomaticPosting()) {
            stored.setStatus(Invoice.Status.IN_REVIEW);
        }

        // The account is remembered only after it has been checked against the previous one, so the
        // next invoice is compared with a value a person has seen.
        if (supplier != null && stored.getIban() != null && !stored.isBankDetailsChanged()) {
            supplier.rememberIban(stored.getIban());
            suppliers.save(supplier);
        }

        Invoice finished = invoices.save(stored);
        log.info("Invoice {} recorded: state {}, bank details changed: {}", finished.getInvoiceNumber(),
                validation.state(), finished.isBankDetailsChanged());
        return new Recorded(finished, savedLines, validation);
    }

    /** Re-runs the validation, for the case where a person corrected the data. */
    @Transactional
    public InvoiceValidation.Result revalidate(UUID invoiceId, UUID tenantId) {
        Invoice invoice = requireInvoice(invoiceId, tenantId);
        Supplier supplier = invoice.getSupplierId() == null ? null
                : suppliers.findById(invoice.getSupplierId()).orElse(null);
        InvoiceValidation.Result validation = validateAgainst(invoice,
                lines.findByInvoiceIdOrderByLineNumberAsc(invoiceId), supplier);

        invoice.setValidationState(validation.asState());
        invoice.setValidationFlags(validation.flags().stream().map(Flag::asMap).toList());
        invoice.setBankDetailsChanged(validation.flags().stream()
                .anyMatch(flag -> "IBAN_CHANGED".equals(flag.code())));
        invoices.save(invoice);
        return validation;
    }

    /**
     * Approves an invoice, unless something blocks it.
     *
     * @param acceptChangedBankDetails true when a person has checked the new account and accepts it.
     *                                 Required, not assumed: the whole purpose of the flag is that
     *                                 somebody looked.
     */
    @Transactional
    public Invoice approve(UUID invoiceId, UUID tenantId, UUID userId, boolean acceptChangedBankDetails,
                           String note) {
        Invoice invoice = requireInvoice(invoiceId, tenantId);
        InvoiceValidation.Result validation = revalidate(invoiceId, tenantId);

        if (invoice.isBankDetailsChanged() && !acceptChangedBankDetails) {
            throw ApiException.conflict(
                    "Die Bankverbindung weicht von der bisher bekannten ab. Die Rechnung darf erst "
                            + "gebucht werden, wenn eine Person die neue Verbindung geprüft und "
                            + "bestätigt hat.");
        }
        if (validation.blocksAutomaticPosting() && !acceptChangedBankDetails) {
            throw ApiException.conflict("Die Rechnung ist blockiert: "
                    + validation.blocking().stream().map(Flag::message).toList());
        }

        if (invoice.isBankDetailsChanged() && acceptChangedBankDetails) {
            // A person confirmed the new account, so it becomes the known one. That is the only way
            // the alert can ever clear.
            suppliers.findById(invoice.getSupplierId()).ifPresent(supplier -> {
                supplier.rememberIban(invoice.getIban());
                suppliers.save(supplier);
            });
        }
        invoice.approve(userId);
        if (note != null && !note.isBlank()) {
            invoice.setNotes(note.strip());
        }
        return invoices.save(invoice);
    }

    @Transactional(readOnly = true)
    public Invoice requireInvoice(UUID invoiceId, UUID tenantId) {
        return invoices.findByIdAndTenantIdAndDeletedAtIsNull(invoiceId, tenantId)
                .orElseThrow(() -> ApiException.notFound("invoice"));
    }

    @Transactional(readOnly = true)
    public List<InvoiceLine> linesOf(UUID invoiceId) {
        return lines.findByInvoiceIdOrderByLineNumberAsc(invoiceId);
    }

    /** Invoices a person has to look at, because something blocked them. */
    @Transactional(readOnly = true)
    public List<Invoice> needingReview(UUID tenantId) {
        List<Invoice> result = new ArrayList<>();
        result.addAll(invoices.findByValidationState(tenantId, "BLOCKED"));
        result.addAll(invoices.findByValidationState(tenantId, "NEEDS_REVIEW"));
        return result;
    }

    private InvoiceValidation.Result validateAgainst(Invoice invoice, List<InvoiceLine> invoiceLines,
                                                     Supplier supplier) {
        List<Subject.Line> subjectLines = invoiceLines.stream()
                .map(line -> new Subject.Line(line.getLineNumber(), line.getNetAmount(),
                        line.getTaxAmount(), line.getGrossAmount(), line.getDescription()))
                .toList();

        Subject subject = new Subject(invoice.getInvoiceNumber(), invoice.getCurrency(),
                invoice.getNetAmount(), invoice.getTaxAmount(), invoice.getGrossAmount(), subjectLines,
                invoice.getIban(), supplier == null ? null : supplier.knownIban(),
                supplier != null && supplier.hasKnownIban(), invoice.getIssueDate(), invoice.getDueDate(),
                supplier == null ? null : supplier.getName());

        return InvoiceValidation.validate(subject,
                settings.getDouble(TOLERANCE_SETTING, DEFAULT_TOLERANCE.doubleValue()) == 0
                        ? DEFAULT_TOLERANCE
                        : BigDecimal.valueOf(settings.getDouble(TOLERANCE_SETTING,
                                DEFAULT_TOLERANCE.doubleValue())));
    }

    /**
     * Finds or creates the supplier.
     *
     * <p>A supplier is matched by name when no identifier is given, which is the realistic case for an
     * invoice that arrived as a PDF: the platform knows the name it read, and the name is what the
     * bank-details memory hangs off.</p>
     */
    private Supplier resolveSupplier(UUID tenantId, UUID supplierId, String supplierName, UUID userId) {
        if (supplierId != null) {
            return suppliers.findByIdAndTenantId(supplierId, tenantId).orElseThrow(
                    () -> ApiException.notFound("supplier"));
        }
        if (supplierName == null || supplierName.isBlank()) {
            return null;
        }
        Optional<Supplier> existing = suppliers.findByTenantIdAndName(tenantId, supplierName.strip());
        return existing.orElseGet(() -> suppliers.save(
                new Supplier(UUID.randomUUID(), tenantId, supplierName.strip())));
    }

    private static String normalizeIban(String iban) {
        return iban == null || iban.isBlank() ? null
                : iban.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
    }

    /** Remembers a supplier's account, for the case where somebody sets it up in advance. */
    @Transactional
    public Supplier rememberSupplierIban(UUID supplierId, UUID tenantId, String iban) {
        Supplier supplier = suppliers.findByIdAndTenantId(supplierId, tenantId)
                .orElseThrow(() -> ApiException.notFound("supplier"));
        supplier.rememberIban(iban);
        return suppliers.save(supplier);
    }
}
