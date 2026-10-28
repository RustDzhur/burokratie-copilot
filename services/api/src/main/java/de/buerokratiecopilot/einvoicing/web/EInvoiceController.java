package de.buerokratiecopilot.einvoicing.web;

import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import de.buerokratiecopilot.einvoicing.domain.EInvoiceRecord;
import de.buerokratiecopilot.einvoicing.EInvoiceService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Electronic invoices: XRechnung and ZUGFeRD (spec §23).
 *
 * <p>{@code EInvoiceService} was implemented and tested with no endpoint, so an electronic invoice could be
 * read by the code and never by anything a customer uses.</p>
 *
 * <p><strong>Importing does not mean accepting, and the answer says which happened.</strong> The service
 * returns the record with the reason it was not accepted, and this endpoint passes both through: a file
 * that arrived, was parsed and failed validation is a file somebody has to look at, and an answer that said
 * only "imported" would send them away.</p>
 *
 * <p><strong>A record that needs attention is a separate list rather than a filter on the others.</strong>
 * The question "what do I have to deal with" is the one that gets asked, and answering it by making a caller
 * combine a status and a list is how things get missed.</p>
 *
 * <p><strong>The ruleset version is reported even when it is empty.</strong> It has been empty since the
 * table was created, because no ruleset has been loaded; a record validated against no ruleset is not a claim
 * of conformity, and the answer says so in words rather than leaving a caller to infer it from an empty
 * field. This is the honest shape: the platform does not invent a ruleset and does not pretend to have
 * checked against one.</p>
 *
 * <p>The permissions are the catalogue's own: there is no {@code e_invoice} permission, and an electronic
 * invoice is a financial document, so the routes use {@code finance.view} and {@code finance.create} —
 * checked before being written, after the lesson that a permission nobody holds makes every endpoint answer
 * 403 while a service test sees nothing wrong.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "E-invoicing", description = "XRechnung and ZUGFeRD records")
public class EInvoiceController {

    private static final String SEE = "hasAnyAuthority('finance.view','finance.create','finance.edit')";
    private static final String IMPORT = "hasAnyAuthority('finance.create','finance.edit')";

    private final EInvoiceService invoices;

    public EInvoiceController(EInvoiceService invoices) {
        this.invoices = invoices;
    }

    /** What has to be dealt with, which is the question that gets asked first. */
    @GetMapping("/e-invoices/needing-attention")
    @PreAuthorize(SEE)
    @Operation(summary = "Electronic invoices that have to be dealt with")
    public List<Map<String, Object>> needingAttention() {
        return invoices.needingAttention(tenant()).stream().map(EInvoiceController::asMap).toList();
    }

    /** The electronic invoices belonging to one document. */
    @GetMapping("/documents/{documentId}/e-invoices")
    @PreAuthorize(SEE)
    @Operation(summary = "The electronic invoices of a document")
    public List<Map<String, Object>> ofDocument(@PathVariable UUID documentId) {
        return invoices.recordsOfDocument(documentId, tenant()).stream()
                .map(EInvoiceController::asMap).toList();
    }

    /** One record, with what it was validated against. */
    @GetMapping("/e-invoices/{recordId}")
    @PreAuthorize(SEE)
    @Operation(summary = "One electronic invoice, with what it was validated against")
    public Map<String, Object> record(@PathVariable UUID recordId) {
        EInvoiceRecord record = invoices.requireRecord(recordId, tenant());
        Map<String, Object> body = asMap(record);
        // Said in words rather than left as an empty field: a record validated against no ruleset is not a
        // claim of conformity, and a caller should not have to infer that from a null.
        body.put("rulesetNote", record.getRulesetVersion() == null || record.getRulesetVersion().isBlank()
                ? "Kein Regelwerk geladen: die Prüfung fand gegen keine Fassung statt und ist keine "
                        + "Aussage über Konformität."
                : "Geprüft gegen Regelwerk " + record.getRulesetVersion() + ".");
        return body;
    }

    /**
     * Imports an electronic invoice into a document.
     *
     * <p>The answer carries whether it was accepted and, when it was not, why — so that a file which arrived
     * and failed validation is visible rather than counted as a success.</p>
     */
    @PostMapping(value = "/documents/{documentId}/e-invoices", consumes = "multipart/form-data")
    @PreAuthorize(IMPORT)
    @Operation(summary = "Imports an electronic invoice; the answer says whether it was accepted and why")
    public ResponseEntity<Map<String, Object>> importInvoice(@PathVariable UUID documentId,
                                                             @RequestParam("file") MultipartFile file) {
        UUID tenantId = tenant();
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurde keine Datei übergeben");
        }
        EInvoiceService.Imported imported;
        try {
            imported = invoices.importFile(tenantId, documentId, file.getBytes(),
                    file.getOriginalFilename(), CurrentUser.required());
        }
        catch (java.io.IOException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Datei konnte nicht gelesen werden: " + ex.getMessage());
        }

        // 'Accepted' must mean 'this is an electronic invoice we understood', not 'a row was written'.
        // The service stores a record even when parsing failed, so that the file is kept and somebody can
        // look at it - which is right - but reporting that as accepted would tell a caller their invoice had
        // been read when it had not been.
        boolean understood = imported.record() != null && imported.parsed() != null;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", understood);
        result.put("stored", imported.record() != null);
        result.put("record", imported.record() == null ? null : asMap(imported.record()));
        // The reason is passed through even on success, because 'accepted' and 'nothing to say' are not the
        // same statement.
        result.put("reason", imported.reason());
        // 201 only when it was understood; 202 when it was kept for a person to look at.
        return ResponseEntity.status(understood ? HttpStatus.CREATED : HttpStatus.ACCEPTED)
                .body(result);
    }

    /** The fields of a record, named as the specification names them. */
    private static Map<String, Object> asMap(EInvoiceRecord record) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recordId", record.getId() == null ? null : record.getId().toString());
        row.put("documentId", record.getDocumentId() == null ? null : record.getDocumentId().toString());
        row.put("invoiceId", record.getInvoiceId() == null ? null : record.getInvoiceId().toString());
        row.put("format", record.getFormat() == null ? null : record.getFormat().name());
        row.put("syntax", record.getSyntax());
        row.put("profile", record.getProfile());
        row.put("specificationVersion", record.getSpecificationVersion());
        row.put("validationStatus",
                record.getValidationStatus() == null ? null : record.getValidationStatus().name());
        row.put("rulesetVersion", record.getRulesetVersion());
        row.put("extractedAt", record.getExtractedAt() == null ? null : record.getExtractedAt().toString());
        row.put("validatedAt", record.getValidatedAt() == null ? null : record.getValidatedAt().toString());
        // Whether a person has to look at it, derived from the status the service set rather than guessed.
        row.put("needsAttention", record.getValidationStatus() != null
                && record.getValidationStatus().name() != "VALID");
        return row;
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
