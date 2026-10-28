package de.buerokratiecopilot.shared.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiErrorResponse;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The general limit on how often the API may be called (HD-RATE-01).
 *
 * <p>The counter is per person once signed in and per address before that, in windows of one minute. A call over the limit
 * is answered with 429 and {@code Retry-After}, and the first one in each window is written to the audit trail, so that a
 * flood leaves one line and not thousands. Health checks are not counted.</p>
 *
 * <p>Limit of this kind: the counters live in the memory of one instance. With several API instances a person can make the
 * limit times the number of instances; a shared counter is the step after that, and it is not claimed here.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long WINDOW_MILLIS = 60_000L;
    private static final int SWEEP_ABOVE = 20_000;

    private record Window(long start, AtomicInteger count, boolean[] reported) {
    }

    private final BurokratieProperties.Security.RateLimit settings;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(BurokratieProperties.Security.RateLimit settings, ObjectMapper objectMapper, AuditService audit) {
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !settings.isEnabled() || !path.startsWith("/api/") || path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = key(request);
        long now = System.currentTimeMillis();
        if (windows.size() > SWEEP_ABOVE) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().start() >= WINDOW_MILLIS);
        }
        Window window = windows.compute(key, (k, old) ->
                old == null || now - old.start() >= WINDOW_MILLIS ? new Window(now, new AtomicInteger(), new boolean[1]) : old);
        int used = window.count().incrementAndGet();
        int limit = settings.getRequestsPerMinute();
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - used)));
        if (used <= limit) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, (window.start() + WINDOW_MILLIS - now + 999) / 1000);
        boolean first;
        synchronized (window.reported()) {
            first = !window.reported()[0];
            window.reported()[0] = true;
        }
        if (first) {
            report(request, key, limit);
        }
        response.setStatus(ErrorCode.RATE_LIMITED.status().value());
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(ErrorCode.RATE_LIMITED.name(),
                "Zu viele Anfragen. Bitte in " + retryAfter + " Sekunden erneut versuchen.",
                RequestContext.correlationId().orElse(null), request.getRequestURI()));
    }

    private static String key(HttpServletRequest request) {
        UUID tenant = RequestContext.tenantId().orElse(null);
        UUID user = CurrentUser.id().orElse(null);
        if (tenant != null && user != null) {
            return "u:" + tenant + ":" + user;
        }
        return "ip:" + request.getRemoteAddr();
    }

    /** One line in the log, and in the audit trail when the caller is signed in. */
    private void report(HttpServletRequest request, String key, int limit) {
        LOG.warn("Rate limit of {} per minute exceeded by {} on {} {}", limit, key.startsWith("ip:") ? key : "a signed-in user",
                request.getMethod(), request.getRequestURI());
        if (RequestContext.tenantId().isEmpty()) {
            return;
        }
        try {
            audit.recordDenied(AuditEvent.Category.AUTHORIZATION, "API_RATE_LIMITED",
                    "Mehr als " + limit + " Anfragen pro Minute; weitere Anfragen werden abgewiesen");
        }
        catch (RuntimeException ex) {
            LOG.warn("The rate limit event could not be written to the audit trail", ex);
        }
    }
}
