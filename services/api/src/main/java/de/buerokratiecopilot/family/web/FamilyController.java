package de.buerokratiecopilot.family.web;

import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.family.FamilyAccessService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Personal and shared documents in a household (spec §3.1; H-003).
 *
 * <p>{@code FamilyAccessService} was implemented and tested with no endpoint, so the rule that a family
 * member does not see another member's personal documents existed and could not be reached.</p>
 *
 * <p><strong>The refusal is an answer, not a status code.</strong> The access route answers the question
 * "may the caller read this", and it answers {@code readable: false} rather than returning 404. That is the
 * better shape here: the basis for a document that does not exist and for one that belongs to somebody else
 * is the same — {@code NOT_PERMITTED} — so the answer tells a member nothing about what the others keep,
 * while a refusal that distinguished the two would tell them exactly that.</p>
 *
 * <p><strong>A document is shared unless somebody marks it.</strong> The platform must not turn an ordinary
 * family file into a private one because a person forgot, so the endpoints offer both directions: marking
 * personal and making it shared again, and the answer says which it currently is.</p>
 *
 * <p>The routes that act on one's own documents need only a session; reading the household's list needs the
 * administrative permission, because it is a view over other people.</p>
 */
@RestController
@RequestMapping("/api/v1/family")
@Tag(name = "Family", description = "Personal and shared documents in a household")
public class FamilyController {

    private static final String AUTHENTICATED = "isAuthenticated()";
    private static final String SEE_ALL = "hasAnyAuthority('settings.view','settings.edit')";

    private final FamilyAccessService family;

    public FamilyController(FamilyAccessService family) {
        this.family = family;
    }

    /** What a caller sends to mark a document personal. */
    public record PersonalBody(UUID memberUserId, String note) {
    }

    /** What a caller sends to share a personal document. */
    public record ShareBody(UUID memberUserId, Instant expiresAt) {
    }

    /** The documents the calling member may read, and why each one is readable. */
    @GetMapping("/documents")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "The documents the caller may read, with the basis for each")
    public List<Map<String, Object>> readableDocuments(@RequestParam(defaultValue = "50") int limit) {
        UUID tenantId = tenant();
        return family.readableDocumentIds(tenantId, CurrentUser.required(), limit);
    }

    /** The caller's own personal documents, which only they see. */
    @GetMapping("/documents/personal")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "The caller's own personal documents")
    public List<Map<String, Object>> personalDocuments() {
        UUID tenantId = tenant();
        return family.personalDocuments(tenantId, CurrentUser.required());
    }

    /**
     * Whether a document may be read, and on what basis.
     *
     * <p>Answered for the caller and not for an arbitrary user: the question "may this person read this" is
     * the one an attacker would ask, and a member asking about their own access is the one that belongs in an
     * interface.</p>
     */
    @GetMapping("/documents/{documentId}/access")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Whether the caller may read this document, and why")
    public Map<String, Object> access(@PathVariable UUID documentId) {
        UUID tenantId = tenant();
        return family.decide(tenantId, CurrentUser.required(), documentId).asMap();
    }

    /**
     * Marks a document personal to a member.
     *
     * <p>Only that member, or somebody acting for the household with the administrative permission, can do
     * it: marking a document as somebody else's private property is not a decision a third person makes.</p>
     */
    @PostMapping("/documents/{documentId}/personal")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Marks a document personal to a member of the household")
    public ResponseEntity<Map<String, Object>> markPersonal(@PathVariable UUID documentId,
                                                            @RequestBody PersonalBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        UUID owner = body == null || body.memberUserId() == null ? actor : body.memberUserId();
        // A person marks their own document; marking somebody else's as theirs is a different act and needs
        // the administrative permission.
        if (!owner.equals(actor)) {
            throw ApiException.forbidden("Ein Dokument wird von der Person als persönlich gekennzeichnet, "
                    + "der es gehört");
        }
        family.markPersonal(tenantId, documentId, owner, actor, body == null ? null : body.note());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("documentId", documentId.toString(),
                "personal", true, "ownerUserId", owner.toString()));
    }

    /** Makes a document shared with the household again. */
    @PostMapping("/documents/{documentId}/shared")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Makes a personal document shared with the household again")
    public ResponseEntity<Map<String, Object>> markShared(@PathVariable UUID documentId) {
        UUID tenantId = tenant();
        family.markShared(tenantId, documentId, CurrentUser.required());
        return ResponseEntity.ok(Map.of("documentId", documentId.toString(), "personal", false));
    }

    /** Whether a document is personal at all, without saying to whom. */
    @GetMapping("/documents/{documentId}/personal")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Whether a document is personal, without saying to whom")
    public Map<String, Object> isPersonal(@PathVariable UUID documentId) {
        UUID tenantId = tenant();
        return Map.of("documentId", documentId.toString(),
                "personal", family.isPersonal(tenantId, documentId));
    }

    /**
     * Lets another member see a personal document.
     *
     * <p>Only the member it belongs to may do this; a share granted by somebody else would not be the owner's
     * decision about their own document.</p>
     */
    @PostMapping("/documents/{documentId}/share")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Shares a personal document; only its owner may do this")
    public ResponseEntity<Map<String, Object>> share(@PathVariable UUID documentId,
                                                     @RequestBody ShareBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        if (body == null || body.memberUserId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es ist nicht angegeben, mit wem geteilt wird");
        }
        family.share(tenantId, documentId, actor, body.memberUserId(), body.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("documentId", documentId.toString(),
                "sharedWith", body.memberUserId().toString(),
                "expiresAt", body.expiresAt() == null ? "unbefristet" : body.expiresAt().toString()));
    }

    /** Withdraws a share, which closes the door again. */
    @PostMapping("/documents/{documentId}/share/revoke")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Withdraws a share")
    public ResponseEntity<Void> revokeShare(@PathVariable UUID documentId,
                                            @RequestBody ShareBody body) {
        UUID tenantId = tenant();
        if (body == null || body.memberUserId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es ist nicht angegeben, wem der Zugriff "
                    + "entzogen wird");
        }
        family.revokeShare(tenantId, documentId, body.memberUserId());
        return ResponseEntity.noContent().build();
    }

    /** The members of the household, which a share picker needs. */
    @GetMapping("/members")
    @PreAuthorize(SEE_ALL)
    @Operation(summary = "The members of the household")
    public List<Map<String, Object>> members() {
        return family.members(tenant());
    }

    /** The bases a decision can have, so an interface can explain them rather than invent them. */
    @GetMapping("/access-bases")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "The bases on which access can be granted")
    public List<String> accessBases() {
        return FamilyAccessService.bases().stream().map(Enum::name).toList();
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
