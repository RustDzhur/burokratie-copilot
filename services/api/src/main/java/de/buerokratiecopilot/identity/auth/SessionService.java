package de.buerokratiecopilot.identity.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.identity.UserSessionRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.identity.domain.UserSession;
import de.buerokratiecopilot.identity.security.SecurityTokens;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.util.IpAddresses;

/**
 * Issues, resolves and revokes server-side sessions.
 *
 * <p>Resolution deliberately runs in its own transaction: the tenant of a request is only known
 * after the session has been read, and the database connection of the subsequent business
 * transaction must be bound to that tenant (see {@code TenantAwareDataSource}).</p>
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final UserSessionRepository sessions;
    private final AuditService auditService;
    private final BurokratieProperties properties;

    public SessionService(UserSessionRepository sessions, AuditService auditService,
                          BurokratieProperties properties) {
        this.sessions = sessions;
        this.auditService = auditService;
        this.properties = properties;
    }

    /** Raw token handed to the browser; the database only stores its hash. */
    public record IssuedSession(String rawToken, UserSession session) {
    }

    /** Joins the caller's transaction so signing in and creating the session are atomic. */
    @Transactional(propagation = Propagation.REQUIRED)
    public IssuedSession issue(User user, UUID tenantId, boolean mfaVerified, String ipAddress,
                               String userAgent) {
        String rawToken = SecurityTokens.newToken();
        Instant now = Instant.now();
        Duration idle = properties.getSecurity().getSession().getIdleTimeout();
        Duration absolute = properties.getSecurity().getSession().getAbsoluteTimeout();

        UserSession session = new UserSession(UUID.randomUUID(), user.getId(),
                SecurityTokens.sha256Hex(rawToken),
                earlier(now.plus(idle), now.plus(absolute)),
                now.plus(absolute));
        session.setTenantId(tenantId);
        session.setMfaVerified(mfaVerified);
        session.setIpAddress(IpAddresses.parse(ipAddress));
        session.setUserAgent(userAgent);
        sessions.save(session);

        log.debug("Issued session for user {} in tenant {}", user.getId(), tenantId);
        return new IssuedSession(rawToken, session);
    }

    /**
     * Resolves a session token. Returns an empty result for unknown, revoked or expired tokens —
     * the caller must not distinguish between these cases towards the client.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UserSession> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        Optional<UserSession> found = sessions.findByTokenHash(SecurityTokens.sha256Hex(rawToken));
        Instant now = Instant.now();
        if (found.isEmpty() || !found.get().isValid(now)) {
            return Optional.empty();
        }
        UserSession session = found.get();
        session.touch(now, properties.getSecurity().getSession().getIdleTimeout());
        return Optional.of(sessions.save(session));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revoke(String rawToken, String reason) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        sessions.findByTokenHash(SecurityTokens.sha256Hex(rawToken)).ifPresent(session -> {
            session.revoke(reason, Instant.now());
            sessions.save(session);
            auditService.record(AuditService.AuditEntry
                    .builder(AuditEvent.Category.AUTHENTICATION, "SESSION_REVOKED")
                    .actor(AuditEvent.ActorType.USER, session.getUserId(), null)
                    .tenant(session.getTenantId())
                    .entity("sessions", session.getId(), null)
                    .description("Session revoked: " + reason)
                    .build());
        });
    }

    /** Revokes every session of a user — used on password change and on access revocation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(UUID userId, String reason) {
        return sessions.revokeAllForUser(userId, reason, Instant.now());
    }

    /** Moves a session to another tenant the user is a member of (tenant switcher). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void switchTenant(UUID sessionId, UUID tenantId) {
        sessions.findById(sessionId).ifPresent(session -> {
            session.setTenantId(tenantId);
            sessions.save(session);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired() {
        return sessions.purgeExpired(Instant.now());
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }
}
