package de.buerokratiecopilot.banking.web;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import de.buerokratiecopilot.banking.PaymentMatchingService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Bank accounts, statement import and payment matching (spec §25).
 *
 * <p>{@code PaymentMatchingService} was implemented and tested with no endpoint, so a bank statement could
 * be parsed by the code and never by a customer.</p>
 *
 * <p><strong>A statement import reports what it skipped, not only what it took.</strong> A file imported
 * twice is the ordinary case — a person downloads the month again — and the counts of duplicates and
 * automatic matches are in the answer so that a second import is visible rather than silent. A total that
 * only said how many transactions arrived would let an account be reconciled twice without anybody
 * noticing.</p>
 *
 * <p><strong>Confirming a match books a payment, so it is an accounting act and the person is named.</strong>
 * The confirming user comes from the session: a reconciliation that named itself would not be one, and the
 * whole purpose of the step is that somebody took responsibility for it.</p>
 *
 * <p><strong>A bank credential reference is a reference and is never returned.</strong> The service takes a
 * {@code credentialRef}, which points at a stored secret; an endpoint that echoed it back would put that
 * reference into a browser session and into every screenshot of the page, and returning the secret itself is
 * not something this codebase does anywhere.</p>
 *
 * <p>The permissions are the catalogue's own — {@code finance.create} for the account and the import,
 * {@code finance.reconcile} for confirming a match — checked before being written.</p>
 */
@RestController
@RequestMapping("/api/v1/banking")
@Tag(name = "Banking", description = "Bank accounts, statement import and payment matching")
public class BankingController {

    private static final String RECONCILE = "hasAnyAuthority('finance.reconcile','finance.edit')";
    private static final String CREATE = "hasAnyAuthority('finance.create','finance.edit')";

    private final PaymentMatchingService banking;

    public BankingController(PaymentMatchingService banking) {
        this.banking = banking;
    }

    /** A bank account to register. */
    public record AccountBody(String name, String iban, String bic, String bankName, String accountHolder,
                              String currency, String credentialRef) {
    }

    /** A confirmed match. */
    public record MatchBody(UUID transactionId, UUID invoiceId, BigDecimal amount, String matchType,
                            String reason) {
    }

    /** A decision on a proposed match. */
    public record ProposalBody(UUID transactionId, UUID invoiceId, Boolean accept, String note) {
    }

    /**
     * Registers a bank account.
     *
     * <p>The credential reference goes in and is not returned: it points at a stored secret, and echoing it
     * would put it into a browser session and into every screenshot of the page.</p>
     */
    @PostMapping("/accounts")
    @PreAuthorize(CREATE)
    @Operation(summary = "Registers a bank account; the credential reference is stored and never returned")
    public ResponseEntity<Map<String, Object>> registerAccount(@RequestBody AccountBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Bankkonto braucht einen Namen");
        }
        UUID accountId = banking.registerAccount(tenantId, body.name(), body.iban(), body.bic(),
                body.bankName(), body.accountHolder(), body.currency(), body.credentialRef());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "accountId", accountId.toString(),
                "name", body.name(),
                "note", "Der Verweis auf die Zugangsdaten wird gespeichert und nie zurückgegeben.",
                "registeredBy", actor.toString()));
    }

    /**
     * Imports a CAMT statement.
     *
     * <p>The answer carries how many transactions arrived, how many were skipped as duplicates and how many
     * were matched or proposed — because an import that only counted arrivals would let the same month be
     * reconciled twice without anybody noticing.</p>
     */
    @PostMapping(value = "/accounts/{accountId}/statements", consumes = "multipart/form-data")
    @PreAuthorize(CREATE)
    @Operation(summary = "Imports a CAMT statement and reports what it skipped as well as what it took")
    public ResponseEntity<Map<String, Object>> importStatement(@PathVariable UUID accountId,
                                                               @RequestParam("file") MultipartFile file) {
        UUID tenantId = tenant();
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurde keine Datei übergeben");
        }
        byte[] content;
        try {
            content = file.getBytes();
        }
        catch (java.io.IOException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Datei konnte nicht gelesen werden: " + ex.getMessage());
        }

        PaymentMatchingService.ImportResult result;
        try {
            result = banking.importCamt(tenantId, accountId, content, CurrentUser.required());
        }
        catch (java.io.IOException ex) {
            // A file that cannot be parsed is not a statement: the answer says so rather than reporting an
            // import of nothing, which would look like a statement with no transactions in it.
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Kontoauszug konnte nicht gelesen werden: " + ex.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>(result.asDiagnostics());
        body.put("accountId", accountId.toString());
        body.put("fileName", file.getOriginalFilename());
        // Said in words, because a number of skipped rows is the thing a person has to notice.
        body.put("note", result.duplicatesSkipped() > 0
                ? "Ein Teil der Umsätze war bereits vorhanden und wurde übersprungen."
                : "Alle Umsätze der Datei waren neu.");
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * Confirms a match between a transaction and an invoice.
     *
     * <p>This books a payment, which is why the person comes from the session: a reconciliation that named
     * itself would not be one.</p>
     */
    @PostMapping("/matches")
    @PreAuthorize(RECONCILE)
    @Operation(summary = "Confirms a match and books the payment; the person comes from the session")
    public ResponseEntity<Map<String, Object>> confirmMatch(@RequestBody MatchBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body == null || body.transactionId() == null || body.invoiceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Für eine Zuordnung werden Umsatz und Rechnung gebraucht");
        }
        if (body.amount() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Für eine Zuordnung wird ein Betrag gebraucht");
        }
        UUID paymentId = banking.confirmMatch(tenantId, body.transactionId(), body.invoiceId(),
                body.amount(), body.matchType(), body.reason(), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "paymentId", paymentId.toString(),
                "confirmedBy", actor.toString(),
                "note", "Die Zahlung ist gebucht und einer Person zugeordnet."));
    }

    /**
     * Accepts or rejects a proposed match.
     *
     * <p>A rejection is recorded rather than the proposal being deleted: that somebody looked and decided
     * against it is what stops the same proposal being offered again.</p>
     */
    @PostMapping("/matches/proposal")
    @PreAuthorize(RECONCILE)
    @Operation(summary = "Accepts or rejects a proposed match; a rejection is recorded, not discarded")
    public ResponseEntity<Void> decideProposal(@RequestBody ProposalBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body == null || body.transactionId() == null || body.invoiceId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Für eine Entscheidung werden Umsatz und Rechnung gebraucht");
        }
        banking.decideProposal(tenantId, body.transactionId(), body.invoiceId(),
                Boolean.TRUE.equals(body.accept()), actor, body.note());
        return ResponseEntity.noContent().build();
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
