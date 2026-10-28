package de.buerokratiecopilot.license;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiErrorResponse;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Holds the agent/workflow surface to the licence state.
 *
 * <p>The filter runs before authorization on purpose. A caller without a session must learn that
 * the installation has no licence (402), not that it forgot to authenticate (401): the operator's
 * first question after a fresh install is "why does the bridge get nothing?", and the answer must
 * name the licence, not the credentials.</p>
 *
 * <p>Health, {@code /api/v1/auth/**}, the licence status endpoint and the runtime admin surface
 * stay reachable in every state. Otherwise an unlicensed or expired installation could not be
 * diagnosed or repaired through the API.</p>
 */
public class LicenseGuardFilter extends OncePerRequestFilter {

    private final LicenseService license;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    public LicenseGuardFilter(LicenseService license, ObjectMapper objectMapper,
                              MessageSource messageSource) {
        this.license = license;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!license.isEnforced()) {
            return true;
        }
        String path = request.getRequestURI();
        return !businessSurface(path) && !ingest(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        LicenseService.LicenseStatus status = license.status();
        String path = request.getRequestURI();

        response.setHeader("X-License-Status", status.state().name());
        if (status.plan() != null) {
            response.setHeader("X-License-Plan", status.plan());
        }
        if (status.expiresAt() != null) {
            response.setHeader("X-License-Expires-At", status.expiresAt().toString());
        }

        if (status.state() == LicenseState.LICENSE_MISSING) {
            if (businessSurface(path)) {
                write(request, response, ErrorCode.LICENSE_MISSING, status.reason());
                return;
            }
            // Ingest is input, not execution. It stays reachable while unlicensed: the documents
            // are stored for the moment the licence arrives, and refusing them would mean data
            // loss with no benefit.
            chain.doFilter(request, response);
            return;
        }

        if (status.state() == LicenseState.LICENSE_REVOKED) {
            // A revocation has no grace period: the business surface is closed immediately.
            // Ingest stays reachable for the same reason as above — accepting documents is not
            // executing an agent, and a customer who is about to renew must not lose data.
            if (businessSurface(path)) {
                write(request, response, ErrorCode.LICENSE_REVOKED, status.revocationReason());
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        if (status.state() == LicenseState.LICENSE_READ_ONLY
                && (businessSurface(path) || ingest(path)) && !safeMethod(request.getMethod())) {
            write(request, response, ErrorCode.LICENSE_READ_ONLY, null);
            return;
        }

        if (status.state() == LicenseState.LICENSE_GRACE) {
            response.setHeader("X-License-Warning",
                    "Licence expired on " + status.expiresAt() + "; "
                            + status.graceDaysRemaining() + " grace day(s) remaining");
        }
        chain.doFilter(request, response);
    }

    /** The business API the licence is about: agents, workflows, exports and settings. */
    private static boolean businessSurface(String path) {
        return under(path, "/api/v1/agents")
                || under(path, "/api/v1/constellations")
                || under(path, "/api/v1/workflows")
                || under(path, "/api/v1/workflow-runs")
                || under(path, "/api/v1/exports")
                || under(path, "/api/v1/settings");
    }

    private static boolean ingest(String path) {
        return "/api/v1/documents:ingest".equals(path);
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static boolean safeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       ErrorCode code, String reason) throws IOException {
        String message = messageSource.getMessage(code.messageKey(), null, code.name(),
                request.getLocale());
        if (reason != null && !reason.isBlank()) {
            // The reason is for the operator and contains no key material; it explains *why* the
            // state is LICENSE_MISSING (missing file, wrong instance, bad signature).
            message = message + " (" + reason + ")";
        }
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        ApiErrorResponse body = ApiErrorResponse.of(code.name(), message,
                RequestContext.correlationId().orElse(null), request.getRequestURI());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
