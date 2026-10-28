package de.buerokratiecopilot.identity.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.ingest.IngestService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiErrorResponse;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Authenticates an agent/workflow request from an API token.
 *
 * <p>Before this filter existed the token was described in the documentation and accepted only
 * by the document ingest endpoint, which checks it inside its controller. A bridge holding a
 * token with {@code agents.view}/{@code agents.run} could therefore read the catalogue contract
 * and not call it. The token is now a first-class principal: tenant, acting user and scopes become
 * the same request context that a session would create.</p>
 *
 * <p>The filter is applied only to the agent/workflow surface. Ingest authenticates its own token
 * inside the controller (and would otherwise be counted twice against the per-token rate limit),
 * and the device endpoint has its own key scheme.</p>
 *
 * <p>Scopes are permission codes. {@code agents.run} additionally opens the workflow and
 * constellation routes that execute agents, because a runtime client starting a workflow is
 * doing exactly what the scope names; {@code agents.view} opens the read side of the same
 * objects. No scope grants a tenant-wide role.</p>
 */
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final IngestService tokens;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    public ApiTokenAuthenticationFilter(IngestService tokens, ObjectMapper objectMapper,
                                        MessageSource messageSource) {
        this.tokens = tokens;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !under(path, "/api/v1/agents")
                && !under(path, "/api/v1/constellations")
                && !under(path, "/api/v1/workflows")
                && !under(path, "/api/v1/workflow-runs")
                && !under(path, "/api/v1/exports")
                && !under(path, "/api/v1/documents")
                && !under(path, "/api/v1/settings");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = bearer(request.getHeader("Authorization"));
        if (presented == null) {
            chain.doFilter(request, response);
            return;
        }

        IngestService.Caller caller;
        try {
            caller = tokens.authenticate(presented);
        }
        catch (ApiException ex) {
            // A bad token is not a session failure: no cookie is involved and the caller must be
            // told which credential was refused.
            writeError(request, response, ex);
            return;
        }

        UUID actor = caller.createdBy();
        RequestContext.setTenantId(caller.tenantId());
        if (actor != null) {
            RequestContext.setUserId(actor);
            MDC.put("userId", actor.toString());
        }
        RequestContext.setActorType(RequestContext.ActorType.API_TOKEN);
        MDC.put("tenantId", caller.tenantId().toString());

        List<GrantedAuthority> authorities = authorities(caller.scopes());
        String principal = actor == null ? "api-token:" + caller.tokenId() : actor.toString();
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        chain.doFilter(request, response);
    }

    /**
     * The principal's scopes, expanded to the permission codes of the routes they cover.
     *
     * <p>The expansion is deliberately visible here rather than hidden in {@code @PreAuthorize}
     * expressions: a reader can see that {@code agents.run} is also allowed to run a workflow, and
     * that no scope is allowed to edit a workflow definition.</p>
     */
    private static List<GrantedAuthority> authorities(List<String> scopes) {
        Set<String> codes = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null || scope.isBlank()) {
                continue;
            }
            String value = scope.strip();
            switch (value) {
                case "agents.view" -> {
                    codes.add("agents.view");
                    codes.add("constellations.view");
                    codes.add("workflows.view");
                }
                case "agents.run" -> {
                    codes.add("agents.run");
                    codes.add("constellations.instantiate");
                    codes.add("workflows.run");
                    codes.add("workflows.execute");
                }
                case "documents:read" -> codes.add("documents.view");
                case "documents:write" -> {
                    codes.add("documents.create");
                    codes.add("documents.review");
                }
                case "exports:read" -> codes.add("documents.view");
                case "settings:read" -> codes.add("settings.view");
                case "settings:write" -> {
                    codes.add("settings.view");
                    codes.add("settings.edit");
                    codes.add("settings.manage_rules");
                    codes.add("settings.manage_retention");
                }
                default -> codes.add(value);
            }
        }
        List<GrantedAuthority> result = new ArrayList<>(codes.size());
        for (String code : codes) {
            result.add(new SimpleGrantedAuthority(code));
        }
        return result;
    }

    private static String bearer(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String trimmed = authorization.strip();
        if (!trimmed.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = trimmed.substring(BEARER.length()).strip();
        return token.isEmpty() ? null : token;
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response,
                            ApiException ex) throws IOException {
        ErrorCode code = ex.errorCode();
        String message = ex.getMessage() == null || ex.getMessage().isBlank()
                ? messageSource.getMessage(code.messageKey(), null, code.name(), request.getLocale())
                : ex.getMessage();
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (code == ErrorCode.UNAUTHENTICATED) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"firmspace-runtime\"");
        }
        ApiErrorResponse body = ApiErrorResponse.of(code.name(), message,
                RequestContext.correlationId().orElse(null), request.getRequestURI());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
