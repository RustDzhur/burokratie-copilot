package de.buerokratiecopilot.identity.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import de.buerokratiecopilot.ingest.IngestService;
import de.buerokratiecopilot.license.LicenseGuardFilter;
import de.buerokratiecopilot.license.LicenseService;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiErrorResponse;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * HTTP security of the platform (spec §21.3).
 *
 * <p>Decisions:</p>
 * <ul>
 *   <li><strong>Sessions</strong> are server-side; the cookie carries an opaque token, is
 *       {@code HttpOnly} and {@code SameSite} (configurable, {@code Lax} in development and
 *       {@code Strict} in production).</li>
 *   <li><strong>CSRF</strong> protection stays enabled because the browser sends credentials
 *       automatically. The SPA reads the {@code XSRF-TOKEN} cookie and echoes it in the
 *       {@code X-XSRF-TOKEN} header — the standard double-submit pattern.</li>
 *   <li><strong>Authorization</strong> is enforced per endpoint; authentication failures and
 *       denials answer with the same error envelope as the rest of the API.</li>
 *   <li>No custom cryptography and no disabled certificate validation anywhere.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
// The HTTP security chain only exists in a servlet application. The `worker` profile runs the
// same jar with `spring.main.web-application-type=none` (no endpoints, only background jobs), and
// without this condition the context would fail because no HttpSecurity bean exists there.
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    /** Endpoints that must stay reachable without a session. */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            // The identity provider sends the browser back here; the state cookie, not a session, authenticates the request.
            "/api/v1/auth/oidc/available",
            "/api/v1/auth/oidc/start",
            "/api/v1/auth/oidc/callback",
            // The mail provider sends the browser back here the same way. The callback is a top-level
            // navigation from Microsoft/Google and must not depend on the session cookie being sent: a
            // deployment with SameSite=Strict would fail every connection. The signed state cookie names the
            // tenant and the person (MailOAuthState), and the code is only exchangeable with its PKCE verifier.
            "/api/v1/mail/oauth/*/callback",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/verify-email/resend",
            "/api/v1/auth/password-reset",
            "/api/v1/auth/password-reset/confirm",
            "/api/v1/auth/csrf",
            "/api/v1/public/**",
            // Authenticated by its own token rather than by a session.
            "/api/v1/documents:ingest",
            // The Desktop Bridge presents a device key rather than a session (spec §8.2): it is a program on a
            // workstation, not a browser, and it cannot log in. DeviceService.authenticate checks the key.
            "/api/v1/device-agent/**",
            "/api/v1/webhooks/**",
            // A phone's calendar app cannot log in: the address carries its own secret (calendar_feed_tokens).
            "/api/v1/calendar/feed/*",
            "/api/v1/system/info",
            // An installation has to be diagnosable before any credential exists.
            "/api/v1/license",
            // The runtime admin surface authenticates itself with a per-installation secret
            // (X-Runtime-Admin-Token). It must stay reachable while the licence is missing so
            // that the operator can bootstrap and repair the installation.
            "/api/v1/runtime/**",
            // The operator page that fronts that surface. It is a static page without data:
            // every action on it sends X-Runtime-Admin-Token to /api/v1/runtime/**, which is
            // where the authorization actually happens. A runtime installation has no session,
            // so requiring one would make the page unusable by design.
            "/runtime-admin",
            "/runtime-admin/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /**
     * The CSRF repository is a bean because both the filter chain and the {@code /auth/csrf}
     * endpoint need it: the endpoint has to be able to write the cookie explicitly, otherwise a
     * single page application would never receive a token and every unsafe request would fail.
     */
    @Bean
    public org.springframework.security.web.csrf.CsrfTokenRepository csrfTokenRepository(
            BurokratieProperties properties) {
        return buildCsrfTokenRepository(properties);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   de.buerokratiecopilot.identity.auth.SessionService sessionService,
                                                   de.buerokratiecopilot.shared.idempotency.IdempotencyService idempotencyService,
                                                   MembershipAuthorityResolver authorityResolver,
                                                   ObjectMapper objectMapper,
                                                   org.springframework.context.MessageSource messageSource,
                                                   BurokratieProperties properties,
                                                   de.buerokratiecopilot.identity.mfa.MfaService mfa,
                                                   de.buerokratiecopilot.audit.AuditService auditService,
                                                   IngestService ingestService,
                                                   LicenseService licenseService,
                                                   org.springframework.beans.factory.ObjectProvider<AccessPolicy> accessPolicy)
            throws Exception {
        SessionAuthenticationFilter sessionAuthenticationFilter =
                new SessionAuthenticationFilter(sessionService, authorityResolver, properties, mfa,
                        accessPolicy.getIfAvailable(() -> AccessPolicy.OPEN));
        LicenseGuardFilter licenseGuardFilter =
                new LicenseGuardFilter(licenseService, objectMapper, messageSource);
        ApiTokenAuthenticationFilter apiTokenFilter =
                new ApiTokenAuthenticationFilter(ingestService, objectMapper, messageSource);
        de.buerokratiecopilot.shared.ratelimit.RateLimitFilter rateLimitFilter =
                new de.buerokratiecopilot.shared.ratelimit.RateLimitFilter(properties.getSecurity().getRateLimit(), objectMapper, auditService);
        de.buerokratiecopilot.shared.idempotency.IdempotencyFilter idempotencyFilter =
                new de.buerokratiecopilot.shared.idempotency.IdempotencyFilter(idempotencyService, objectMapper);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(buildCsrfTokenRepository(properties))
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        // Ingest is protected by API tokens plus an HMAC signature; browser CSRF
                        // does not apply there (spec §7.4).
                        // The machine interface: a bearer token, no session, no CSRF token to send.
                        .ignoringRequestMatchers("/api/v1/public/**", "/api/v1/documents:ingest",
                                "/api/v1/webhooks/**",
                                // The Desktop Bridge presents a device key, not a cookie: there is no browser
                                // session to protect from cross-site requests (spec §8.2).
                                "/api/v1/device-agent/**",
                                // The runtime admin surface authenticates with its own header, not a cookie.
                                "/api/v1/runtime/**")
                        // A bearer credential is never attached automatically by a browser; the
                        // double-submit cookie check would reject every non-browser client. CORS
                        // still prevents a cross-site page from setting the header.
                        .ignoringRequestMatchers(request -> {
                            String authorization = request.getHeader("Authorization");
                            return authorization != null && authorization.regionMatches(
                                    true, 0, "Bearer ", 0, 7);
                        }))
                .cors(cors -> cors.configurationSource(corsConfigurationSource(properties)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(objectMapper, messageSource, request, response,
                                        ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(objectMapper, messageSource, request, response,
                                        ErrorCode.FORBIDDEN)))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                        .contentTypeOptions(org.springframework.security.config.Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(policy -> policy.policy(
                                "camera=(self), microphone=(self), geolocation=()")))
                // The filter must run AFTER SecurityContextHolderFilter: that filter installs
                // (and later clears) the request's security context, so an authentication set
                // before it would be replaced by an empty context and every request would be
                // treated as anonymous.
                .addFilterAfter(sessionAuthenticationFilter,
                        org.springframework.security.web.context.SecurityContextHolderFilter.class)
                // The licence question comes before every credential question: an unlicensed
                // installation answers 402, not 401, so the operator sees the actual problem.
                .addFilterAfter(licenseGuardFilter, SessionAuthenticationFilter.class)
                // A bearer token is a principal like a session is: it must be installed before
                // the rate limit and (in particular) before authorization.
                .addFilterAfter(apiTokenFilter, LicenseGuardFilter.class)
                // Idempotency needs the authenticated tenant, so it runs right after the session
                // filter and before any request reaches a controller.
                // The general limit comes first, so that a flood is answered before any work is done for it.
                .addFilterAfter(rateLimitFilter, ApiTokenAuthenticationFilter.class)
                .addFilterAfter(idempotencyFilter, de.buerokratiecopilot.shared.ratelimit.RateLimitFilter.class);

        return http.build();
    }

    private CookieCsrfTokenRepository buildCsrfTokenRepository(BurokratieProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName("XSRF-TOKEN");
        repository.setHeaderName("X-XSRF-TOKEN");
        repository.setCookiePath("/");
        // Cookie attributes are intentionally configured through the session settings so that a
        // production deployment cannot end up with a non-secure CSRF cookie.
        repository.setCookieCustomizer(cookie -> cookie
                .secure(properties.getSecurity().getSession().isCookieSecure())
                .sameSite(properties.getSecurity().getSession().getCookieSameSite()));
        return repository;
    }

    private CorsConfigurationSource corsConfigurationSource(BurokratieProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        // Credentials are sent, therefore the allowed origin must be explicit — "*" is invalid.
        configuration.setAllowedOrigins(List.of(properties.getSecurity().getPublicBaseUrl()
                .replaceAll("/+$", "")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Authorization", "X-Correlation-Id",
                "X-XSRF-TOKEN", "X-Requested-With", "Idempotency-Key", "Accept-Language",
                "X-Runtime-Admin-Token"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "Content-Disposition",
                "X-License-Status", "X-License-Plan", "X-License-Expires-At", "X-License-Warning"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    /** Answers with the shared error envelope so clients parse one format (spec §20). */
    private void writeError(ObjectMapper objectMapper, org.springframework.context.MessageSource messageSource,
                            HttpServletRequest request, HttpServletResponse response, ErrorCode code)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Localized like every other API error, so a client never has to translate a code itself.
        String message = messageSource.getMessage(code.messageKey(), null, code.name(), request.getLocale());
        ApiErrorResponse body = ApiErrorResponse.of(code.name(), message,
                RequestContext.correlationId().orElse(null), request.getRequestURI());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
