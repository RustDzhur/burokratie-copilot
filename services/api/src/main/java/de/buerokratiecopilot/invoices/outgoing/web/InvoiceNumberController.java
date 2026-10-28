package de.buerokratiecopilot.invoices.outgoing.web;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.invoices.outgoing.InvoiceNumberSequence;
import de.buerokratiecopilot.invoices.outgoing.InvoiceNumberService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Invoice number sequences (spec §24).
 *
 * <p>{@code InvoiceNumberService} was implemented and tested with no endpoint, so a customer could not see
 * the number their next invoice would take.</p>
 *
 * <p><strong>Looking at the next number does not take it.</strong> That distinction is the reason the
 * service has both {@code peek} and {@code nextNumber}, and it is why they are separate routes with separate
 * methods: a number consumed by somebody opening a screen is a gap in the books, and a gap in an invoice
 * sequence is the kind of thing an auditor asks about. Reading is a {@code GET} and cannot consume anything;
 * taking a number is a {@code POST} and does.</p>
 *
 * <p><strong>The date comes from the server.</strong> The service takes the day it is numbering for, and
 * letting a caller supply it would let them choose which year's sequence they are writing into — a sequence
 * that resets annually would then reset when the client said so, which is not a property a customer should
 * be able to set by sending a different string.</p>
 *
 * <p><strong>The pattern is the customer's, not the platform's.</strong> A sequence carries a pattern that
 * the account defined; this code lays it out and does not impose a numbering scheme, because which numbers
 * a business is required to use is not a question this platform answers.</p>
 *
 * <p>The permissions are the catalogue's own: {@code finance.view} to read, {@code finance.create} to define
 * a sequence and to take a number.</p>
 */
@RestController
@RequestMapping("/api/v1/invoice-numbers")
@Tag(name = "Invoice numbers", description = "Number sequences for outgoing invoices")
public class InvoiceNumberController {

    private static final String SEE = "hasAnyAuthority('finance.view','finance.create','finance.edit')";
    private static final String CREATE = "hasAnyAuthority('finance.create','finance.edit')";

    private final InvoiceNumberService numbers;

    public InvoiceNumberController(InvoiceNumberService numbers) {
        this.numbers = numbers;
    }

    /** A sequence to define. */
    public record SequenceBody(String code, String name, String pattern, String resetPolicy) {
    }

    /** The sequences this account keeps. */
    @GetMapping
    @PreAuthorize(SEE)
    @Operation(summary = "The invoice number sequences of this account")
    public List<Map<String, Object>> list() {
        return numbers.list(tenant()).stream().map(InvoiceNumberController::asMap).toList();
    }

    /**
     * Defines a sequence.
     *
     * <p>The pattern belongs to the customer: which numbers a business must use is not a question this
     * platform answers.</p>
     */
    @PostMapping
    @PreAuthorize(CREATE)
    @Operation(summary = "Defines an invoice number sequence with the customer's own pattern")
    public ResponseEntity<Map<String, Object>> define(@RequestBody SequenceBody body) {
        UUID tenantId = tenant();
        if (body == null || body.code() == null || body.code().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Nummernfolge braucht einen Code");
        }
        InvoiceNumberSequence.ResetPolicy policy;
        try {
            policy = body.resetPolicy() == null
                    ? InvoiceNumberSequence.ResetPolicy.NEVER
                    : InvoiceNumberSequence.ResetPolicy.valueOf(
                            body.resetPolicy().toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Rückstellung: "
                    + body.resetPolicy() + ". Möglich sind "
                    + java.util.Arrays.toString(InvoiceNumberSequence.ResetPolicy.values()));
        }

        InvoiceNumberSequence sequence = numbers.createSequence(tenantId, body.code(), body.name(),
                body.pattern(), policy);
        return ResponseEntity.status(HttpStatus.CREATED).body(asMap(sequence));
    }

    /**
     * The number the next invoice would take, without taking it.
     *
     * <p>A {@code GET}, deliberately: looking at a screen must not consume a number, because a gap in an
     * invoice sequence is what an auditor asks about.</p>
     */
    @GetMapping("/{code}/next")
    @PreAuthorize(SEE)
    @Transactional
    @Operation(summary = "The number the next invoice would take; looking does not take it")
    public Map<String, Object> peek(@PathVariable String code) {
        UUID tenantId = tenant();
        String next = numbers.peek(tenantId, code, LocalDate.now());
        return Map.of("code", code, "nextNumber", next,
                "consumed", false,
                "note", "Diese Nummer wurde nur angezeigt und ist noch nicht vergeben.");
    }

    /**
     * Takes the next number.
     *
     * <p>A {@code POST}, because it consumes: this is the call that puts a number into the books, and the
     * day it numbers for comes from the server.</p>
     *
     * <p><strong>Transactional, and that is not decoration.</strong> The service declares
     * {@code propagation = MANDATORY}: it refuses to run outside a transaction, which is what stops a number
     * being consumed and the surrounding work rolling back — a gap in the books. Called from here, where the
     * HTTP layer opens nothing, that requirement was unmet and every attempt answered 500. The transaction
     * belongs at the boundary; the service's demand is the safeguard and is kept.</p>
     */
    @PostMapping("/{code}/next")
    @PreAuthorize(CREATE)
    @Transactional
    @Operation(summary = "Takes the next number; this call is what consumes it")
    public Map<String, Object> take(@PathVariable String code) {
        UUID tenantId = tenant();
        String taken = numbers.nextNumber(tenantId, code, LocalDate.now());
        return Map.of("code", code, "number", taken, "consumed", true,
                "note", "Diese Nummer ist vergeben und wird nicht erneut ausgegeben.");
    }

    /** One sequence, named as the specification names its parts. */
    private static Map<String, Object> asMap(InvoiceNumberSequence sequence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", sequence.getCode());
        row.put("name", sequence.getName());
        // The pattern is the customer's own; the platform lays it out and does not impose a scheme.
        row.put("pattern", sequence.getPattern());
        row.put("resetPolicy", sequence.getResetPolicy() == null
                ? null : sequence.getResetPolicy().name());
        row.put("nextValue", sequence.getNextValue());
        row.put("year", sequence.getYear());
        row.put("active", sequence.isActive());
        row.put("updatedAt", sequence.getUpdatedAt() == null ? null : sequence.getUpdatedAt().toString());
        return row;
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
