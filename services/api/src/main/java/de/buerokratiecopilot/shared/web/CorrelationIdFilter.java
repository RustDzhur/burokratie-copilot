package de.buerokratiecopilot.shared.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Assigns a correlation ID to every request, exposes it in the MDC and returns it to the
 * caller as {@code X-Correlation-Id}.
 *
 * <p>The identifier is the only handle needed to find the matching log lines, which is why
 * error responses carry it (spec §20). An inbound value is accepted only when it looks like a
 * UUID, so a client cannot inject arbitrary text into the logs.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolve(request);
        RequestContext.setCorrelationId(correlationId);
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        }
        finally {
            // The thread is returned to the pool: never let the context leak into the next request.
            MDC.remove(MDC_KEY);
            RequestContext.clear();
        }
    }

    private String resolve(HttpServletRequest request) {
        String inbound = request.getHeader(HEADER);
        if (inbound != null && inbound.length() == 36) {
            try {
                return UUID.fromString(inbound).toString();
            }
            catch (IllegalArgumentException ignored) {
                // fall through to a freshly generated identifier
            }
        }
        return UUID.randomUUID().toString();
    }
}
