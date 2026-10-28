package de.buerokratiecopilot.invoices.outgoing.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.invoices.outgoing.PartialPaymentService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * What is still owed on an invoice, and payments that arrived outside the bank import (spec §24, §25).
 *
 * <p>{@code PartialPaymentService} was implemented and tested with no endpoint, so a part-paid invoice could
 * not be read and a payment that arrived as cash could not be recorded.</p>
 *
 * <p><strong>What is owed is computed, never supplied.</strong> {@code outstanding} is derived from the
 * invoice and the payments against it, so the answer cannot be made to say what a caller would prefer. That
 * is the whole reason the route is a read rather than a field somebody fills in: a figure about money that a
 * client could choose is not a figure about money.</p>
 *
 * <p><strong>Fully paid and nothing owed are said separately.</strong> The answer carries the status the
 * service assigned next to the amount, because "0,00 outstanding" and "paid" are not the same statement once
 * an overpayment is possible — a credit of ten euros also leaves nothing outstanding.</p>
 *
 * <p><strong>Recording a payment books it, so the person comes from the session.</strong> A payment entered
 * without anybody behind it is a row that cannot be explained to an auditor, and the service already refuses
 * a non-positive amount, which is left to it rather than repeated here.</p>
 */
@RestController
@RequestMapping("/api/v1/invoices")
@Tag(name = "Invoice payments", description = "What is owed, and payments recorded by hand")
public class PartialPaymentController {

    private static final String SEE = "hasAnyAuthority('finance.view','finance.create','finance.edit')";
    private static final String BOOK = "hasAnyAuthority('finance.create','finance.edit')";

    private final PartialPaymentService payments;

    public PartialPaymentController(PartialPaymentService payments) {
        this.payments = payments;
    }

    /** A payment that arrived outside the bank import. */
    public record PaymentBody(BigDecimal amount, String currency, LocalDate paymentDate, String reference) {
    }

    /**
     * What is still owed on an invoice.
     *
     * <p>Computed from the invoice and its payments rather than supplied, so the answer cannot be made to say
     * what a caller would prefer.</p>
     */
    @GetMapping("/{invoiceId}/outstanding")
    @PreAuthorize(SEE)
    @Operation(summary = "What is still owed on an invoice, computed rather than supplied")
    public Map<String, Object> outstanding(@PathVariable UUID invoiceId) {
        UUID tenantId = tenant();
        BigDecimal outstanding = payments.outstanding(tenantId, invoiceId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("invoiceId", invoiceId.toString());
        result.put("outstanding", outstanding == null ? null : outstanding.toPlainString());
        // The amount and the state are different statements once an overpayment is possible: a credit also
        // leaves nothing outstanding.
        result.put("settled", outstanding != null && outstanding.signum() == 0);
        result.put("note", outstanding != null && outstanding.signum() == 0
                ? "Es steht nichts mehr offen. Ob die Rechnung vollständig bezahlt oder überzahlt ist, "
                        + "ergibt sich aus den Zahlungen."
                : "Es steht noch ein Betrag offen.");
        return result;
    }

    /**
     * Records a payment that arrived outside the bank import.
     *
     * <p>The amount is the caller's; whether it is acceptable is the service's, and its refusal is passed
     * through rather than repeated here — a second copy of a rule is a second place for it to differ.</p>
     */
    @PostMapping("/{invoiceId}/payments")
    @PreAuthorize(BOOK)
    @Operation(summary = "Records a payment by hand; the person comes from the session")
    public ResponseEntity<Map<String, Object>> record(@PathVariable UUID invoiceId,
                                                      @RequestBody PaymentBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body == null || body.amount() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Zahlung braucht einen Betrag");
        }

        UUID paymentId = payments.recordExternalPayment(tenantId, invoiceId, body.amount(),
                body.currency(), body.paymentDate(), body.reference(), actor);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentId", paymentId.toString());
        result.put("recordedBy", actor.toString());
        // What is owed afterwards, so a caller does not have to ask a second question to learn the effect of
        // what they just did.
        BigDecimal outstanding = payments.outstanding(tenantId, invoiceId);
        result.put("outstandingAfter", outstanding == null ? null : outstanding.toPlainString());
        result.put("note", "Die Zahlung ist gebucht und einer Person zugeordnet.");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
