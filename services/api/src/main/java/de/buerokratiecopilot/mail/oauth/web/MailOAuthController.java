package de.buerokratiecopilot.mail.oauth.web;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.mail.oauth.MailOAuthException;
import de.buerokratiecopilot.mail.oauth.MailOAuthProvider;
import de.buerokratiecopilot.mail.oauth.MailOAuthService;
import de.buerokratiecopilot.mail.oauth.MailOAuthState;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Connecting a Microsoft 365 or Google Workspace mailbox (spec §7.2).
 *
 * <p>The same two-step shape as the platform's own OIDC sign-in: {@code /start} sends the browser to the
 * provider with a signed state cookie (state and PKCE verifier in it), and {@code /callback} checks that
 * cookie, exchanges the code and stores the tokens encrypted. The callback is deliberately reachable
 * without a session — it is a top-level navigation from a third-party site, and a deployment with
 * {@code SameSite=Strict} would otherwise fail every connection. The identity of the person and the tenant
 * comes from the signed payload, never from a query parameter.</p>
 *
 * <p>The browser is always sent back to the mail screen with a reason, never answered with a token or a
 * provider error body. The reason codes are {@code state}, {@code denied}, {@code code}, {@code exchange},
 * {@code userinfo}, {@code storage} and {@code unavailable}.</p>
 */
@RestController
@RequestMapping("/api/v1/mail/oauth")
@Tag(name = "Mail OAuth", description = "Connecting Microsoft 365 and Google Workspace mailboxes")
public class MailOAuthController {

    private static final Logger LOG = LoggerFactory.getLogger(MailOAuthController.class);

    private static final String START = "hasAnyAuthority('mail.connect','mail.configure')";
    private static final String SEE = "hasAnyAuthority('mail.view','mail.connect','mail.configure')";

    private final MailOAuthService oauth;
    private final MailOAuthState states;
    private final BurokratieProperties properties;

    public MailOAuthController(MailOAuthService oauth, MailOAuthState states, BurokratieProperties properties) {
        this.oauth = oauth;
        this.states = states;
        this.properties = properties;
    }

    /** Which providers are configured, and what is missing when one is not. */
    @GetMapping("/providers")
    @PreAuthorize(SEE)
    @Operation(summary = "Which OAuth mail providers this installation can offer, without any secret")
    public List<Map<String, Object>> providers() {
        return oauth.describeAll();
    }

    /**
     * Sends the browser to the provider.
     *
     * <p>A provider whose application is not registered answers {@code 503} with the missing configuration
     * keys in {@code details}, rather than starting a flow that cannot finish.</p>
     */
    @GetMapping("/{provider}/start")
    @PreAuthorize(START)
    @Operation(summary = "Starts the authorization-code flow for a mailbox")
    public ResponseEntity<Void> start(@PathVariable String provider) {
        MailOAuthProvider resolved = required(provider);
        MailOAuthService.Started started = oauth.start(resolved,
                RequestContext.tenantId().orElseThrow(), CurrentUser.required());
        return ResponseEntity.status(HttpStatus.FOUND).location(started.location())
                .header(HttpHeaders.SET_COOKIE, cookie(started.cookieName(), started.cookieValue(),
                        Duration.ofSeconds(started.cookieMaxAgeSeconds())).toString())
                .build();
    }

    /**
     * The provider sends the browser back here.
     *
     * <p>Always answers with a redirect to the mail screen and clears the state cookie. A failure is a
     * reason code in the query; the details stay in the log and never include tokens.</p>
     */
    @GetMapping("/{provider}/callback")
    @Operation(summary = "Returns from the provider and stores the mailbox credentials encrypted")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         HttpServletRequest request) {
        MailOAuthProvider resolved = required(provider);
        String cookieName = states.cookieName(resolved);
        String stateCookie = cookieValue(request, cookieName);
        ResponseCookie cleared = cookie(cookieName, "", Duration.ZERO);

        if (error != null && !error.isBlank()) {
            LOG.info("{} refused the mailbox connection: {}", resolved.displayName(), error);
            oauth.recordFailure(resolved, "provider answered " + error);
            return redirect(cleared, "/mail?oauth=error&reason=denied");
        }
        try {
            MailOAuthService.Connected connected = oauth.finish(resolved, code, state, stateCookie);
            LOG.info("Mailbox {} connected with {}", connected.accountId(), resolved.displayName());
            return redirect(cleared, "/mail?oauth=connected&provider=" + resolved.path());
        }
        catch (MailOAuthException ex) {
            LOG.info("{} mailbox connection refused: {}", resolved.displayName(), ex.getMessage());
            oauth.recordFailure(resolved, ex.reason());
            return redirect(cleared, "/mail?oauth=error&reason=" + ex.reason());
        }
        catch (ApiException ex) {
            LOG.warn("{} mailbox connection could not be stored: {}", resolved.displayName(), ex.getMessage());
            oauth.recordFailure(resolved, "storage");
            return redirect(cleared, "/mail?oauth=error&reason=unavailable");
        }
        catch (RuntimeException ex) {
            LOG.error("{} mailbox connection failed unexpectedly", resolved.displayName(), ex);
            oauth.recordFailure(resolved, "unexpected");
            return redirect(cleared, "/mail?oauth=error&reason=unavailable");
        }
    }

    private MailOAuthProvider required(String provider) {
        return MailOAuthProvider.fromPath(provider)
                .orElseThrow(() -> ApiException.notFound("OAuth provider"));
    }

    private ResponseEntity<Void> redirect(ResponseCookie cleared, String path) {
        String base = properties.getSecurity().getPublicBaseUrl().replaceAll("/+$", "");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(base + path))
                .header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
    }

    private ResponseCookie cookie(String name, String value, Duration age) {
        return ResponseCookie.from(name, value).httpOnly(true)
                .secure(properties.getSecurity().getSession().isCookieSecure())
                // Lax: the cookie must come back with the top-level navigation from the provider.
                .sameSite("Lax").path("/api/v1/mail/oauth").maxAge(age).build();
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
