package de.buerokratiecopilot.identity.web;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.identity.auth.AuthService;
import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Authentication API (spec §20: {@code /auth}).
 *
 * <p>Session tokens are set as cookies and are never returned in a response body, so a token
 * cannot leak through JavaScript or through a proxy log.</p>
 */
@RestController
// HTTP controllers only exist in a servlet application; the `worker` profile runs the same jar
// without a web context, where creating a controller would fail on its web-only dependencies.
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, sign-in, sessions and password reset")
public class AuthController {

    private final de.buerokratiecopilot.identity.mfa.MfaService mfa;
    private final de.buerokratiecopilot.identity.auth.SessionService sessions;
    private final AuthService authService;
    private final de.buerokratiecopilot.identity.auth.AccountSetupService accountSetup;
    private final BurokratieProperties properties;
    private final org.springframework.security.web.csrf.CsrfTokenRepository csrfTokenRepository;

    private final de.buerokratiecopilot.billing.SubscriptionService subscriptions;
    private final de.buerokratiecopilot.identity.profile.ProfileService profileService;

    public AuthController(AuthService authService, BurokratieProperties properties,
                          org.springframework.security.web.csrf.CsrfTokenRepository csrfTokenRepository,
                          de.buerokratiecopilot.identity.auth.AccountSetupService accountSetup,
                          de.buerokratiecopilot.identity.mfa.MfaService mfa,
                          de.buerokratiecopilot.identity.auth.SessionService sessions,
                          de.buerokratiecopilot.billing.SubscriptionService subscriptions,
                          de.buerokratiecopilot.identity.profile.ProfileService profileService) {
        this.profileService = profileService;
        this.subscriptions = subscriptions;
        this.mfa = mfa;
        this.sessions = sessions;
        this.authService = authService;
        this.accountSetup = accountSetup;
        this.properties = properties;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password,
            @Size(max = 200) String displayName,
            String accountType,
            @Size(max = 200) String tenantName,
            @Size(max = 10) String locale,
            @Size(max = 60) String timeZone,
            Boolean acceptedTerms) {
    }

    public record RegisterResponse(UUID userId, UUID tenantId, boolean verificationMailSent,
                                   String nextStep) {
    }

    public record VerifyEmailRequest(@NotBlank String token) {
    }

    public record ResendVerificationRequest(@NotBlank @Email String email) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record LoginResponse(UUID userId, UUID tenantId, String displayName, String locale,
                                String timeZone, List<String> roles, boolean mfaRequired) {
    }

    public record MeResponse(UUID userId, UUID tenantId, List<String> permissions, String email,
                             String displayName, String locale, String timeZone, String workspaceName,
                             String accountType, List<String> roles, boolean onboardingCompleted,
                             boolean mfaEnrolmentRequired, java.util.Map<String, Object> subscription,
                             Long avatarVersion) {
    }

    public record PasswordResetRequest(@NotBlank @Email String email) {
    }

