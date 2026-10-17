package de.buerokratiecopilot.invoices.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.invoices.InvoiceService;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.invoices.domain.InvoiceLine;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Invoice API (spec §14, §20).
 *
 * <p>The validation result travels with every invoice response, and the flags are machine readable. A
 * caller that only renders the amounts would otherwise present a blocked invoice as an ordinary one,
 * and the blocking is the point of this module.</p>
 *
 * <p>The approval endpoint takes an explicit confirmation for a changed bank connection. It is a
 * parameter rather than an assumption because the whole purpose of the alert is that a person looked.</p>
 */
@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "Invoices", description = "Incoming invoices, their validation and their approval")
public class InvoiceController {

    private final InvoiceService invoices;
    private final de.buerokratiecopilot.invoices.outgoing.InvoicePdfRenderer renderer;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public InvoiceController(InvoiceService invoices,
                             de.buerokratiecopilot.invoices.outgoing.InvoicePdfRenderer renderer,
                             org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.renderer = renderer;
        this.jdbc = jdbc;
    }

    public record LineBody(Integer lineNumber, String description, BigDecimal quantity, String unit,
                           BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxRatePercent,
                           BigDecimal taxAmount, BigDecimal grossAmount, String costCategory) {
    }

    public record InvoiceBody(String invoiceNumber, String supplierId, String supplierName,
                              String direction, String invoiceType, String currency,
                              BigDecimal netAmount, BigDecimal taxAmount, BigDecimal grossAmount,
                              String iban, String bic, String paymentReference, LocalDate issueDate,
                              LocalDate deliveryDate, LocalDate dueDate, Integer paymentTermsDays,
                              String documentId, String contractId, List<LineBody> lines) {
    }

    public record FlagResponse(String code, String severity, String message, Map<String, Object> details) {

        @SuppressWarnings("unchecked")
        static FlagResponse from(Map<String, Object> flag) {
            return new FlagResponse(String.valueOf(flag.get("code")), String.valueOf(flag.get("severity")),
                    String.valueOf(flag.get("message")),
                    flag.get("details") instanceof Map<?, ?> details
                            ? (Map<String, Object>) details : Map.of());
        }
    }

    public record LineResponse(String id, int lineNumber, String description, BigDecimal quantity,
                               String unit, BigDecimal unitPrice, BigDecimal netAmount,
                               BigDecimal taxRatePercent, BigDecimal taxAmount, BigDecimal grossAmount,
                               String costCategory) {

        static LineResponse from(InvoiceLine line) {
            return new LineResponse(line.getId().toString(), line.getLineNumber(), line.getDescription(),
                    line.getQuantity(), line.getUnit(), line.getUnitPrice(), line.getNetAmount(),
                    line.getTaxRatePercent(), line.getTaxAmount(), line.getGrossAmount(),
                    line.getCostCategory());
        }
    }

    public record InvoiceResponse(String id, String invoiceNumber, String direction, String invoiceType,
                                  String status, String paymentStatus, String currency,
                                  BigDecimal netAmount, BigDecimal taxAmount, BigDecimal grossAmount,
                                  BigDecimal paidAmount, LocalDate issueDate, LocalDate dueDate,
                                  String iban, String bic, String supplierId, String documentId,
                                  boolean bankDetailsChanged, boolean blocksAutomaticPosting,
                                  String state, List<FlagResponse> flags, String approvedAt,
                                  List<LineResponse> lines, String supplierName) {

        static InvoiceResponse from(Invoice invoice, List<InvoiceLine> lines) {
            return from(invoice, lines, null);
        }

        static InvoiceResponse from(Invoice invoice, List<InvoiceLine> lines, String supplierName) {
            Object state = invoice.getValidationState().get("state");
            return new InvoiceResponse(invoice.getId().toString(), invoice.getInvoiceNumber(),
                    invoice.getDirection().name(), invoice.getInvoiceType().name(),
                    invoice.getStatus().name(), invoice.getPaymentStatus().name(), invoice.getCurrency(),
                    invoice.getNetAmount(), invoice.getTaxAmount(), invoice.getGrossAmount(),
                    invoice.getPaidAmount(), invoice.getIssueDate(), invoice.getDueDate(),
                    invoice.getIban(), invoice.getBic(),
                    invoice.getSupplierId() == null ? null : invoice.getSupplierId().toString(),
                    invoice.getDocumentId() == null ? null : invoice.getDocumentId().toString(),
                    invoice.isBankDetailsChanged(), invoice.blocksAutomaticPosting(),
                    state == null ? "NOT_VALIDATED" : String.valueOf(state),
                    invoice.getValidationFlags().stream().map(FlagResponse::from).toList(),
                    invoice.getApprovedAt() == null ? null : invoice.getApprovedAt().toString(),
                    lines == null ? List.of() : lines.stream().map(LineResponse::from).toList(), supplierName);
        }
    }

    public record ApproveBody(boolean acceptChangedBankDetails, String note) {
    }

