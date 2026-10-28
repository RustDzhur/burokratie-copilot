package de.buerokratiecopilot.calendar.web;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.calendar.CalendarFeedService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The private calendar address a phone subscribes to, and where a person creates and revokes it.
 *
 * <p>Two different doors: the management endpoints need a login like everything else; the feed itself is opened by a
 * phone that cannot log in, so it is public in the security configuration and is authenticated by the secret in its own
 * path. A wrong, revoked or malformed secret is answered with the same 404, so nothing is learned from the answer.</p>
 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/calendar")
@Tag(name = "Calendar feed", description = "A private iCalendar address for the phone's calendar app")
public class CalendarFeedController {

    private static final String SEE = "hasAnyAuthority('tasks.view','cases.view')";

    private final CalendarFeedService feeds;

    public CalendarFeedController(CalendarFeedService feeds) {
        this.feeds = feeds;
    }

    public record CreateFeedRequest(String label, String locale) {
    }

    @GetMapping("/feeds")
    @PreAuthorize(SEE)
    @Operation(summary = "The calendar addresses of the caller (without their secrets)")
    public List<CalendarFeedService.Feed> list() {
        return feeds.list(RequestContext.tenantId().orElseThrow(), CurrentUser.required());
    }

    @PostMapping("/feeds")
    @PreAuthorize(SEE)
    @Operation(summary = "Creates a calendar address; the secret is in the answer once and never again")
    public ResponseEntity<CalendarFeedService.Created> create(@RequestBody CreateFeedRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(feeds.create(RequestContext.tenantId().orElseThrow(),
                CurrentUser.required(), request.label(), request.locale()));
    }

    @DeleteMapping("/feeds/{id}")
    @PreAuthorize(SEE)
    @Operation(summary = "Revokes a calendar address: it answers 404 from then on")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        feeds.revoke(RequestContext.tenantId().orElseThrow(), CurrentUser.required(), id);
        return ResponseEntity.noContent().build();
    }

    /** Public: authenticated by the secret in the path ({@code <secret>.ics}). */
    @GetMapping("/feed/{file}")
    @Operation(summary = "The iCalendar feed of one address")
    public ResponseEntity<byte[]> feed(@PathVariable String file) {
        String token = file.endsWith(".ics") ? file.substring(0, file.length() - 4) : file;
        return feeds.authenticate(token)
                .map(owner -> ResponseEntity.ok()
                        .contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"calendar.ics\"")
                        // The phone asks again on its own; a proxy must not keep one person's feed for another.
                        .cacheControl(CacheControl.noStore())
                        .body(feeds.feedOf(owner).getBytes(StandardCharsets.UTF_8)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
