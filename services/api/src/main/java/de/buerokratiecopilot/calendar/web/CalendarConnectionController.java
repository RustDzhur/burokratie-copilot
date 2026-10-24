package de.buerokratiecopilot.calendar.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.calendar.caldav.CalDavClient.CalendarInfo;
import de.buerokratiecopilot.calendar.caldav.CalDavConnectionService;
import de.buerokratiecopilot.calendar.caldav.CalDavSyncService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The CalDAV connection of the caller: connect an external calendar (iCloud with an app-specific
 * password, or a generic CalDAV server), see its status, sync now, disconnect.
 *
 * <p><strong>The password goes in and never comes back out.</strong> It is needed for connecting and for
 * the calendar discovery, and every answer here omits it entirely.</p>
 *
 * <p><strong>Connecting proves access.</strong> The calendar path is checked against the server before
 * the row is saved; a wrong password is answered with the honest message, not a saved connection that
 * fails later.</p>
 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/calendar/connection")
@Tag(name = "Calendar connection", description = "A CalDAV connection to an external calendar")
public class CalendarConnectionController {

    private static final String SEE = "hasAnyAuthority('tasks.view','cases.view')";

    private final CalDavConnectionService connections;
    private final CalDavSyncService sync;
    private final CalendarEventService events;

    public CalendarConnectionController(CalDavConnectionService connections, CalDavSyncService sync,
                                        CalendarEventService events) {
        this.connections = connections;
        this.sync = sync;
        this.events = events;
    }

    /** The credentials for discovery and connecting; nothing here is ever returned. */
    public record ConnectBody(String provider, String account, String appPassword, String serverUrl,
                              String calendarPath, String calendarName) {
    }

    /** The status of the caller's connection, or {@code null} when there is none. */
    @GetMapping
    @PreAuthorize(SEE)
    @Operation(summary = "The status of the caller's external calendar connection (never the password)")
    public CalDavConnectionService.Status status() {
        return connections.status(RequestContext.tenantId().orElseThrow(), CurrentUser.required());
    }

    /**
     * Connects an external calendar. The access check (PROPFIND on the given path) runs before the row is
     * saved, so a connection that does not work is never presented as connected.
     */
    @PostMapping
    @PreAuthorize(SEE)
    @Operation(summary = "Connects an external calendar after checking that the credentials work")
    public ResponseEntity<CalDavConnectionService.Status> connect(@RequestBody ConnectBody body) {
        CalDavConnectionService.Status status = connections.connect(RequestContext.tenantId().orElseThrow(),
                CurrentUser.required(), body.provider(), body.account(), body.appPassword(),
                body.serverUrl(), body.calendarPath(), body.calendarName());
        return ResponseEntity.status(HttpStatus.CREATED).body(status);
    }

    /** Disconnects; the external calendar stays as it is, only the login is removed here. */
    @DeleteMapping
    @PreAuthorize(SEE)
    @Operation(summary = "Disconnects the external calendar")
    public ResponseEntity<Void> disconnect() {
        connections.disconnect(RequestContext.tenantId().orElseThrow(), CurrentUser.required());
        return ResponseEntity.noContent().build();
    }

    /** The calendars the account offers, so the person can pick one before connecting. */
    @PostMapping("/discover")
    @PreAuthorize(SEE)
    @Operation(summary = "Lists the calendars of the account (the password is used once and not stored)")
    public List<CalendarInfo> discover(@RequestBody ConnectBody body) {
        return connections.discover(RequestContext.tenantId().orElseThrow(), body.provider(),
                body.account(), body.appPassword(), body.serverUrl());
    }

    /**
     * Pushes the current calendar to the external one and removes remote leftovers of deleted entries.
     * The answer says how many events were pushed, removed and failed.
     */
    @PostMapping("/sync")
    @PreAuthorize(SEE)
    @Operation(summary = "Syncs the platform calendar into the external calendar now")
    public CalDavSyncService.SyncResult syncNow() {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        UUID userId = CurrentUser.required();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/Berlin"));
        List<Map<String, Object>> current = events.events(tenantId, userId, false, today.minusDays(7),
                today.plusDays(390));
        return sync.syncNow(tenantId, userId, current);
    }
}
