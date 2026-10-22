package de.buerokratiecopilot.exports.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.exports.DatevExportService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * DATEV export packages (Kanzlei edition, §5.6).
 *
 * <p>The permission is {@code documents.export}, which the permission catalogue already grants to the people
 * who do this work (employee, manager, accountant). {@code documents.edit} would be too narrow for an
 * accountant and would not say what the action is.</p>
 *
 * <p><strong>The CSV format is provisional.</strong> The download serves what the service produced; the first
 * line of every file says that the profile is not agreed with a Kanzlei. See Appendix 1 of the specification
 * and {@code docs/business/datev-export-format.md}.</p>
 */
@RestController
@RequestMapping("/api/v1/exports")
@Tag(name = "Exporte", description = "DATEV export packages (provisional CSV format)")
public class DatevExportController {

    private final DatevExportService exports;

    public DatevExportController(DatevExportService exports) {
        this.exports = exports;
    }

    /** The request body of a new package. All dates are ISO days; the client filter is optional. */
    public record CreateDatevExport(LocalDate periodFrom, LocalDate periodTo, UUID mandantId) {
    }

    @PostMapping("/datev")
    @PreAuthorize("hasAuthority('documents.export')")
    @Operation(summary = "Create a DATEV package from the checked vouchers of a period")
    public ResponseEntity<DatevExportService.ExportResult> create(
            @RequestBody CreateDatevExport request) {
        UUID tenantId = RequestContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("This action requires a tenant"));
        var result = exports.create(tenantId, CurrentUser.required(), request.periodFrom(),
                request.periodTo(), request.mandantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/datev")
    @PreAuthorize("hasAuthority('documents.export')")
    @Operation(summary = "The DATEV packages of this account, newest first")
    public List<DatevExportService.BatchSummary> history() {
        return exports.history(RequestContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("This action requires a tenant")));
    }

    @GetMapping("/datev/{id}/download")
    @PreAuthorize("hasAuthority('documents.export')")
    @Operation(summary = "Download the CSV of one DATEV package")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var file = exports.download(RequestContext.tenantId()
                .orElseThrow(() -> new IllegalStateException("This action requires a tenant")), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.filename())
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(file.content());
    }
}
