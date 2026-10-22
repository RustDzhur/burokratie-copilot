package de.buerokratiecopilot.mandantrequests.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.mandantrequests.MandantRequestService;
import de.buerokratiecopilot.mandantrequests.MandantRequestService.CreateCommand;
import de.buerokratiecopilot.mandantrequests.MandantRequestService.Letter;
import de.buerokratiecopilot.mandantrequests.MandantRequestService.MandantRequestView;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The letters that ask a client for missing vouchers (Kanzlei edition, §5.7).
 *
 * <p>The card of a Beleg calls {@code /preview} to show the German text before anything is stored, then
 * {@code POST} and {@code /send}. The separate preview is deliberate: a dialog that invents its own text
 * would let a person send a letter the backend never produced, and the history would no longer match what
 * left the house.</p>
 *
 * <p>Reading is granted by {@code documents.view}, the permission of the voucher the request is about.
 * Writing is granted by {@code documents.edit}: asking a client for a voucher changes the office's work
 * on that voucher, and it uses the same right as correcting the voucher itself. The tenant always comes
 * from the session, never from the request body.</p>
 */
@RestController
@RequestMapping("/api/v1/mandant-requests")
@Tag(name = "Anfragen", description = "Requests to a client for missing vouchers")
public class MandantRequestController {

    private final MandantRequestService requests;

    public MandantRequestController(MandantRequestService requests) {
        this.requests = requests;
    }

    /** What the dialog sends: the client, the voucher and what is missing. */
    public record CreateRequestBody(UUID mandantId, UUID documentId, List<String> missing, String subject,
                                    String body, String note) {

        CreateCommand toCommand() {
            return new CreateCommand(mandantId, documentId, missing, subject, body, note);
        }
    }

    public record UpdateRequestBody(String subject, String body, String note) {
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "The German letter, filled in but not stored")
    public Letter preview(@RequestBody CreateRequestBody request) {
        return requests.preview(tenant(), request.toCommand());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('documents.edit')")
    @Operation(summary = "Creates a draft request")
    public ResponseEntity<MandantRequestView> create(@RequestBody CreateRequestBody request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(requests.create(tenant(), request.toCommand(), CurrentUser.required()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "The requests of the account, filtered by client, voucher or state")
    public List<MandantRequestView> list(@RequestParam(required = false) UUID mandantId,
                                         @RequestParam(required = false) UUID documentId,
                                         @RequestParam(required = false) String status) {
        return requests.list(tenant(), mandantId, documentId, status);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('documents.view')")
    @Operation(summary = "One request with the text that was stored or sent")
    public MandantRequestView get(@PathVariable UUID id) {
        return requests.get(tenant(), id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('documents.edit')")
    @Operation(summary = "Edits the text of a draft")
    public MandantRequestView update(@PathVariable UUID id, @RequestBody UpdateRequestBody request) {
        return requests.update(tenant(), id, request.subject(), request.body(), request.note());
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAuthority('documents.edit')")
    @Operation(summary = "Hands the letter to the mail mechanism; a draft stays a draft on failure")
    public MandantRequestView send(@PathVariable UUID id) {
        return requests.send(tenant(), id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('documents.edit')")
    @Operation(summary = "Withdraws an open request")
    public MandantRequestView cancel(@PathVariable UUID id) {
        return requests.cancel(tenant(), id);
    }

    @PostMapping("/{id}/mark-answered")
    @PreAuthorize("hasAuthority('documents.edit')")
    @Operation(summary = "Records that the client's answer arrived")
    public MandantRequestView markAnswered(@PathVariable UUID id) {
        return requests.markAnswered(tenant(), id);
    }

    private static UUID tenant() {
        return RequestContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("This action requires a tenant"));
    }
}
