package de.buerokratiecopilot.identity.security;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import de.buerokratiecopilot.identity.domain.UserSession;
import de.buerokratiecopilot.identity.auth.SessionService;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Authenticates a request from the session cookie.
 *
 * <p>Responsibilities, in this order:</p>
 * <ol>
 *   <li>read the opaque session cookie,</li>
 *   <li>resolve the server-side session (its own transaction, no tenant bound yet),</li>
 *   <li>bind the tenant and the user of the session into the {@link RequestContext} so that the
 *       connection of the following business transaction is tenant-scoped,</li>
 *   <li>publish the permissions of the membership as Spring Security authorities,</li>
 *   <li>clear everything on the way out — a pooled thread must never carry state into the next
 *       request.</li>
 * </ol>
 *
 * <p>The tenant is therefore always derived from the authenticated session and never read from a
 * request body, query parameter or header (spec §3.2).</p>
 *
 * <p>The filter is intentionally <em>not</em> a Spring bean: a {@code Filter} bean is also
 * auto-registered in the servlet chain by Spring Boot, and {@link OncePerRequestFilter} would
 * then skip its second execution inside the security chain — leaving every request anonymous.
 * It is instantiated by {@code SecurityConfiguration} instead.</p>
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessionService;
    private final MembershipAuthorityResolver authorityResolver;
    private final BurokratieProperties properties;

    public SessionAuthenticationFilter(SessionService sessionService,
                                       MembershipAuthorityResolver authorityResolver,
                                       BurokratieProperties properties) {
        this.sessionService = sessionService;
        this.authorityResolver = authorityResolver;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = readSessionCookie(request);
        if (token != null) {
            try {
                Optional<UserSession> session = sessionService.resolve(token);
                session.ifPresent(value -> authenticate(value, request));
            }
            catch (RuntimeException ex) {
                // A broken session lookup must not break public endpoints: log and continue
                // as an anonymous request.
                logger.warn("Could not resolve session", ex);
            }
        }
        try {
            chain.doFilter(request, response);
        }
        finally {
            SecurityContextHolder.clearContext();
            RequestContext.clear();
            MDC.remove("tenantId");
            MDC.remove("userId");
        }
    }

    private void authenticate(UserSession session, HttpServletRequest request) {
        RequestContext.setTenantId(session.getTenantId());
        RequestContext.setUserId(session.getUserId());
        RequestContext.setActorType(RequestContext.ActorType.USER);

        if (session.getTenantId() != null) {
            MDC.put("tenantId", session.getTenantId().toString());
        }
        if (session.getUserId() != null) {
            MDC.put("userId", session.getUserId().toString());
        }

        List<String> permissions = session.getTenantId() == null
                ? List.of()
                : authorityResolver.permissionsOf(session.getTenantId(), session.getUserId());
        List<SimpleGrantedAuthority> authorities = permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        var authentication = new UsernamePasswordAuthenticationToken(
                session.getUserId().toString(), null, authorities);
        authentication.setDetails(new SessionDetails(session.getId(), session.getTenantId(),
                session.isMfaVerified()));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Recorded for the audit trail of the current request.
        request.setAttribute("bc.sessionId", session.getId());
    }

    private String readSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = properties.getSecurity().getSession().getCookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** Details of the authenticated session, available for services that need them. */
    public record SessionDetails(UUID sessionId, UUID tenantId, boolean mfaVerified) {
    }

    /** Resolves the permission codes of a membership; cached per request by the resolver. */
    public interface MembershipAuthorityResolver {
        List<String> permissionsOf(UUID tenantId, UUID userId);
    }

    static String normalize(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
