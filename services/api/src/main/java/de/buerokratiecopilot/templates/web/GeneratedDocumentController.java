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

    public GeneratedDocumentController(GeneratedDocumentService generated) {
        this.generated = generated;
    }

    /**
     * How many generated documents are waiting.
     *
     * <p>A number, and the route below is what turns it into something somebody can act on.</p>
     */
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
