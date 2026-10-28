package de.buerokratiecopilot.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator page of an API-only runtime installation.
 *
 * <p>The runtime has no web cabinet, and installing a licence or issuing the first bridge token
 * through {@code curl} is exactly the kind of command-line work the installer should not have to
 * do. This controller serves a single static page; every action on it calls the same
 * {@code /api/v1/runtime/**} endpoints that are protected by {@code X-Runtime-Admin-Token}. The
 * page itself carries no credential and no tenant data, so it is safe to serve without a session
 * (and it must be: there is no session in a runtime installation).</p>
 *
 * <p>The page only exists when the runtime admin API exists, so a full-product deployment does
 * not grow an orphan surface.</p>
 */
@RestController
@ConditionalOnWebApplication(
        type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "burokratie.runtime", name = "enabled", havingValue = "true")
public class RuntimeAdminPageController {

    private static final String PAGE = "static/runtime-admin/index.html";

    private static final MediaType HTML_UTF8 =
            new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8);

    /**
     * The HTML entry point. Assets are served by Spring Boot's default static resource handler at
     * {@code /runtime-admin/**}; the page is deliberately never cached, because it is the only way
     * an operator receives a fixed UI without a release note.
     */
    @GetMapping({"/runtime-admin", "/runtime-admin/"})
    public ResponseEntity<Resource> page() {
        Resource page = new ClassPathResource(PAGE);
        if (!page.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .cacheControl(CacheControl.noCache())
                .body(page);
    }
}