    @PostMapping
    @PreAuthorize("hasAuthority('finance.create')")
    @Operation(summary = "Record an incoming invoice; it is validated before it can be approved")
    public ResponseEntity<InvoiceResponse> record(@RequestBody InvoiceBody body) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        InvoiceService.Recorded recorded = invoices.record(new InvoiceService.InvoiceRequest(tenantId,
                body.invoiceNumber(), uuid(body.supplierId()), body.supplierName(), body.direction(),
                body.invoiceType(), body.currency(), body.netAmount(), body.taxAmount(),
                body.grossAmount(), body.iban(), body.bic(), body.paymentReference(), body.issueDate(),
                body.deliveryDate(), body.dueDate(), body.paymentTermsDays(), uuid(body.documentId()),
                uuid(body.contractId()),
                body.lines() == null ? List.of() : body.lines().stream()
                        .map(line -> new InvoiceService.LineRequest(line.lineNumber(), line.description(),
                                line.quantity(), line.unit(), line.unitPrice(), line.netAmount(),
                                line.taxRatePercent(), line.taxAmount(), line.grossAmount(),
                                line.costCategory()))
                        .toList()),
                CurrentUser.required());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InvoiceResponse.from(recorded.invoice(), recorded.lines()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('finance.view')")
    @Operation(summary = "Invoices of the account, filterable by status and direction")
    public ResponseEntity<org.springframework.data.domain.Page<InvoiceResponse>> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            List<Invoice.Status> status,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            List<Invoice.Direction> direction,
            @org.springframework.data.web.PageableDefault(size = 25, sort = "createdAt",
                    direction = org.springframework.data.domain.Sort.Direction.DESC)
            org.springframework.data.domain.Pageable pageable) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        var page = invoices.list(tenantId, status, direction, pageable);
        var names = invoices.supplierNames(tenantId, page.getContent().stream()
                .map(Invoice::getSupplierId).filter(java.util.Objects::nonNull).distinct().toList());
        return ResponseEntity.ok(page.map(invoice -> InvoiceResponse.from(invoice, null,
                names.get(invoice.getSupplierId()))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('finance.view')")
    @Operation(summary = "An invoice with its lines and its validation result")
    public ResponseEntity<InvoiceResponse> detail(@PathVariable UUID id) {
        Invoice invoice = invoices.requireInvoice(id, RequestContext.tenantId().orElseThrow());
        return ResponseEntity.ok(InvoiceResponse.from(invoice, invoices.linesOf(id)));
    }

    /**
     * The invoice as a PDF.
     *
     * <p><strong>Why this endpoint exists.</strong> {@code InvoicePdfRenderer} was written, tested and called
     * by nothing. An invoice could be created, validated and approved, and there was no way to get it out of
     * the product as a document — which is the one thing a customer does with an invoice.</p>
     *
     * <p>The renderer prints what the record says and computes nothing; this route only feeds it, so a wrong
     * amount on the PDF is a wrong amount in the invoice, not a second place to look.</p>
     */
    @GetMapping("/{id}/pdf")
    @PreAuthorize("hasAuthority('finance.view')")
    @Operation(summary = "The invoice as a PDF")
    public org.springframework.http.ResponseEntity<byte[]> pdf(@PathVariable UUID id) throws java.io.IOException {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        var invoice = invoices.requireInvoice(id, tenantId);
        var lines = invoices.linesOf(id).stream()
                .map(line -> new de.buerokratiecopilot.invoices.outgoing.InvoicePdfRenderer.RenderLine(
                        line.getLineNumber(),
                        line.getDescription(), line.getQuantity(), line.getUnit(),
                        line.getUnitPrice(), line.getNetAmount(), line.getTaxRatePercent()))
                .toList();
        String seller = jdbc.query("select name from tenants where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, tenantId);
        String buyer = invoice.getContactId() == null ? null : jdbc.query("""
                select display_name from contacts where id = ? and tenant_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, invoice.getContactId(), tenantId);

        byte[] pdf = renderer.render(new de.buerokratiecopilot.invoices.outgoing.InvoicePdfRenderer.Document(
                invoice, lines, seller, buyer));
        String filename = (invoice.getInvoiceNumber() == null ? "Rechnung" : invoice.getInvoiceNumber())
                .replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** Re-runs the validation, for the case where a person corrected the data. */
    @PostMapping("/{id}/revalidate")
    @PreAuthorize("hasAuthority('finance.edit')")
    @Operation(summary = "Validate the invoice again after a correction")
    public ResponseEntity<InvoiceResponse> revalidate(@PathVariable UUID id) {
        invoices.revalidate(id, RequestContext.tenantId().orElseThrow());
        // The service stored the outcome, so the response is the invoice as it now stands.
        Invoice invoice = invoices.requireInvoice(id, RequestContext.tenantId().orElseThrow());
        return ResponseEntity.ok(InvoiceResponse.from(invoice, invoices.linesOf(id)));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('finance.approve')")
    @Operation(summary = "Approve an invoice; a changed bank connection needs an explicit confirmation")
    public ResponseEntity<InvoiceResponse> approve(@PathVariable UUID id,
                                                   @RequestBody(required = false) ApproveBody body) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        Invoice approved = invoices.approve(id, tenantId, CurrentUser.required(),
                body != null && body.acceptChangedBankDetails(), body == null ? null : body.note());
        return ResponseEntity.ok(InvoiceResponse.from(approved, invoices.linesOf(id)));
    }

    @GetMapping("/needing-review")
    @PreAuthorize("hasAuthority('finance.view')")
    @Operation(summary = "Invoices a person has to look at, because something blocked them")
    public ResponseEntity<List<InvoiceResponse>> needingReview() {
        return ResponseEntity.ok(invoices.needingReview(RequestContext.tenantId().orElseThrow()).stream()
                .map(invoice -> InvoiceResponse.from(invoice, null)).toList());
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
