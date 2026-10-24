package de.buerokratiecopilot.calendar.caldav;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.calendar.caldav.CalDavClient.CalendarInfo;
import de.buerokratiecopilot.calendar.caldav.CalDavClient.Connection;
import de.buerokratiecopilot.integrations.CredentialCipher;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The CalDAV connection of one person: connect (with an access check), disconnect, status, and the
 * calendar list an account offers (the step before connecting).
 *
 * <p><strong>The app password is stored like a mailbox password.</strong> The same {@link CredentialCipher}
 * encrypts it before it reaches the database, bound to the tenant and the connection id, and no endpoint
 * ever returns it. The password exists in plaintext only in the request and, transiently, in memory
 * while a CalDAV call runs.</p>
 *
 * <p><strong>Connecting proves access first.</strong> The calendar path is checked with a PROPFIND before
 * the row is saved, so a connection whose credentials or path do not work is never presented as
 * connected. A 401 becomes the honest message "the app password is wrong", not a generic failure.</p>
 */
@Service
public class CalDavConnectionService {

    private static final Logger LOG = LoggerFactory.getLogger(CalDavConnectionService.class);

    /** The CalDAV root of iCloud, when nobody names another server. */
    public static final String ICLOUD_SERVER_URL = "https://caldav.icloud.com";

    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final CalDavClient client;

