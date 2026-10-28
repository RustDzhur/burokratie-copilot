package de.buerokratiecopilot.demo.web;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.demo.DemoService;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** Enter a demo cabinet without a password, leave it again, and ask whether the current workspace is one. */
@RestController
@RequestMapping("/api/v1")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "Demo", description = "A demo cabinet filled with invented data, deleted when the visitor leaves")
public class DemoController {

    private final DemoService demo;
    private final BurokratieProperties properties;

    public DemoController(DemoService demo, BurokratieProperties properties) {
        this.demo = demo;
        this.properties = properties;
    }

    public record StartRequest(String persona) {
    }

    @PostMapping("/public/demo/start")
    @Operation(summary = "Open a demo cabinet for the chosen purpose and sign in to it (public)")
    public Map<String, Object> start(@RequestBody StartRequest request, HttpServletRequest http, HttpServletResponse response) {
        DemoService.Started started = demo.start(request == null ? null : request.persona(), http.getRemoteAddr(),
                http.getHeader(HttpHeaders.USER_AGENT));
        BurokratieProperties.Security.Session session = properties.getSecurity().getSession();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(session.getCookieName(), started.sessionToken())
                .httpOnly(true).secure(session.isCookieSecure()).sameSite(session.getCookieSameSite()).path("/")
                .maxAge(Duration.ofHours(4)).build().toString());
        return Map.of("persona", started.persona(), "next", "/dashboard?tour=1");
    }

    @GetMapping("/demo/status")
    @Operation(summary = "Whether the current workspace is a demo cabinet, and when it ends")
    public Map<String, Object> status() {
        return demo.status(RequestContext.tenantId().orElse(null));
    }

    @PostMapping("/demo/exit")
    @Operation(summary = "Leave the demo: everything that was created in it is deleted")
    public Map<String, Object> exit(HttpServletResponse response) {
        UUID user = CurrentUser.required();
        demo.exitAll(user);
        BurokratieProperties.Security.Session session = properties.getSecurity().getSession();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(session.getCookieName(), "").httpOnly(true)
                .secure(session.isCookieSecure()).sameSite(session.getCookieSameSite()).path("/").maxAge(Duration.ZERO)
                .build().toString());
        return Map.of("left", true);
    }
}
