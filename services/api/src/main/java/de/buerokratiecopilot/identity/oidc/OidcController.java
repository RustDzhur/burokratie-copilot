package de.buerokratiecopilot.identity.oidc;

import java.net.URI;
import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.identity.auth.AuthService;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.error.ApiException;

/** Sign-in through an external OpenID Connect provider: the browser is sent away and comes back. */
@RestController
@RequestMapping("/api/v1/auth/oidc")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class OidcController {

    private static final Logger LOG = LoggerFactory.getLogger(OidcController.class);
    private static final String COOKIE = "bc_oidc";

    private final OidcLoginService login;
    private final BurokratieProperties properties;

    public OidcController(OidcLoginService login, BurokratieProperties properties) {
        this.login = login;
        this.properties = properties;
    }

    private ResponseCookie stateCookie(String value, Duration age) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(properties.getSecurity().getSession().isCookieSecure())
                // Lax: the cookie must come back with the top-level navigation from the provider.
                .sameSite("Lax").path("/api/v1/auth/oidc").maxAge(age).build();
    }

    private ResponseCookie sessionCookie(String token) {
        BurokratieProperties.Security.Session session = properties.getSecurity().getSession();
        return ResponseCookie.from(session.getCookieName(), token).httpOnly(true).secure(session.isCookieSecure())
                .sameSite(session.getCookieSameSite()).path("/").maxAge(session.getAbsoluteTimeout()).build();
    }

    private String base() {
        return properties.getSecurity().getPublicBaseUrl().replaceAll("/+$", "");
    }

    /** Whether the login page should offer this sign-in at all. */
    @GetMapping("/available")
    public java.util.Map<String, Boolean> available() {
        return java.util.Map.of("enabled", login.enabled());
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start() {
        OidcLoginService.Start start = login.start();
        return ResponseEntity.status(HttpStatus.FOUND).location(start.location())
                .header(HttpHeaders.SET_COOKIE, stateCookie(start.cookieValue(), Duration.ofMinutes(10)).toString()).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = COOKIE, required = false) String stateCookie,
                                         HttpServletRequest request, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, stateCookie("", Duration.ZERO).toString());
        try {
            if (error != null) {
                throw new OidcProvider.OidcException("The provider answered with an error: " + error);
            }
            AuthService.LoginResult result = login.finish(code, state, stateCookie, request.getRemoteAddr(),
                    request.getHeader(HttpHeaders.USER_AGENT));
            response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(result.sessionToken()).toString());
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(base() + (result.mfaRequired() ? "/login?mfa=1" : "/"))).build();
        }
        catch (OidcProvider.OidcException ex) {
            LOG.warn("OIDC sign-in refused: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(base() + "/login?error=oidc")).build();
        }
        catch (ApiException ex) {
            LOG.info("OIDC sign-in refused: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(base() + "/login?error=oidc")).build();
        }
    }
}
