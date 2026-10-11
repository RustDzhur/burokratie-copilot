package de.buerokratiecopilot.identity.mfa.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.identity.mfa.MfaService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The second factor (spec §33, A-014).
 *
 * <p>{@code MfaService} was implemented and tested with no endpoint, so nobody could switch on a second
 * factor. This is the way in.</p>
 *
 * <p><strong>Enrolment is not activation, and the endpoints say so.</strong> A new secret comes back with a
 * note that the factor is not yet active, and only a code generated from it confirms it. Otherwise a person
 * who scanned nothing — or scanned into the wrong application — would be locked out by a factor they cannot
 * produce, and an administrator would have to rescue them.</p>
 *
 * <p><strong>The secret and the recovery codes are shown once.</strong> The secret is what generates every
 * future code, and a recovery code that can be read again is a password on a screen. Both come back in the
 * answer that creates them and never in a list.</p>
 *
 * <p><strong>A person manages their own factor.</strong> Every route here acts on the caller: there is no
 * path that names somebody else, because an endpoint that could switch off another person's second factor is
 * the one an attacker would want. Only the tenant-wide requirement needs an administrative permission.</p>
 */
@RestController
@RequestMapping("/api/v1/mfa")
@Tag(name = "MFA", description = "The second factor of the calling user")
public class MfaController {

    private static final String AUTHENTICATED = "isAuthenticated()";

    private final MfaService mfa;

    public MfaController(MfaService mfa) {
        this.mfa = mfa;
    }

    /** What a caller sends to confirm or to disable. */
    public record CodeBody(String code) {
    }

    /** What a caller sends to require a second factor of the account. */
    public record RequirementBody(boolean required) {
    }

    /** Whether the caller has a working second factor, and how many recovery codes are left. */
    @GetMapping
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Whether the caller has an active second factor")
    public Map<String, Object> status() {
        UUID userId = CurrentUser.required();
        UUID tenantId = tenant();
        return Map.of("active", mfa.hasActiveFactor(userId),
                "required", mfa.isMfaRequired(tenantId),
                "remainingRecoveryCodes", mfa.remainingRecoveryCodes(userId),
                // An enrolment that was started and never confirmed does not count: the question is whether
                // somebody could actually produce a code.
                "note", mfa.hasActiveFactor(userId) ? "Der zweite Faktor ist aktiv."
                        : "Es ist kein bestätigter zweiter Faktor hinterlegt.");
    }

    /** The caller's factors, without any secret. */
    @GetMapping("/methods")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "The caller's factors; no secret is ever returned here")
    public List<Map<String, Object>> methods() {
        return mfa.list(CurrentUser.required());
    }

    /**
     * Starts an enrolment.
     *
     * <p>The secret comes back once, with the provisioning URI an authenticator application scans, and the
     * factor is not active until a code from it has been confirmed.</p>
     */
    @PostMapping("/enrol")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Starts an enrolment; the factor is not active until a code is confirmed")
    public ResponseEntity<Map<String, Object>> enrol(@RequestBody(required = false) Map<String, String> body) {
        UUID tenantId = tenant();
        UUID userId = CurrentUser.required();
        String label = body == null ? null : body.get("label");
        return ResponseEntity.status(HttpStatus.CREATED).body(mfa.enrol(tenantId, userId, label).asMap());
    }

    /**
     * Confirms an enrolment with a code from the application.
     *
     * <p>This is the moment the factor exists in the sense that matters, and it is also when the recovery
     * codes are issued — shown once, because they exist for the telephone that was lost.</p>
     */
    @PostMapping("/methods/{methodId}/confirm")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Confirms an enrolment and issues the recovery codes once")
    public Map<String, Object> confirm(@PathVariable UUID methodId, @RequestBody CodeBody body) {
        UUID tenantId = tenant();
        UUID userId = CurrentUser.required();
        if (body == null || body.code() == null || body.code().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Bestätigungscode ist erforderlich");
        }
        return mfa.confirm(tenantId, userId, methodId, body.code()).asMap();
    }

    /**
     * Checks a code.
     *
     * <p>Offered so that a sign-in screen can ask before creating a session, and it reports the answer
     * rather than throwing: a wrong code is the expected case, not an error.</p>
     */
    @PostMapping("/verify")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Checks a code; a wrong code is an answer, not an error")
    public Map<String, Object> verify(@RequestBody CodeBody body) {
        UUID tenantId = tenant();
        UUID userId = CurrentUser.required();
        if (body == null || body.code() == null || body.code().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Code ist erforderlich");
        }
        return mfa.verify(tenantId, userId, body.code()).asMap();
    }

    /**
     * Switches a factor off.
     *
     * <p>The recovery codes go with it: they are the part that would otherwise stay usable.</p>
     */
    @DeleteMapping("/methods/{methodId}")
    @PreAuthorize(AUTHENTICATED)
    @Operation(summary = "Switches off a factor of the caller and invalidates its recovery codes")
    public ResponseEntity<Void> disable(@PathVariable UUID methodId) {
        UUID tenantId = tenant();
        mfa.disable(tenantId, CurrentUser.required(), methodId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Requires a second factor for the whole account.
     *
     * <p>Configuration, because whether an organisation demands one is its decision and not the platform's.
     * The permission is the catalogue's own {@code settings.edit} — checked before being written, after the
     * lesson that a permission nobody holds makes every endpoint answer 403.</p>
     */
    @PostMapping("/requirement")
    @PreAuthorize("hasAuthority('settings.edit')")
    @Operation(summary = "Requires a second factor for this account")
    public Map<String, Object> requireMfa(@RequestBody RequirementBody body) {
        UUID tenantId = tenant();
        UUID actor = CurrentUser.required();
        mfa.requireMfa(tenantId, body.required(), actor);
        return Map.of("required", mfa.isMfaRequired(tenantId));
    }

    /** The tenant the caller is acting in, which is never taken from a body. */
    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow();
    }
}
