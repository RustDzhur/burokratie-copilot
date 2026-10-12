package de.buerokratiecopilot.identity.onboarding.web;

import java.util.LinkedHashMap;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.identity.onboarding.OnboardingService;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Onboarding: creating an account and the first administrator (spec §2, §3; A-001).
 *
 * <p>{@code OnboardingService} was implemented and tested with no endpoint, so an installation could only be
 * brought up through the database. This is the way in, and it is under {@code /api/v1/public} because
 * somebody who has no account yet cannot authenticate.</p>
 *
 * <p><strong>The bootstrap is refused once an administrator exists.</strong> {@code hasFinishedAdministrator}
 * is a property of the installation and not of an account, so without that guard this route would be an open
 * account-creation endpoint on every running system — reachable by anybody who can reach the host, with no
 * session and no invitation. The guard is the whole reason the route can be public, and it is tested by
 * bootstrapping twice.</p>
 *
 * <p><strong>The token is what authenticates the second half.</strong> Following the link from the bootstrap
 * is what lets a person set their password; there is no route that takes an address and sets one, because
 * that would be a way to take over an account that is waiting to be set up.</p>
 *
 * <p><strong>A slug is not a customer list.</strong> Looking an account up by its address is deliberately not
 * public: it would answer "does a company with this name use this software", which is a question about
 * somebody else's business, and the accounts that ask it are the ones that already have a session.</p>
 */
@RestController
@RequestMapping("/api/v1/public/onboarding")
@Tag(name = "Onboarding", description = "Creating an account and the first administrator")
public class OnboardingController {

    private final OnboardingService onboarding;

    public OnboardingController(OnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    /** An account to create. */
    public record OnboardBody(String slug, String accountName, String accountType, String adminEmail,
                              String adminDisplayName, String language, String timeZone,
                              String countryCode, String organizationName) {
    }

    /** The completion of a bootstrap. */
    public record CompleteBody(String token, String password, String displayName) {
    }

    /** Whether this installation still has to be set up, which a first-run screen asks. */
    @GetMapping("/status")
    @Operation(summary = "Whether this installation still has to be set up")
    public Map<String, Object> status() {
        boolean finished = onboarding.hasFinishedAdministrator();
        return Map.of("hasAdministrator", finished,
                "bootstrapAvailable", !finished,
                // Said plainly, because a first-run screen that offers a form which will be refused is worse
                // than one that says the installation is already set up.
                "note", finished ? "Diese Installation ist bereits eingerichtet."
                        : "Diese Installation ist noch nicht eingerichtet.");
    }

    /**
     * Creates an account.
     *
     * <p>Whoever calls this becomes the administrator of the account they created: there is no way to create
     * an account on behalf of somebody else, because that would be a way to become their administrator.</p>
     */
    @PostMapping
    @Operation(summary = "Creates an account; the caller becomes its administrator")
    public ResponseEntity<Map<String, Object>> onboard(@RequestBody OnboardBody body) {
        requireFields(body.slug(), body.accountName(), body.adminEmail());

        OnboardingService.Account account = onboarding.onboard(new OnboardingService.Request(
                body.slug(), body.accountName(), body.accountType(), body.adminEmail(),
                body.adminDisplayName(), body.language(), body.timeZone(), body.countryCode(),
                body.organizationName()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", account.tenantId().toString());
        result.put("workspaceId", account.workspaceId().toString());
        result.put("adminUserId", account.adminUserId().toString());
        result.put("accountType", account.accountType());
        // The address is verified before the account can be used, so the answer says where to go rather than
        // pretending the account is ready.
        result.put("note", "Bitte die E-Mail-Adresse bestätigen; danach ist das Konto nutzbar.");
        return ResponseEntity.status(account.alreadyExisted() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result);
    }

    /**
     * Starts the first administrator, once.
     *
     * <p>Refused as soon as an administrator exists. Without that check this route would let anybody create
     * an account on a running installation.</p>
     */
    @PostMapping("/bootstrap")
    @Operation(summary = "Starts the first administrator; refused once one exists")
    public ResponseEntity<Map<String, Object>> bootstrap(@RequestBody OnboardBody body) {
        requireFields(body.slug(), body.accountName(), body.adminEmail());

        // The guard that makes this route safe to be public, and the one thing it must never lose.
        if (onboarding.hasFinishedAdministrator()) {
            throw ApiException.conflict("Diese Installation ist bereits eingerichtet; ein zweiter "
                    + "Erstzugang ist nicht möglich");
        }

        OnboardingService.Bootstrap bootstrap = onboarding.bootstrapAdmin(new OnboardingService.Request(
                body.slug(), body.accountName(), body.accountType(), body.adminEmail(),
                body.adminDisplayName(), body.language(), body.timeZone(), body.countryCode(),
                body.organizationName()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", bootstrap.account().tenantId().toString());
        result.put("adminUserId", bootstrap.account().adminUserId().toString());
        // Shown once: whoever holds it can set this account's first password.
        result.put("token", bootstrap.token());
        result.put("expiresAt", bootstrap.expiresAt() == null ? null : bootstrap.expiresAt().toString());
        result.put("note", "Der Einrichtungslink wird nur einmal angezeigt.");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Completes a bootstrap with the token and a password.
     *
     * <p>The token is what authenticates this; there is no route that takes an address and sets a password,
     * because that would be a way to take over an account that is waiting to be set up.</p>
     */
    @PostMapping("/bootstrap/complete")
    @Operation(summary = "Sets the first password, authenticated by the bootstrap token")
    public Map<String, Object> complete(@RequestBody CompleteBody body) {
        if (body == null || body.token() == null || body.token().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Einrichtungs token fehlt");
        }
        if (body.password() == null || body.password().length() < 12) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Das Passwort muss mindestens 12 Zeichen haben");
        }
        java.util.UUID userId = onboarding.completeBootstrap(body.token(), body.password(),
                body.displayName());
        return Map.of("userId", userId.toString(),
                "note", "Das Konto ist eingerichtet; die Anmeldung ist jetzt möglich.");
    }

    /** Refuses a request that would create an account without anybody able to administer it. */
    private static void requireFields(String slug, String accountName, String adminEmail) {
        if (slug == null || slug.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Kontokürzel ist erforderlich");
        }
        if (accountName == null || accountName.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Kontoname ist erforderlich");
        }
        if (adminEmail == null || adminEmail.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Eine Adresse für die Administration ist erforderlich");
        }
    }

    /** Deliberately absent: looking an account up by slug is not public. See the class comment. */
    @GetMapping("/by-slug/{slug}")
    @Operation(summary = "Not available without a session", deprecated = true, hidden = true)
    public ResponseEntity<Map<String, Object>> bySlug(@PathVariable String slug) {
        throw ApiException.forbidden("Konten werden nicht über ihre Adresse nachgeschlagen; dafür ist eine "
                + "Anmeldung erforderlich");
    }
}
