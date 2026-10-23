package de.buerokratiecopilot.documents.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.documents.DocumentBatchService;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Bulk actions of the Posteingang (Kanzlei edition, §5.1).
 *
 * <p>Deliberately a separate controller from the single-voucher endpoints: the contract of a batch is
 * different — it answers with a count and a list of refusals instead of one document, because a selection
 * of ten is nine decisions the office stands behind and one it must look at.</p>
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Belege")
class DocumentBatchController {

    /** One request of the queue: which vouchers, which action, and whose client for the assignment. */
    public record BatchRequest(List<UUID> ids, DocumentBatchService.Action action, UUID mandantId) {
    }

    /** What happened, and what did not — the reason is shown next to the voucher on the screen. */
    public record BatchResponse(int updated, List<DocumentBatchService.Skipped> skipped) {
    }

    private final DocumentBatchService batches;

    DocumentBatchController(DocumentBatchService batches) {
        this.batches = batches;
    }

    @PostMapping("/batch")
    @PreAuthorize("hasAuthority('documents.edit')")
    @Operation(summary = "Move, check, fail or assign several vouchers at once; "
            + "the ones the rules refuse are listed with their reason")
    BatchResponse batch(@RequestBody BatchRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Aufruf enthält keine Aktion.");
        }
        DocumentBatchService.Result result = batches.apply(request.ids(), request.action(), request.mandantId());
        return new BatchResponse(result.updated(), result.skipped());
    }
}
