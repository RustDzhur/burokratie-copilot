package de.buerokratiecopilot.templates.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.templates.GeneratedDocumentService;

/**
 * Generated documents that are waiting on something (spec §15; E-003).
 *
 * <p>{@code GeneratedDocumentService} was implemented and tested with no endpoint, so a blocked document
 * existed and nobody could find out why.</p>
 *
 * <p><strong>A blocked document is produced and not usable, and the routes keep that visible.</strong> A
 * letter generated from a template with a field nobody filled in is a letter with a gap in it; the platform
 * records it as blocked rather than handing it over, and these routes exist so that the gap can be found and
 * filled. A count alone would say that something is waiting without saying what.</p>
 *
 * <p><strong>Which fields are missing is the actionable part.</strong> "Three documents are blocked" sends
 * somebody looking; "this document is blocked because the case reference and the amount were never entered"
 * tells them what to do. That is why both routes exist and why the second one names the fields rather than
 * counting them.</p>
 *
 * <p><strong>Nothing here resolves anything.</strong> These are read routes: filling in a field changes a
 * document, and a read route that could do it would do it while somebody was looking at a list.</p>
 *
 * <p>The permissions are the catalogue's own: {@code templates.view} to read, which is the same permission
 * that lets somebody see the template the document came from.</p>
 */
@RestController
@RequestMapping("/api/v1/generated-documents")
@Tag(name = "Generated documents", description = "Documents waiting on a field nobody filled in")
public class GeneratedDocumentController {

    private static final String SEE = "hasAnyAuthority('templates.view','templates.create','templates.edit')";

    private final GeneratedDocumentService generated;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final de.buerokratiecopilot.shared.storage.BlobStore blobs;

    public GeneratedDocumentController(GeneratedDocumentService generated,
                                       org.springframework.jdbc.core.JdbcTemplate jdbc,
                                       de.buerokratiecopilot.shared.storage.BlobStore blobs) {
        this.generated = generated;
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    /**
     * How many generated documents are waiting.
     *
     * <p>A number, and the route below is what turns it into something somebody can act on.</p>
     */
    /**
     * The produced file of a generated document.
     *
     * <p><strong>Why this exists.</strong> A generated letter is rendered, stored as a blob and recorded with
     * its {@code rendered_blob_id} — and nothing ever read that column. The document existed and could not be
     * downloaded, which is the whole point of generating it. {@code GeneratedDocumentExportService} next to it
     * was likewise called by nothing; it produces a file at generation time, while this route serves the one
     * that was already produced.</p>
     */
    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyAuthority('documents.view','documents.review')")
    @Operation(summary = "The produced file of a generated document")
    public org.springframework.http.ResponseEntity<byte[]> download(@PathVariable UUID id) {
        UUID tenantId = de.buerokratiecopilot.shared.context.RequestContext.tenantId().orElseThrow();
        var row = jdbc.query("""
                select b.storage_key, b.content_type, g.title, g.output_format
                  from generated_documents g
                  join document_blobs b on b.id = g.rendered_blob_id
                 where g.id = ? and g.tenant_id = ? and b.deleted_at is null
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new Object[] {rs.getString("storage_key"), rs.getString("content_type"),
                    rs.getString("title"), rs.getString("output_format")};
        }, id, tenantId);
        if (row == null) {
            throw de.buerokratiecopilot.shared.error.ApiException.notFound(
                    "Zu diesem Dokument gibt es keine erzeugte Datei");
        }

        byte[] content;
        try (java.io.InputStream data = blobs.retrieve((String) row[0])) {
            content = data.readAllBytes();
        }
        catch (java.io.IOException ex) {
            // The record says a file exists and it cannot be read: that is a failure worth reporting, not an
            // empty download that looks like a document.
            throw de.buerokratiecopilot.shared.error.ApiException.notFound(
                    "Die erzeugte Datei konnte nicht gelesen werden: " + ex.getMessage());
        }

        String title = row[2] == null ? "Dokument" : (String) row[2];
        String extension = row[3] == null ? "" : "." + ((String) row[3]).toLowerCase();
        String filename = title.replaceAll("[^A-Za-z0-9._-]", "_") + extension;
        String mimeType = row[1] == null ? "application/octet-stream" : (String) row[1];
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(mimeType))
                .body(content);
    }

    @GetMapping("/blocked")
    @PreAuthorize(SEE)
    @Operation(summary = "How many generated documents are blocked")
    public Map<String, Object> blocked() {
        UUID tenantId = tenant();
        int blocked = generated.blockedCount(tenantId);
        return Map.of("blocked", blocked,
                // Said in words, because a blocked document is produced and not usable, and a person
                // reading a count has to know that it is not a count of letters that went out.
                "note", blocked == 0
                        ? "Es warten keine erzeugten Dokumente."
                        : "Diese Dokumente sind erzeugt, aber wegen fehlender Angaben nicht verwendbar.");
    }

    /**
     * Which fields a blocked document is missing.
     *
     * <p>The actionable answer: a person needs to know what to enter, not that something is waiting.</p>
     */
    @GetMapping("/{documentId}/unresolved-fields")
    @PreAuthorize(SEE)
    @Operation(summary = "Which fields a generated document is missing")
    public Map<String, Object> unresolvedFields(@PathVariable UUID documentId) {
        UUID tenantId = tenant();
        List<String> missing = generated.unresolvedFields(tenantId, documentId);
        return Map.of("documentId", documentId.toString(),
                "unresolvedFields", missing,
                "complete", missing.isEmpty(),
                "note", missing.isEmpty()
                        ? "Für dieses Dokument fehlt nichts."
                        : "Diese Angaben fehlen; erst danach ist das Dokument verwendbar.");
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
