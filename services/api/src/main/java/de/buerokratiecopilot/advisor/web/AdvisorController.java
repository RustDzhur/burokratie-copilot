package de.buerokratiecopilot.advisor.web;

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
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.advisor.AdvisorService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Access for a tax advisor or accountant to a client's account (spec §3.2; H-002).
 *
 * <p>{@code AdvisorService} was implemented and tested with no endpoint, so the arrangement existed and
 * could only be created in the database.</p>
 *
 * <p><strong>The client consents; the advisor cannot grant themselves anything.</strong> An invitation is
 * one half of the arrangement and it does nothing until the client accepts it — and the accepting side comes
 * from the session, not from a body. That is the whole point of the two steps: a relationship in which one
 * party gives the other access to their documents is not one that party can create alone.</p>
 *
 * <p><strong>The advisor's own account comes from the session too.</strong> The invitation names the client,
 * never the inviter: an endpoint that let a caller name the advisor would let one advisor invite clients on
 * behalf of another, and the client would have no way to tell whose request they were accepting.</p>
 *
 * <p><strong>Ending it takes effect at once and keeps the record.</strong> A client who withdraws access
 * must not be readable a moment later; what the advisor did while the arrangement ran is not erased,
 * because a document that was seen is a document that was seen.</p>
 *
 * <p>The permissions are the catalogue's own — {@code staff.view}, {@code staff.invite},
 * {@code staff.remove} — checked before being written, after the lesson that a permission nobody holds makes
 * every endpoint answer 403 while a service test sees nothing wrong.</p>
 */
@RestController
@RequestMapping("/api/v1/advisor")
@Tag(name = "Advisor access", description = "Arrangements between an advisor and a client account")
public class AdvisorController {

    private static final String SEE = "hasAnyAuthority('staff.view','staff.invite','staff.manage_roles')";
    private static final String INVITE = "hasAnyAuthority('staff.invite','staff.manage_roles')";
    private static final String END = "hasAnyAuthority('staff.remove','staff.manage_roles')";

    private final AdvisorService advisors;

    public AdvisorController(AdvisorService advisors) {
        this.advisors = advisors;
    }

    /** An invitation, as a caller supplies it. */
    public record InviteBody(UUID clientTenantId, String accessLevel, String scope, String label) {
    }

    /** A reason, for ending one. */
    public record EndBody(String reason) {
    }

    /**
     * Invites a client account.
     *
     * <p>The advisor is the caller's own account, taken from the session. The invitation does nothing until
     * the client accepts it.</p>
     */
    @PostMapping("/arrangements")
    @PreAuthorize(INVITE)
    @Operation(summary = "Invites a client account; nothing is granted until the client accepts")
    public ResponseEntity<Map<String, Object>> invite(@RequestBody InviteBody body) {
        UUID advisorTenantId = tenant();
        if (body == null || body.clientTenantId() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es ist nicht angegeben, wer eingeladen wird");
        }
        AdvisorService.Arrangement arrangement = advisors.invite(advisorTenantId, body.clientTenantId(),
                body.accessLevel(), body.scope(), body.label(), CurrentUser.required());

        Map<String, Object> result = arrangement.asMap();
        result.put("note", "Die Einladung ist noch nicht wirksam; sie wird es, wenn der Mandant zustimmt.");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Accepts an invitation.
     *
     * <p>The accepting account is the session's own. There is no route that accepts on behalf of a client,
     * because an arrangement nobody agreed to is not an arrangement.</p>
     */
    @PostMapping("/arrangements/{arrangementId}/accept")
    @PreAuthorize(SEE)
    @Operation(summary = "Accepts an invitation as the client; the account comes from the session")
    public Map<String, Object> accept(@PathVariable UUID arrangementId) {
        UUID clientTenantId = tenant();
        AdvisorService.Arrangement arrangement = advisors.accept(clientTenantId, arrangementId,
                CurrentUser.required());
        Map<String, Object> result = arrangement.asMap();
        result.put("note", "Die Zustimmung ist vermerkt; der Zugang besteht, bis er beendet wird.");
        return result;
    }

    /**
     * Ends an arrangement.
     *
     * <p>Immediate: a client who withdraws access must not be readable a moment later.</p>
     */
    @PostMapping("/arrangements/{arrangementId}/end")
    @PreAuthorize(END)
    @Operation(summary = "Ends an arrangement; access stops at once and the record stays")
    public ResponseEntity<Void> end(@PathVariable UUID arrangementId,
                                    @RequestBody(required = false) EndBody body) {
        advisors.end(tenant(), arrangementId, CurrentUser.required(),
                body == null ? null : body.reason());
        return ResponseEntity.noContent().build();
    }

    /**
     * Whether an account may read another's documents under an arrangement.
     *
     * <p>Answered by the same code that refuses it, so a screen and an endpoint cannot disagree about what
     * somebody may see.</p>
     */
    @GetMapping("/access/{clientTenantId}")
    @PreAuthorize(SEE)
    @Operation(summary = "Whether the calling account may read this client's documents")
    public Map<String, Object> access(@PathVariable UUID clientTenantId) {
        UUID advisorTenantId = tenant();
        boolean allowed = advisors.mayAccess(advisorTenantId, clientTenantId);
        return Map.of("clientTenantId", clientTenantId.toString(), "mayAccess", allowed,
                // Said in words, because 'no' has two causes a person needs to tell apart: no arrangement,
                // or one that has not been accepted or has ended.
                "note", allowed ? "Der Zugang besteht."
                        : "Es besteht kein wirksamer Zugang: entweder gibt es keine Vereinbarung, oder sie "
                                + "wurde noch nicht bestätigt oder ist beendet.");
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