    public CalDavConnectionService(JdbcTemplate jdbc, CredentialCipher cipher, CalDavClient client) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.client = client;
    }

    /**
     * What the interface shows about the connection — deliberately without any secret.
     *
     * <p>{@code lastSyncStatus} is SUCCESS, PARTIAL or FAILED of the most recent run; {@code error} is
     * the last message, in the language of the platform.</p>
     */
    public record Status(UUID id, String provider, String account, String serverUrl, String calendarPath,
                         String calendarName, String status, String lastSyncAt, String lastSyncStatus,
                         String error) {

        public boolean connected() {
            return status != null && !"ERROR".equals(status);
        }
    }

    /** What a successful connection answered. */
    public record Connected(UUID id, Status status) {
    }

    /** Whether anything is stored; the answer never says what it was. */
    public Status connect(UUID tenantId, UUID userId, String provider, String account, String appPassword,
                          String serverUrl, String calendarPath, String calendarName) {
        requireCredentials(provider, account, appPassword);
        String server = normalizeServerUrl(provider, serverUrl);
        requireCalendarPath(calendarPath);

        // The access check runs before anything is stored: a wrong password must never leave a row that
        // pretends to be connected.
        Connection probe = new Connection(server, calendarPath, account.strip(), appPassword);
        try {
            client.verify(probe);
        }
        catch (CalDavException ex) {
            throw asApiException(ex);
        }

        // The id is chosen first, because the ciphertext is bound to it (see CredentialCipher).
        UUID id = existingActive(tenantId, userId).orElseGet(UUID::randomUUID);
        String encrypted = cipher.encrypt(appPassword, tenantId, id);
        int changed = jdbc.update("""
                update calendar_connections
                set provider = ?, account = ?, server_url = ?, calendar_path = ?, calendar_name = ?,
                    app_password_encrypted = ?, status = 'CONNECTED', error = null,
                    consecutive_failures = 0
                where id = ? and tenant_id = ? and user_id = ? and deleted_at is null
                """, provider, account.strip(), server, calendarPath, calendarName,
                encrypted, id, tenantId, userId);
        if (changed == 0) {
            jdbc.update("""
                    insert into calendar_connections (id, tenant_id, user_id, provider, account, server_url,
                                                      calendar_path, calendar_name, app_password_encrypted,
                                                      status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONNECTED')
                    """, id, tenantId, userId, provider, account.strip(), server, calendarPath,
                    calendarName, encrypted);
        }

        LOG.info("CalDAV connection {} stored for tenant {}", id, tenantId);
        return status(tenantId, userId);
    }

    /** What calendars an account offers (PROPFIND), before a connection exists. */
    public List<CalendarInfo> discover(UUID tenantId, String provider, String account, String appPassword,
                                       String serverUrl) {
        requireCredentials(provider, account, appPassword);
        String server = normalizeServerUrl(provider, serverUrl);
        Connection probe = new Connection(server, "/", account.strip(), appPassword);
        try {
            return client.listCalendars(probe);
        }
        catch (CalDavException ex) {
            throw asApiException(ex);
        }
    }

    /** The current status, or a not-connected answer. */
    @Transactional(readOnly = true)
    public Status status(UUID tenantId, UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, provider, account, server_url, calendar_path, calendar_name, status,
                       last_sync_at::text as last_sync_at, last_sync_status, error
                from calendar_connections
                where tenant_id = ? and user_id = ? and deleted_at is null
                order by created_at desc limit 1
                """, tenantId, userId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.getFirst();
        return new Status((UUID) row.get("id"), String.valueOf(row.get("provider")),
                String.valueOf(row.get("account")), String.valueOf(row.get("server_url")),
                String.valueOf(row.get("calendar_path")), str(row.get("calendar_name")),
                String.valueOf(row.get("status")), str(row.get("last_sync_at")),
                str(row.get("last_sync_status")), str(row.get("error")));
    }

    /** Disconnects: the row stays for the history, the password is never touched again by the API. */
    @Transactional
    public void disconnect(UUID tenantId, UUID userId) {
        int changed = jdbc.update("""
                update calendar_connections set deleted_at = now(), status = 'DISABLED'
                where tenant_id = ? and user_id = ? and deleted_at is null
                """, tenantId, userId);
        if (changed == 0) {
            throw ApiException.notFound("calendar connection");
        }
    }

    /**
     * The active connection as the CalDAV client needs it, with the password decrypted for this call
     * only. Empty when no connection exists.
     */
    @Transactional(readOnly = true)
    public Optional<Connection> activeConnection(UUID tenantId, UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, server_url, calendar_path, account, app_password_encrypted
                from calendar_connections
                where tenant_id = ? and user_id = ? and deleted_at is null
                  and status in ('CONNECTED', 'ERROR')
                order by created_at desc limit 1
                """, tenantId, userId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        UUID id = (UUID) row.get("id");
        try {
            String password = cipher.decrypt(String.valueOf(row.get("app_password_encrypted")),
                    tenantId, id);
            return Optional.of(new Connection(String.valueOf(row.get("server_url")),
                    String.valueOf(row.get("calendar_path")), String.valueOf(row.get("account")),
                    password));
        }
        catch (CredentialCipher.CredentialCipherException ex) {
            LOG.error("CalDAV connection {}: stored password cannot be decrypted", id, ex);
            return Optional.empty();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Storage and validation                                             */
    /* ------------------------------------------------------------------ */

    private Optional<UUID> existingActive(UUID tenantId, UUID userId) {
        return jdbc.query("""
                select id from calendar_connections
                where tenant_id = ? and user_id = ? and deleted_at is null
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, userId).stream()
                .findFirst();
    }

    private static void requireCredentials(String provider, String account, String appPassword) {
        if (provider == null || !List.of("icloud", "caldav").contains(provider)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Anbieter muss 'icloud' oder 'caldav' sein");
        }
        if (account == null || account.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Apple-ID (das Konto) fehlt");
        }
        if (appPassword == null || appPassword.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Das App-Passwort fehlt");
        }
    }

    private static void requireCalendarPath(String calendarPath) {
        if (calendarPath == null || calendarPath.isBlank() || !calendarPath.startsWith("/")) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Kalenderpfad muss mit '/' beginnen");
        }
    }

    private static String normalizeServerUrl(String provider, String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            if ("icloud".equals(provider)) {
                return ICLOUD_SERVER_URL;
            }
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Für einen generischen CalDAV-Server wird die Serveradresse benötigt");
        }
        URI uri = URI.create(serverUrl.strip());
        if (uri.getHost() == null || !List.of("https", "http").contains(uri.getScheme())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Die Serveradresse muss mit https:// beginnen");
        }
        return serverUrl.strip().replaceAll("/+$", "");
    }

    /**
     * The interface-safe message for a CalDAV failure; never leaks the password.
     *
     * <p>A wrong app password is a 400, not a 401: the caller's session is valid, only the supplied
     * credential is not, and a 401 would log the person out of the platform.</p>
     */
    static ApiException asApiException(CalDavException ex) {
        return switch (ex.code()) {
            case AUTH_FAILED -> new ApiException(ErrorCode.INVALID_REQUEST,
                    "Das App-Passwort oder das Konto ist nicht korrekt");
            case NOT_FOUND -> new ApiException(ErrorCode.NOT_FOUND,
                    "Der Kalender wurde auf dem Server nicht gefunden");
            case PROTOCOL -> new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "Der Server antwortet nicht als Kalenderserver");
            case NETWORK -> new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "Der Kalenderserver ist nicht erreichbar");
            case SERVER -> new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE, ex.getMessage());
        };
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
