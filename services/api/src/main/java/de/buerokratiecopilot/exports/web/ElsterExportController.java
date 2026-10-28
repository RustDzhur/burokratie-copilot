package de.buerokratiecopilot.exports.web;

import java.nio.charset.StandardCharsets;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.exports.ElsterCsvWriter;
import de.buerokratiecopilot.exports.ElsterExportService;
import de.buerokratiecopilot.settings.TaxSettingsService;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The ELSTER export (docs/ELSTER_MAPPING.md §5).
 *
 * <p>Reading the export is reading documents and finance, so it needs one of the two view permissions.
 * The year is optional: without it the tenant's Wirtschaftsjahr is used (a configured one, otherwise the
 * current calendar year). A year without data answers 200 with an empty list and zero totals — an empty
 * year is a fact, not a server error.</p>
 */
@RestController
@RequestMapping("/api/v1/exports")
@Tag(name = "Exports", description = "ELSTER/DATEV export of the categorised receipts of a year")
public class ElsterExportController {

    private static final String READ = "hasAnyAuthority('finance.view','documents.view')";

    private final ElsterExportService exports;
    private final TaxSettingsService taxSettings;

    public ElsterExportController(ElsterExportService exports, TaxSettingsService taxSettings) {
        this.exports = exports;
        this.taxSettings = taxSettings;
    }

    @GetMapping("/elster")
    @PreAuthorize(READ)
    @Operation(summary = "EÜR export of a year as JSON or as a semicolon-separated CSV")
    public ResponseEntity<?> elster(@RequestParam(required = false) Integer year,
                                    @RequestParam(defaultValue = "json") String format) {
        int target = year == null ? taxSettings.current().wirtschaftsjahr() : year;
        if (target < 1900 || target > 2200) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Das Jahr muss zwischen 1900 und 2200 liegen: " + target);
        }
        boolean csv = "csv".equalsIgnoreCase(format);
        if (!csv && !"json".equalsIgnoreCase(format)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Das Format muss csv oder json sein: " + format);
        }
        ElsterExportService.Export export = exports.export(target);
        if (!csv) {
            return ResponseEntity.ok(export);
        }
        byte[] body = ElsterCsvWriter.write(export).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"elster-euer-" + target + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}
