package de.buerokratiecopilot.identity.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.UserSessionRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.identity.domain.UserSession;
import de.buerokratiecopilot.identity.mfa.MfaService;
import de.buerokratiecopilot.identity.security.PasswordPolicy;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * What a signed-in person may see and do about their own sign-in: devices, password, second factor.
 *
 * <p>Everything here is about the caller. A session identifier from the request is looked up among the
 * caller's own sessions, so naming somebody else's session id answers "not found" and revokes nothing;
 * the same boundary that hides another tenant's rows hides another person's devices.</p>
 */
@Service
public class SecuritySettingsService {

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final SessionService sessionService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final MfaService mfa;
    private final AuditService audit;
    private final BurokratieProperties properties;

    public SecuritySettingsService(UserRepository users, UserSessionRepository sessions,
                                   SessionService sessionService, PasswordEncoder passwordEncoder,
                                   PasswordPolicy passwordPolicy, MfaService mfa, AuditService audit,
                                   BurokratieProperties properties) {
        this.users = users;
        this.sessions = sessions;
        this.sessionService = sessionService;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.mfa = mfa;
        this.audit = audit;
        this.properties = properties;
    }

    /** The security card: factor, password age, live sessions and the rules that apply. */
    @Transactional(readOnly = true)
    public Map<String, Object> overview(UUID userId, UUID currentSessionId, UUID tenantId) {
        User user = requireUser(userId);
        Instant now = Instant.now();

        List<Map<String, Object>> live = sessions.findByUserIdAndRevokedAtIsNull(userId).stream()
                .filter(session -> session.isValid(now))
                .sorted(java.util.Comparator.comparing(UserSession::getLastSeenAt).reversed())
                .map(session -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", session.getId().toString());
                    row.put("device", describeDevice(session.getUserAgent()));
                    row.put("location", session.getIpAddress() == null ? null
                            : session.getIpAddress().getHostAddress());
                    row.put("lastActiveAt", session.getLastSeenAt().toString());
                    row.put("current", session.getId().equals(currentSessionId));
                    return row;
                }).toList();

        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("passwordMinLength", properties.getSecurity().getPassword().getMinLength());
        policy.put("maxFailedLogins", properties.getSecurity().getLogin().getMaxFailedAttempts());
        policy.put("lockoutMinutes", properties.getSecurity().getLogin().getLockoutDuration().toMinutes());
        policy.put("sessionIdleMinutes", properties.getSecurity().getSession().getIdleTimeout().toMinutes());
        policy.put("sessionMaxHours", properties.getSecurity().getSession().getAbsoluteTimeout().toHours());
        policy.put("mfaRequiredForAccount", mfa.isMfaRequired(tenantId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mfaEnabled", mfa.hasActiveFactor(userId));
        result.put("passwordChangedAt",
                user.getPasswordChangedAt() == null ? null : user.getPasswordChangedAt().toString());
        result.put("sessions", live);
        result.put("policy", policy);
        return result;
    }

    /**
     * Changes the caller's password.
     *
     * <p>The current password is checked first and a wrong one answers like any other failed sign-in, so this
     * is not an oracle for somebody who found an open session. Every other session is ended afterwards: a
     * person changing a password on one device usually wants the others signed out.</p>
     */
    @Transactional
    public void changePassword(UUID userId, UUID currentSessionId, String currentPassword, String newPassword,
                               String ipAddress, String userAgent) {
        User user = requireUser(userId);
        if (currentPassword == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            audit.recordDenied(AuditEvent.Category.AUTHENTICATION, "PASSWORD_CHANGE_REFUSED",
                    "Wrong current password");
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "The current password is wrong");
        }
        List<String> violations = passwordPolicy.validate(newPassword, user.getEmail(), user.getDisplayName());
        if (!violations.isEmpty()) {
            throw ApiException.validation("Password does not meet the policy",
                    violations.stream()
                            .map(key -> new de.buerokratiecopilot.shared.error.ApiErrorResponse.FieldViolation(
                                    "newPassword", key.toUpperCase(Locale.ROOT), key))
                            .toList());
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "The new password must differ from the current one");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        users.save(user);

        int ended = 0;
        for (UserSession session : sessions.findByUserIdAndRevokedAtIsNull(userId)) {
            if (!session.getId().equals(currentSessionId)) {
                session.revoke("PASSWORD_CHANGED", Instant.now());
                sessions.save(session);
                ended++;
            }
        }
        audit.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.AUTHENTICATION, "PASSWORD_CHANGED")
                .actor(AuditEvent.ActorType.USER, user.getId(), user.getEmail())
                .entity("users", user.getId(), user.getEmail())
                .description("Password changed; " + ended + " other session(s) ended")
                .severity(AuditEvent.Severity.NOTICE)
                .request(ipAddress, userAgent, null, "/api/v1/settings/security/password", "POST")
                .build());
    }

    /** Ends one of the caller's own sessions; the one in use cannot be ended this way (that is signing out). */
    @Transactional
    public void revokeSession(UUID userId, UUID currentSessionId, UUID sessionId) {
        if (sessionId.equals(currentSessionId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die aktuelle Sitzung wird durch Abmelden beendet");
        }
        UserSession session = sessions.findById(sessionId)
                .filter(candidate -> candidate.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("session"));
        if (session.getRevokedAt() == null) {
            session.revoke("USER_REVOKED", Instant.now());
            sessions.save(session);
            audit.record(AuditService.AuditEntry
                    .builder(AuditEvent.Category.AUTHENTICATION, "SESSION_REVOKED")
                    .actor(AuditEvent.ActorType.USER, userId, null)
                    .tenant(session.getTenantId())
                    .entity("sessions", session.getId(), null)
                    .description("Session ended by its owner")
                    .build());
        }
    }

    private User requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("user"));
    }

    /** "Chrome on macOS" from a user agent, without trusting it for anything but a label. */
    static String describeDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unbekanntes Gerät";
        }
        String agent = userAgent.toLowerCase(Locale.ROOT);
        String browser = agent.contains("edg/") ? "Edge" : agent.contains("firefox") ? "Firefox"
                : agent.contains("chrome") ? "Chrome" : agent.contains("safari") ? "Safari" : "Browser";
        String system = agent.contains("windows") ? "Windows" : agent.contains("iphone") ? "iOS"
                : agent.contains("ipad") ? "iPadOS" : agent.contains("android") ? "Android"
                : agent.contains("mac os") ? "macOS" : agent.contains("linux") ? "Linux" : null;
        return system == null ? browser : browser + " – " + system;
    }
}
