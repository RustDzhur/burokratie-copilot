package de.buerokratiecopilot.settings.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.settings.KontoMappingService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The account mapping of the DATEV export, editable by the Kanzlei (Kanzlei edition, §5.6, §8.4).
 *
 * <p><strong>Why this screen exists.</strong> {@code konto_mapping} (migration V79) decides whether a voucher
 * can become a booking row at all: the export skips every voucher without a matching row with "Kein
 * Konto-Mapping". The table had a repository and no door — the export worked and produced an empty file. This
 * controller is that door: list, create, change, delete and the CSV import the Kanzlei already has.</p>
 *
 * <p><strong>Permissions follow the settings screens.</strong> Reading needs {@code settings.view} — an
 * accountant may look at the mapping without being allowed to change it — and every change needs
 * {@code settings.manage_rules}, the same right that guards the date vocabulary and the rule settings. The
 * permission catalogue grants it to the people who configure the cabinet; a narrower right would either lock
 * out the accountant or invent a permission that no role has.</p>
 *
 * <p><strong>Tenant scope.</strong> The account comes from the session, never from the body, and the row level
 * security policy of V79 is the second guard. An identifier of another account is answered as not found, so a
 * refusal does not confirm that the row exists.</p>
 */
@RestController
@RequestMapping("/api/v1/settings/konto-mapping")
@Tag(name = "Einstellungen", description = "Konten-Zuordnung für den DATEV-Export")
public class KontoMappingController {

    private static final String READ = "hasAuthority('settings.view')";
    private static final String WRITE = "hasAuthority('settings.manage_rules')";

    private final KontoMappingService mappings;

    public KontoMappingController(KontoMappingService mappings) {
        this.mappings = mappings;
    }

    /**
     * One row as a caller sends it.
     *
     * <p>The same body is used for create and patch: a patch replaces the whole row. A field that is absent or
     * {@code null} means "not set" — an optional dimension (client, supplier, category, tax key) is cleared,
     * and the two accounts are mandatory. The dialog sends every field, so a partial body has no meaning here;
     * pretending it had one would make "empty the supplier" impossible to express.</p>
     */
    public record MappingRequest(UUID mandantId, String supplier, String category, String sollkonto,
                                 String habenkonto, String steuerschluessel) {
    }

    private static UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }

    /**
     * The rows of this account.
     *
     * @param mandantId only rows of this client
     * @param supplier  part of the supplier name, case-insensitive
     * @param category  exact category, case-insensitive
     */
    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "The account mappings of this account, optionally filtered")
    public Map<String, Object> list(@RequestParam(required = false) UUID mandantId,
                                    @RequestParam(required = false) String supplier,
                                    @RequestParam(required = false) String category) {
        UUID tenantId = tenant();
        List<Map<String, Object>> items = mappings.list(tenantId, mandantId, supplier, category).stream()
                .map(KontoMappingService.Mapping::asMap).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        // The dialog offers these instead of repeating the taxonomy in the interface.
        body.put("categories", mappings.categories(tenantId));
        return body;
    }

    /** Adds a row. Both accounts are mandatory; the audit trail records it. */
    @PostMapping
    @PreAuthorize(WRITE)
    @Operation(summary = "Creates an account mapping")
    public ResponseEntity<Map<String, Object>> create(@RequestBody MappingRequest request) {
        UUID tenantId = tenant();
        var created = mappings.create(tenantId, CurrentUser.required(), change(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created.asMap());
    }

    /** Replaces the row. A foreign identifier is not found, not forbidden. */
    @PatchMapping("/{id}")
    @PreAuthorize(WRITE)
    @Operation(summary = "Replaces an account mapping")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody MappingRequest request) {
        UUID tenantId = tenant();
        return mappings.update(tenantId, CurrentUser.required(), id, change(request)).asMap();
    }

    /** Removes the row; the export stops using it immediately. */
    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @Operation(summary = "Deletes an account mapping")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        UUID tenantId = tenant();
        mappings.delete(tenantId, CurrentUser.required(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Imports the semicolon separated table the Kanzlei exchanges.
     *
     * <p>The answer is the report a person needs: how many rows were written, how many changed, and every line
     * that was left alone with a reason (an unknown client by name is a skip, not a 500). A wrong header is a
     * {@code 400}: that is not a row the caller may ignore, it is the wrong file.</p>
     */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize(WRITE)
    @Operation(summary = "Imports account mappings from a CSV file and reports what it skipped")
    public Map<String, Object> importCsv(@RequestParam("file") MultipartFile file) {
        UUID tenantId = tenant();
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurde keine Datei übergeben.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        }
        catch (IOException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Datei konnte nicht gelesen werden: " + ex.getMessage());
        }
        return mappings.importCsv(tenantId, CurrentUser.required(), content).asMap();
    }

    private static KontoMappingService.Change change(MappingRequest request) {
        if (request == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurden keine Angaben übermittelt.");
        }
        return new KontoMappingService.Change(request.mandantId(), request.supplier(), request.category(),
                request.sollkonto(), request.habenkonto(), request.steuerschluessel());
    }
}
