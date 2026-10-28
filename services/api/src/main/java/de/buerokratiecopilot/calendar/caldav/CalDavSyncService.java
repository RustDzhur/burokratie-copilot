package de.buerokratiecopilot.calendar.caldav;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.calendar.CalendarFeedService;
import de.buerokratiecopilot.calendar.IcsEventWriter;
import de.buerokratiecopilot.calendar.caldav.CalDavClient.Connection;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Pushes the platform's calendar events into a connected external calendar (CalDAV / iCloud).
 *
 * <p><strong>Best effort, like mail.</strong> An event is stored locally first; the push happens
 * afterwards on its own thread and a failure never touches the local event. The connection row carries
 * the honest result: when the last push failed, why, and when.</p>
 *
 * <p><strong>Events are found by UID.</strong> Every pushed event keeps the feed's UID
 * ({@code <id>@copilot}) and a matching file name, so a push is a create-or-replace and a deleted local
 * event can be deleted remotely by name. The manual sync therefore pushes the current set and removes
 * remote leftovers of our own events that no longer exist locally — never anything else.</p>
 */
@Service
public class CalDavSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(CalDavSyncService.class);

    private final JdbcTemplate jdbc;
    private final CalDavConnectionService connections;
    private final CalDavClient client;
    private final String baseUrl;

    /** One thread per push, so a slow CalDAV server never blocks a request. Virtual threads, JDK 21. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public CalDavSyncService(JdbcTemplate jdbc, CalDavConnectionService connections, CalDavClient client,
                             @Value("${burokratie.security.public-base-url:http://localhost:3000}")
                             String baseUrl) {
        this.jdbc = jdbc;
        this.connections = connections;
        this.client = client;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** What a manual sync did. */
    public record SyncResult(int pushed, int deletedRemote, int failed, String status) {
    }

    /**
     * Hooks into {@link CalendarEventService}: after an event was stored, the same event goes to the
     * external calendar. Fires and forgets; never throws back into the caller.
     *
     * <p>The owner is passed in, not read back: the hook runs inside the creating transaction and the
     * row is not visible to another thread before the commit.</p>
     */
    public void afterCreated(UUID tenantId, UUID userId, Map<String, Object> event) {
        if (userId == null) {
            return; // system-created entry with no owner — no personal connection to push into
        }
        submit(tenantId, () -> push(tenantId, userId, event));
    }

    /** Hooks into deletion: after an event was deleted locally, its remote twin goes with it. */
    public void afterDeleted(UUID tenantId, UUID eventId) {
        submit(tenantId, () -> remove(tenantId, eventId));
    }

    /**
     * The manual "sync now": pushes every current event and removes remote leftovers of our own deleted
     * events. Runs in the caller's thread, because the person waits for the answer.
     *
     * @param events what the person sees in the platform calendar (deadlines, appointments, reminders)
     */
    public SyncResult syncNow(UUID tenantId, UUID userId, List<Map<String, Object>> events) {
        Optional<Connection> connection = connections.activeConnection(tenantId, userId);
        if (connection.isEmpty()) {
            throw ApiException.notFound("calendar connection");
        }
        List<Map<String, Object>> marked = CalendarFeedService.withConfidentialFlags(jdbc, events);

        Set<String> localFiles = new HashSet<>();
        for (Map<String, Object> event : marked) {
            localFiles.add(IcsEventWriter.eventFileName(event));
        }

        int pushed = 0;
        int deleted = 0;
        int failed = 0;
        String firstError = null;

        try {
            for (CalDavClient.Resource resource : client.listResources(connection.get())) {
                String name = baseName(resource.href());
                if (name == null || !name.endsWith("@copilot.ics")) {
                    continue; // not ours — never touch other people's entries
                }
                if (!localFiles.contains(name)) {
                    try {
                        client.deleteEvent(connection.get(), name);
                        deleted++;
                    }
                    catch (CalDavException ex) {
                        failed++;
                        if (firstError == null) {
                            firstError = ex.getMessage();
                        }
                    }
                }
            }

            for (Map<String, Object> event : marked) {
                String fileName = IcsEventWriter.eventFileName(event);
                String ics = IcsEventWriter.render(event, "de", baseUrl);
                try {
                    client.putEvent(connection.get(), fileName, ics);
                    pushed++;
                }
                catch (CalDavException ex) {
                    failed++;
                    if (firstError == null) {
                        firstError = ex.getMessage();
                    }
                }
            }
        }
        catch (CalDavException ex) {
            failed++;
            firstError = ex.getMessage();
        }

        recordOutcome(tenantId, userId, pushed + deleted, failed, firstError);
        return new SyncResult(pushed, deleted, failed,
                failed == 0 ? "SUCCESS" : pushed + deleted > 0 ? "PARTIAL" : "FAILED");
    }

    /** One event to the external calendar, with the connection's result recorded. */
    private void push(UUID tenantId, UUID userId, Map<String, Object> event) {
        RequestContext.runAs(tenantId, () -> {
            Optional<Connection> connection = connections.activeConnection(tenantId, userId);
            if (connection.isEmpty()) {
                return; // no external calendar connected — nothing to do, nothing to record
            }
            List<Map<String, Object>> marked = CalendarFeedService.withConfidentialFlags(jdbc, List.of(event));
            String fileName = IcsEventWriter.eventFileName(marked.getFirst());
            String ics = IcsEventWriter.render(marked.getFirst(), "de", baseUrl);
            try {
                client.putEvent(connection.get(), fileName, ics);
                recordOutcome(tenantId, userId, 1, 0, null);
            }
            catch (CalDavException ex) {
                LOG.warn("CalDAV push of {} failed: {}", fileName, ex.getMessage());
                recordOutcome(tenantId, userId, 0, 1, ex.getMessage());
            }
        });
    }

    /** One deleted event, removed from the external calendar. */
    private void remove(UUID tenantId, UUID eventId) {
        RequestContext.runAs(tenantId, () -> {
            // The delete hook knows only the event's own id, not its owner; the connection is per person,
            // so the owner is read from the event row (the soft-deleted row is still there).
            List<UUID> owners = jdbc.query("""
                    select created_by from calendar_events where id = ? and tenant_id = ?
                    """, (rs, row) -> rs.getObject("created_by", UUID.class), eventId, tenantId);
            if (owners.isEmpty() || owners.getFirst() == null) {
                return;
            }
            UUID userId = owners.getFirst();
            Optional<Connection> connection = connections.activeConnection(tenantId, userId);
            if (connection.isEmpty()) {
                return;
            }
            String fileName = eventId + "@copilot.ics";
            try {
                client.deleteEvent(connection.get(), fileName);
                recordOutcome(tenantId, userId, 1, 0, null);
            }
            catch (CalDavException ex) {
                LOG.warn("CalDAV delete of {} failed: {}", fileName, ex.getMessage());
                recordOutcome(tenantId, userId, 0, 1, ex.getMessage());
            }
        });
    }

    /** Writes the result of a run into the connection row; the interface reads it back. */
    private void recordOutcome(UUID tenantId, UUID userId, int successes, int failures, String error) {
        if (failures == 0) {
            jdbc.update("""
                    update calendar_connections
                    set last_sync_at = now(), last_sync_status = 'SUCCESS', error = null,
                        status = 'CONNECTED', consecutive_failures = 0
                    where tenant_id = ? and user_id = ? and deleted_at is null
                    """, tenantId, userId);
            return;
        }
        if (successes > 0) {
            jdbc.update("""
                    update calendar_connections
                    set last_sync_at = now(), last_sync_status = 'PARTIAL', error = ?,
                        status = 'CONNECTED', consecutive_failures = consecutive_failures + ?
                    where tenant_id = ? and user_id = ? and deleted_at is null
                    """, error, failures, tenantId, userId);
            return;
        }
        jdbc.update("""
                update calendar_connections
                set last_sync_at = now(), last_sync_status = 'FAILED', error = ?, status = 'ERROR',
                    consecutive_failures = consecutive_failures + ?
                where tenant_id = ? and user_id = ? and deleted_at is null
                """, error, failures, tenantId, userId);
    }

    private void submit(UUID tenantId, Runnable task) {
        try {
            executor.submit(() -> {
                try {
                    task.run();
                }
                catch (RuntimeException ex) {
                    // Best effort by contract: a failing push must never reach the caller's request.
                    LOG.error("CalDAV sync task failed for tenant {}", tenantId, ex);
                }
            });
        }
        catch (RuntimeException ex) {
            LOG.error("CalDAV sync task could not be scheduled for tenant {}", tenantId, ex);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** The file name part of a href, URL-decoded. */
    static String baseName(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String trimmed = href.replaceAll("/+$", "");
        int slash = trimmed.lastIndexOf('/');
        return URLDecoder.decode(slash >= 0 ? trimmed.substring(slash + 1) : trimmed,
                StandardCharsets.UTF_8);
    }

}