    public record PasswordResetConfirmRequest(@NotBlank String token,
                                              @NotBlank @Size(min = 12, max = 128) String password) {
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account, its workspace and the owner membership")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request,
                                                     HttpServletRequest httpRequest) {
        AuthService.RegistrationResult result = authService.register(
                new AuthService.RegisterCommand(request.email(), request.password(), request.displayName(),
                        request.accountType(), request.tenantName(), request.locale(), request.timeZone(),
                        Boolean.TRUE.equals(request.acceptedTerms())),
                clientIp(httpRequest), httpRequest.getHeader(HttpHeaders.USER_AGENT));

        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(
                result.userId(), result.tenantId(), result.verificationMailSent(),
                result.verificationMailSent() ? "VERIFY_EMAIL" : "VERIFY_EMAIL_PENDING_MAIL_CONFIGURATION"));
    }

    @PostMapping("/verify-email")
    @Operation(summary = "Confirm an e-mail address using the token from the verification mail")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request,
                                            HttpServletRequest httpRequest) {
        authService.verifyEmail(request.token(), clientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify-email/resend")
    @Operation(summary = "Send the verification link again (the answer never reveals whether the address exists)")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in and receive a session cookie")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        AuthService.LoginResult result = authService.login(request.email(), request.password(),
                clientIp(httpRequest), httpRequest.getHeader(HttpHeaders.USER_AGENT));
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(result.sessionToken()).toString());
        return ResponseEntity.ok(new LoginResponse(result.userId(), result.tenantId(), result.displayName(),
                result.locale(), result.timeZone(), result.roles(), result.mfaRequired()));
    }

    public record MfaCodeRequest(@NotBlank String code) {
    }

    /**
     * The second step of sign-in. Works only for a session that was issued but has not yet proven its
     * second factor; the session filter lets exactly this route (and sign-out) through for it.
     */
    @PostMapping("/mfa/verify")
    @Operation(summary = "Proves the second factor of a freshly issued session")
    public ResponseEntity<Void> verifyMfa(@Valid @RequestBody MfaCodeRequest request, Authentication authentication,
                                          HttpServletRequest httpRequest) {
        if (authentication == null || !(authentication.getDetails()
                instanceof de.buerokratiecopilot.identity.security.SessionAuthenticationFilter.SessionDetails details)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UUID userId = UUID.fromString(authentication.getName());
        if (!mfa.verify(details.tenantId(), userId, request.code().strip()).valid()) {
            authService.registerFailedSecondFactor(userId, clientIp(httpRequest),
                    httpRequest.getHeader(HttpHeaders.USER_AGENT));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        sessions.markMfaVerified(details.sessionId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    @Operation(summary = "End the current session")
    public ResponseEntity<Void> logout(
            @CookieValue(name = "${burokratie.security.session.cookie-name:bc_session}", required = false)
            String sessionToken,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        if (sessionToken != null) {
            authService.logout(sessionToken, clientIp(httpRequest), httpRequest.getHeader(HttpHeaders.USER_AGENT));
        }
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, expiredSessionCookie().toString());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    @Operation(summary = "Describe the signed-in principal and its effective permissions")
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        List<String> permissions = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
        UUID userId = UUID.fromString(authentication.getName());
        UUID tenantId = de.buerokratiecopilot.shared.context.RequestContext.tenantId().orElse(null);
        AuthService.SessionProfile profile = authService.describeSession(userId, tenantId);
        return ResponseEntity.ok(new MeResponse(userId, tenantId, permissions, profile.email(),
                profile.displayName(), profile.locale(), profile.timeZone(), profile.workspaceName(),
                profile.accountType(), profile.roles(), accountSetup.isCompleted(userId),
                enrolmentRequired(authentication, userId, tenantId), subscriptionOf(tenantId),
                profileService.avatarVersion(userId)));
    }

    /** What the interface needs to show the trial banner or the "access ended" page; null without a workspace. */
    private java.util.Map<String, Object> subscriptionOf(UUID tenantId) {
        if (tenantId == null) {
            return null;
        }
        try {
            java.util.Map<String, Object> view = subscriptions.view(tenantId);
            java.util.Map<String, Object> small = new java.util.LinkedHashMap<>();
            for (String key : List.of("plan_code", "plan_name", "status", "access_until", "retention_until",
                    "days_left", "blocked", "data_choice", "retention_days")) {
                small.put(key, view.get(key));
            }
            return small;
        }
        catch (de.buerokratiecopilot.shared.error.ApiException ex) {
            return null;
        }
    }

    public record OnboardingRequest(String workspaceName, String workspaceType, List<String> sources,
                                    List<String> notifications) {
    }

    @PostMapping("/onboarding")
    @Operation(summary = "Finishes the first-run setup; only an account editor changes the account itself")
    public ResponseEntity<Void> completeOnboarding(@RequestBody OnboardingRequest request,
                                                   Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        boolean mayEdit = authentication.getAuthorities().stream()
                .anyMatch(granted -> "settings.edit".equals(granted.getAuthority()));
        accountSetup.complete(de.buerokratiecopilot.shared.context.RequestContext.tenantId().orElseThrow(),
                UUID.fromString(authentication.getName()), mayEdit,
                new de.buerokratiecopilot.identity.auth.AccountSetupService.Setup(request.workspaceName(),
                        request.workspaceType(), request.sources(), request.notifications()));
        return ResponseEntity.noContent().build();
    }

    /** True while the account requires a second factor and this session has none (and so may only enrol one). */
    private boolean enrolmentRequired(Authentication authentication, UUID userId, UUID tenantId) {
        return authentication.getDetails()
                instanceof de.buerokratiecopilot.identity.security.SessionAuthenticationFilter.SessionDetails details
                && !details.mfaVerified() && tenantId != null && !mfa.hasActiveFactor(userId)
                && Boolean.TRUE.equals(de.buerokratiecopilot.shared.context.RequestContext.runAs(tenantId,
                        () -> mfa.isMfaRequired(tenantId)));
    }

    @PostMapping("/password-reset")
    @Operation(summary = "Request a password reset link (the answer never reveals whether the address exists)")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request,
                                                     HttpServletRequest httpRequest) {
        authService.requestPasswordReset(request.email(), clientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/confirm")
    @Operation(summary = "Set a new password using the token from the reset mail")
    public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request,
                                                     HttpServletRequest httpRequest) {
        authService.resetPassword(request.token(), request.password(), clientIp(httpRequest),
                httpRequest.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/csrf")
    @Operation(summary = "Ensure the CSRF cookie is present for the single page application")
    public ResponseEntity<Void> csrf(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // Spring Security resolves the CSRF token lazily, so a plain 204 carries no cookie. The
        // single page application needs the XSRF-TOKEN cookie before it can send any unsafe
        // request, therefore the token is resolved (and, when absent, generated) here explicitly.
        // Unconditionally issue a token: publishing an existing one would depend on Spring
        // Security's internal lazy resolution and could silently answer without any cookie,
        // leaving the single page application unable to send a single unsafe request.
        var token = csrfTokenRepository.generateToken(httpRequest);
        csrfTokenRepository.saveToken(token, httpRequest, httpResponse);
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie sessionCookie(String token) {
        BurokratieProperties.Security.Session session = properties.getSecurity().getSession();
        return ResponseCookie.from(session.getCookieName(), token)
                .httpOnly(true)
                .secure(session.isCookieSecure())
                .sameSite(session.getCookieSameSite())
                .path("/")
                .maxAge(session.getAbsoluteTimeout())
                .build();
    }

    private ResponseCookie expiredSessionCookie() {
        BurokratieProperties.Security.Session session = properties.getSecurity().getSession();
        return ResponseCookie.from(session.getCookieName(), "")
                .httpOnly(true)
                .secure(session.isCookieSecure())
                .sameSite(session.getCookieSameSite())
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
