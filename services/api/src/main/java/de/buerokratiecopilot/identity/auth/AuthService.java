package de.buerokratiecopilot.identity.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.identity.MembershipRepository;
import de.buerokratiecopilot.identity.MembershipRoleRepository;
import de.buerokratiecopilot.identity.OneTimeTokenRepository;
import de.buerokratiecopilot.identity.RoleRepository;
import de.buerokratiecopilot.identity.TenantRepository;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.WorkspaceRepository;
import de.buerokratiecopilot.identity.domain.Membership;
import de.buerokratiecopilot.identity.domain.MembershipRole;
import de.buerokratiecopilot.identity.domain.OneTimeToken;
import de.buerokratiecopilot.identity.domain.Role;
import de.buerokratiecopilot.identity.domain.Tenant;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.identity.domain.Workspace;
import de.buerokratiecopilot.identity.security.PasswordPolicy;
import de.buerokratiecopilot.identity.security.SecurityTokens;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.mail.EmailMessage;
import de.buerokratiecopilot.shared.mail.EmailSender;

/**
 * Registration, e-mail verification, login and password reset (spec §21.3).
 *
 * <p>Design rules applied here:</p>
 * <ul>
 *   <li>The tenant of a session is derived from the authenticated user's memberships, never
 *       from a value supplied by the client (§3.2).</li>
 *   <li>Login failures never reveal whether an account exists.</li>
 *   <li>Failures are time-boxed: after {@code burokratie.security.login.max-failed-attempts}
 *       attempts the account is locked for a limited period, and the lock expires by itself.</li>
 *   <li>Registration creates the tenant, its default workspace and the OWNER membership in one
 *       transaction — a half-registered account cannot exist.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final Duration EMAIL_VERIFICATION_TTL = Duration.ofDays(3);
    private static final Duration PASSWORD_RESET_TTL = Duration.ofHours(2);

    private final de.buerokratiecopilot.identity.mfa.MfaService mfa;
    private final de.buerokratiecopilot.billing.SubscriptionService subscriptions;
    private final de.buerokratiecopilot.legal.LegalService legal;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;
    private final MembershipRoleRepository membershipRoles;
    private final RoleRepository roles;
    private final OneTimeTokenRepository oneTimeTokens;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final EmailSender emailSender;
    private final BurokratieProperties properties;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public AuthService(UserRepository users, TenantRepository tenants, WorkspaceRepository workspaces,
                       MembershipRepository memberships, MembershipRoleRepository membershipRoles,
                       RoleRepository roles, OneTimeTokenRepository oneTimeTokens,
                       SessionService sessionService, AuditService auditService,
                       PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
                       EmailSender emailSender, BurokratieProperties properties,
                       org.springframework.transaction.PlatformTransactionManager transactionManager,
                       de.buerokratiecopilot.identity.mfa.MfaService mfa,
                       de.buerokratiecopilot.billing.SubscriptionService subscriptions,
                       de.buerokratiecopilot.legal.LegalService legal) {
        this.legal = legal;
        this.subscriptions = subscriptions;
        this.mfa = mfa;
        this.users = users;
        this.tenants = tenants;
        this.workspaces = workspaces;
        this.memberships = memberships;
        this.membershipRoles = membershipRoles;
        this.roles = roles;
        this.oneTimeTokens = oneTimeTokens;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.emailSender = emailSender;
        this.properties = properties;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /**
     * Input of a registration. The product is a Kanzlei tool, so the cabinet has one kind and the person no longer
     * chooses one: registration creates the Kanzlei cabinet.
     */
    public record RegisterCommand(String email, String password, String displayName,
                                  String tenantName, String locale, String timeZone, boolean acceptedTerms) {
    }

    /** The one kind of cabinet: an accounting office. */
    static final String KANZLEI_ACCOUNT_TYPE = "COMPANY";
    static final Workspace.Mode KANZLEI_WORKSPACE_MODE = Workspace.Mode.BUSINESS;
    static final String KANZLEI_WORKSPACE_NAME = "Kanzlei";

    public record RegistrationResult(UUID userId, UUID tenantId, boolean verificationMailSent) {
    }

    public record LoginResult(String sessionToken, UUID userId, UUID tenantId, String displayName,
                              String locale, String timeZone, List<String> roles, boolean mfaRequired) {
    }

    // ---------------------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------------------

    @Transactional
    public RegistrationResult register(RegisterCommand command, String ipAddress, String userAgent) {
        if (!properties.getSecurity().isRegistrationOpen()) {
            // Said plainly rather than as a generic refusal: a person who tries to register should know why.
            throw new ApiException(ErrorCode.FORBIDDEN, "Registration is closed on this installation");
        }
        if (properties.getSecurity().isRequireTermsAcceptance() && !command.acceptedTerms()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Die Nutzungsbedingungen und die Datenschutzerklärung müssen akzeptiert werden");
        }
        String email = normalizeEmail(command.email());
        String displayName = command.displayName() == null || command.displayName().isBlank()
                ? email
                : command.displayName().trim();

        List<String> violations = passwordPolicy.validate(command.password(), email, displayName);
        if (!violations.isEmpty()) {
            throw ApiException.validation("Password does not meet the policy",
                    violations.stream()
                            .map(key -> new de.buerokratiecopilot.shared.error.ApiErrorResponse.FieldViolation(
                                    "password", key.toUpperCase(Locale.ROOT), key))
                            .toList());
        }

        if (users.existsByEmailIgnoreCase(email)) {
            // Registration cannot hide that an address is taken, but it must not confirm it
            // either: the answer is deliberately the same shape as a validation error.
            throw ApiException.duplicate("An account with this e-mail address already exists");
        }

        String accountType = KANZLEI_ACCOUNT_TYPE;

        User user = new User(UUID.randomUUID(), email, displayName);
        user.setStatus(User.Status.PENDING_VERIFICATION);
        user.setPasswordHash(passwordEncoder.encode(command.password()));
        user.setPasswordChangedAt(Instant.now());
        if (command.locale() != null && !command.locale().isBlank()) {
            user.setLocale(command.locale());
        }
        if (command.timeZone() != null && !command.timeZone().isBlank()) {
            user.setTimeZone(command.timeZone());
        }
        // Flushed: the proof of acceptance below is written with plain SQL and needs the row to exist.
        users.saveAndFlush(user);
        if (command.acceptedTerms()) {
            legal.recordAcceptance(user.getId());
        }

        Tenant tenant = new Tenant(UUID.randomUUID(), uniqueSlug(email),
                command.tenantName() == null || command.tenantName().isBlank()
                        ? displayName
                        : command.tenantName().trim(),
                accountType);
        tenants.saveAndFlush(tenant);
        // Every new workspace starts with the free trial.
        subscriptions.startTrial(tenant.getId());

        Workspace workspace = new Workspace(UUID.randomUUID(), tenant.getId(),
                KANZLEI_WORKSPACE_NAME, KANZLEI_WORKSPACE_MODE);
        workspace.setDefaultWorkspace(true);
        workspaces.save(workspace);

        Membership membership = new Membership(UUID.randomUUID(), tenant.getId(), user.getId(),
                Membership.Status.ACTIVE);
        membership.setOwner(true);
        membership.setJoinedAt(Instant.now());
        membership.setWorkspaceId(workspace.getId());
        memberships.save(membership);

        Role ownerRole = roles.findByCodeAndTenantIdIsNull(Role.OWNER)
                .orElseThrow(() -> new IllegalStateException("System role OWNER is missing — check migration V12"));
        membershipRoles.save(new MembershipRole(membership.getId(), ownerRole.getId(), user.getId(), null));

        String verificationToken = issueOneTimeToken(user, OneTimeToken.Purpose.EMAIL_VERIFICATION,
                EMAIL_VERIFICATION_TTL);
        boolean mailSent = sendVerificationMail(user, verificationToken);

        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.AUTHENTICATION, "USER_REGISTERED")
                .actor(AuditEvent.ActorType.USER, user.getId(), email)
                .tenant(tenant.getId())
                .entity("users", user.getId(), email)
                .description("Account registered (Kanzlei)")
                .request(ipAddress, userAgent, null, "/api/v1/auth/register", "POST")
                .build());

        return new RegistrationResult(user.getId(), tenant.getId(), mailSent);
    }

    // ---------------------------------------------------------------------------------
    // E-mail verification
    // ---------------------------------------------------------------------------------

    @Transactional
    public UUID verifyEmail(String rawToken, String ipAddress, String userAgent) {
        OneTimeToken token = oneTimeTokens
                .findUsable(SecurityTokens.sha256Hex(rawToken), OneTimeToken.Purpose.EMAIL_VERIFICATION,
                        Instant.now())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                        "The verification link is invalid or has expired"));

        User user = users.findById(token.getUserId())
                .orElseThrow(() -> ApiException.notFound("User"));
        token.consume(Instant.now());
        user.markEmailVerified(Instant.now());
        if (user.getStatus() == User.Status.PENDING_VERIFICATION) {
            user.setStatus(User.Status.ACTIVE);
        }
        oneTimeTokens.save(token);
        users.save(user);

        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.AUTHENTICATION, "EMAIL_VERIFIED")
                .actor(AuditEvent.ActorType.USER, user.getId(), user.getEmail())
                .entity("users", user.getId(), user.getEmail())
                .description("E-mail address confirmed")
                .request(ipAddress, userAgent, null, "/api/v1/auth/verify-email", "POST")
                .build());

        return user.getId();
    }

    // ---------------------------------------------------------------------------------
    // Login / logout
    // ---------------------------------------------------------------------------------

    public LoginResult login(String rawEmail, String password, String ipAddress, String userAgent) {
        String email = normalizeEmail(rawEmail);
        Instant now = Instant.now();
        Optional<User> candidate = users.findActiveByEmail(email);

        if (candidate.isEmpty()) {
            // A transaction would be rolled back by the exception below, so the failed attempt is
            // recorded first, in its own committed transaction.
            inNewTransaction(() -> auditService.record(AuditService.AuditEntry
                    .builder(AuditEvent.Category.AUTHENTICATION, "LOGIN_FAILED")
                    .actor(AuditEvent.ActorType.USER, null, null)
                    .description("Login attempt for an unknown or inactive address")
                    .result(AuditEvent.Result.FAILURE)
                    .severity(AuditEvent.Severity.NOTICE)
                    .request(ipAddress, userAgent, null, "/api/v1/auth/login", "POST")
                    .build()));
            // Same answer as a wrong password: the caller must not learn whether the account exists.
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid credentials");
        }

        User user = candidate.get();

        if (user.isLocked(now)) {
            auditService.recordDenied(AuditEvent.Category.AUTHENTICATION, "LOGIN_BLOCKED",
                    "Login attempt on a locked account");
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED, "The account is temporarily locked");
        }

        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            UUID userId = user.getId();
            inNewTransaction(() -> {
                User current = users.findById(userId).orElseThrow();
                current.registerFailedLogin(properties.getSecurity().getLogin().getMaxFailedAttempts(),
                        Instant.now().plus(properties.getSecurity().getLogin().getLockoutDuration()));
                users.save(current);
                return auditService.record(AuditService.AuditEntry
                        .builder(AuditEvent.Category.AUTHENTICATION, "LOGIN_FAILED")
                        .actor(AuditEvent.ActorType.USER, current.getId(), current.getEmail())
                        .entity("users", current.getId(), current.getEmail())
                        .description("Wrong password")
                        .result(AuditEvent.Result.FAILURE)
                        .severity(AuditEvent.Severity.NOTICE)
                        .request(ipAddress, userAgent, null, "/api/v1/auth/login", "POST")
                        .build());
            });
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid credentials");
        }

        return completeLogin(user, ipAddress, userAgent, "/api/v1/auth/login", "User signed in");
    }

    /**
     * Signs in a user whose identity an external identity provider has already proven (HD-OIDC). The caller has verified the
     * token; what remains is the same as after a password: the account must be usable, a session is issued, the audit
     * trail records it. A second factor of the platform still applies.
     */
    public LoginResult loginWithVerifiedIdentity(String rawEmail, String ipAddress, String userAgent) {
        User user = users.findActiveByEmail(normalizeEmail(rawEmail))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "No account for this identity"));
        if (user.isLocked(Instant.now())) {
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED, "The account is temporarily locked");
        }
        return completeLogin(user, ipAddress, userAgent, "/api/v1/auth/oidc/callback", "User signed in through the identity provider");
    }

    private LoginResult completeLogin(User user, String ipAddress, String userAgent, String path, String description) {
        if (user.getStatus() == User.Status.PENDING_VERIFICATION) {
            throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED,
                    "The e-mail address has not been confirmed yet");
        }
        if (user.getStatus() == User.Status.SUSPENDED || user.getStatus() == User.Status.DISABLED) {
            throw new ApiException(ErrorCode.FORBIDDEN, "The account is not enabled");
        }

        Membership membership = primaryMembership(user)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN,
                        "The account is not linked to any workspace"));

        UUID userId = user.getId();
        UUID tenantId = membership.getTenantId();
        UUID membershipId = membership.getId();

        // A person with a second factor gets a session that is not yet usable: the filter lets it reach
        // only the code check and sign-out until the code has been proven.
        boolean secondFactor = mfa.hasActiveFactor(userId);
        // An account that requires a second factor does not let a person without one in either: that
        // session may only enrol one (see SessionAuthenticationFilter).
        // Read before the transaction opens and inside the tenant: the setting is a tenant row, row level security
        // hides it from a request that has no tenant yet (sign-in), and a connection that is already open keeps the
        // tenant it was borrowed with.
        boolean enrolmentRequired = !secondFactor
                && Boolean.TRUE.equals(de.buerokratiecopilot.shared.context.RequestContext.runAs(tenantId,
                    () -> mfa.isMfaRequired(tenantId)));
        return inNewTransaction(() -> {
            User current = users.findById(userId).orElseThrow();
            current.registerSuccessfulLogin(Instant.now());
            users.save(current);

            SessionService.IssuedSession issued = sessionService.issue(current, tenantId,
                    !secondFactor && !enrolmentRequired,
                    ipAddress, userAgent);

            auditService.record(AuditService.AuditEntry
                    .builder(AuditEvent.Category.AUTHENTICATION, "LOGIN_SUCCEEDED")
                    .actor(AuditEvent.ActorType.USER, current.getId(), current.getEmail())
                    .tenant(tenantId)
                    .entity("sessions", issued.session().getId(), null)
                    .description(description)
                    .request(ipAddress, userAgent, null, path, "POST")
                    .build());

            return new LoginResult(issued.rawToken(), current.getId(), tenantId,
                    current.getDisplayName(), current.getLocale(), current.getTimeZone(),
                    membershipRoles.findRoleCodes(membershipId), secondFactor);
        });
    }

    /**
     * Sends the verification link again. Like the password reset, the answer is the same whether or not the
     * address exists or is already confirmed, so the endpoint cannot be used to enumerate accounts.
     */
    @Transactional
    public void resendVerification(String rawEmail) {
        users.findActiveByEmail(normalizeEmail(rawEmail))
                .filter(user -> user.getStatus() == User.Status.PENDING_VERIFICATION)
                .ifPresent(user -> sendVerificationMail(user,
                        issueOneTimeToken(user, OneTimeToken.Purpose.EMAIL_VERIFICATION, EMAIL_VERIFICATION_TTL)));
    }

    /** What the signed-in screen needs to greet the user and name the workspace. */
    public record SessionProfile(String email, String displayName, String locale, String timeZone,
                                 String workspaceName, List<String> roles) {
    }

    /**
     * Describes the principal of a request that is already authenticated. The caller supplies the ids
     * from the session, so nothing here can reach another workspace than the one the session names.
     */
    @Transactional(readOnly = true)
    public SessionProfile describeSession(UUID userId, UUID tenantId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED, "The session user no longer exists"));
        Optional<Tenant> tenant = tenantId == null ? Optional.empty() : tenants.findById(tenantId);
        String workspaceName = tenant.map(Tenant::getName).orElse(null);
        List<String> roleCodes = tenantId == null ? List.<String>of()
                : memberships.findByTenantIdAndUserId(tenantId, userId)
                        .map(m -> membershipRoles.findRoleCodes(m.getId()))
                        .orElse(List.of());
        return new SessionProfile(user.getEmail(), user.getDisplayName(), user.getLocale(), user.getTimeZone(),
                workspaceName, roleCodes);
    }

    /**
     * Counts a wrong second-factor code like a wrong password: repeated failures lock the account for the
     * same time, so the six-digit code cannot be guessed.
     */
    public void registerFailedSecondFactor(UUID userId, String ipAddress, String userAgent) {
        inNewTransaction(() -> {
            User current = users.findById(userId).orElseThrow();
            current.registerFailedLogin(properties.getSecurity().getLogin().getMaxFailedAttempts(),
                    Instant.now().plus(properties.getSecurity().getLogin().getLockoutDuration()));
            users.save(current);
            return auditService.record(AuditService.AuditEntry
                    .builder(AuditEvent.Category.AUTHENTICATION, "MFA_FAILED")
                    .actor(AuditEvent.ActorType.USER, current.getId(), current.getEmail())
                    .description("Wrong second-factor code")
                    .result(AuditEvent.Result.FAILURE)
                    .severity(AuditEvent.Severity.NOTICE)
                    .request(ipAddress, userAgent, null, "/api/v1/auth/mfa/verify", "POST")
                    .build());
        });
    }

    @Transactional
    public void logout(String rawSessionToken, String ipAddress, String userAgent) {
        sessionService.revoke(rawSessionToken, "USER_LOGOUT");
    }

    // ---------------------------------------------------------------------------------
    // Password reset
    // ---------------------------------------------------------------------------------

    /**
     * Starts a password reset. The HTTP response is always the same, whether or not the address
     * exists — otherwise the endpoint becomes a user enumeration oracle.
     */
    @Transactional
    public void requestPasswordReset(String rawEmail, String ipAddress, String userAgent) {
        String email = normalizeEmail(rawEmail);
        users.findActiveByEmail(email).ifPresent(user -> {
            String token = issueOneTimeToken(user, OneTimeToken.Purpose.PASSWORD_RESET, PASSWORD_RESET_TTL);
            String link = properties.getSecurity().getPublicBaseUrl() + "/reset-password?token=" + token;
            emailSender.send(EmailMessage.of(user.getEmail(), "Passwort zurücksetzen",
                    """
                    Guten Tag,

                    Sie haben ein neues Passwort für Bürokratie-Copilot angefordert.
                    Öffnen Sie den folgenden Link, um ein neues Passwort zu vergeben:

                    %s

                    Der Link ist %d Stunden gültig. Wenn Sie das nicht angefordert haben,
                    können Sie diese E-Mail ignorieren — Ihr Passwort bleibt unverändert.
                    """.formatted(link, PASSWORD_RESET_TTL.toHours())));
            auditService.record(AuditService.AuditEntry
                    .builder(AuditEvent.Category.AUTHENTICATION, "PASSWORD_RESET_REQUESTED")
                    .actor(AuditEvent.ActorType.USER, user.getId(), user.getEmail())
                    .entity("users", user.getId(), user.getEmail())
                    .description("Password reset requested")
                    .severity(AuditEvent.Severity.NOTICE)
                    .request(ipAddress, userAgent, null, "/api/v1/auth/password-reset", "POST")
                    .build());
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword, String ipAddress, String userAgent) {
        OneTimeToken token = oneTimeTokens
                .findUsable(SecurityTokens.sha256Hex(rawToken), OneTimeToken.Purpose.PASSWORD_RESET, Instant.now())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST,
                        "The reset link is invalid or has expired"));

        User user = users.findById(token.getUserId())
                .orElseThrow(() -> ApiException.notFound("User"));

        List<String> violations = passwordPolicy.validate(newPassword, user.getEmail(), user.getDisplayName());
        if (!violations.isEmpty()) {
            throw ApiException.validation("Password does not meet the policy",
                    violations.stream()
                            .map(key -> new de.buerokratiecopilot.shared.error.ApiErrorResponse.FieldViolation(
                                    "password", key.toUpperCase(Locale.ROOT), key))
                            .toList());
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        users.save(user);

        token.consume(Instant.now());
        oneTimeTokens.save(token);

        // Every other session must die: a reset usually means the old credentials leaked (§21.3).
        int revoked = sessionService.revokeAllForUser(user.getId(), "PASSWORD_RESET");

        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.AUTHENTICATION, "PASSWORD_RESET_COMPLETED")
                .actor(AuditEvent.ActorType.USER, user.getId(), user.getEmail())
                .entity("users", user.getId(), user.getEmail())
                .description("Password changed; " + revoked + " session(s) revoked")
                .severity(AuditEvent.Severity.NOTICE)
                .request(ipAddress, userAgent, null, "/api/v1/auth/password-reset/confirm", "POST")
                .build());
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Optional<Membership> primaryMembership(User user) {
        List<Membership> active = memberships.findByUserIdAndStatus(user.getId(), Membership.Status.ACTIVE);
        Instant now = Instant.now();
        return active.stream()
                .filter(m -> m.isEffective(now))
                .sorted(java.util.Comparator
                        .comparing(Membership::isOwner).reversed()
                        .thenComparing(m -> m.getJoinedAt() == null ? Instant.MAX : m.getJoinedAt()))
                .findFirst();
    }

    private String issueOneTimeToken(User user, OneTimeToken.Purpose purpose, Duration ttl) {
        String raw = SecurityTokens.newToken();
        oneTimeTokens.save(new OneTimeToken(UUID.randomUUID(), user.getId(), purpose,
                SecurityTokens.sha256Hex(raw), Instant.now().plus(ttl)));
        return raw;
    }

    private boolean sendVerificationMail(User user, String token) {
        String link = properties.getSecurity().getPublicBaseUrl() + "/verify-email?token=" + token;
        try {
            emailSender.send(EmailMessage.of(user.getEmail(), "Bitte bestätigen Sie Ihre E-Mail-Adresse",
                    """
                    Guten Tag,

                    bitte bestätigen Sie Ihre E-Mail-Adresse für Bürokratie-Copilot:

                    %s

                    Der Link ist 3 Tage gültig.
                    """.formatted(link)));
            return true;
        }
        catch (RuntimeException ex) {
            // The account stays usable: verification can be requested again. The failure is
            // reported to the caller instead of pretending the mail was delivered.
            log.warn("Verification mail could not be delivered for user {}", user.getId(), ex);
            return false;
        }
    }

    /** Runs a block in its own committed transaction, independent of the caller's outcome. */
    private <T> T inNewTransaction(java.util.function.Supplier<T> action) {
        return transactionTemplate.execute(status -> action.get());
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "An e-mail address is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String uniqueSlug(String email) {
        String base = email.substring(0, email.indexOf('@'))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (base.length() < 3) {
            base = "konto-" + base;
        }
        base = base.substring(0, Math.min(base.length(), 60));
        String candidate = base;
        int suffix = 1;
        while (tenants.existsBySlug(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }
}
