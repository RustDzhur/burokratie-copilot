package de.buerokratiecopilot.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Decides who is a platform administrator.
 *
 * <p>Only an address listed in the server configuration ({@code BC_PLATFORM_ADMINS}) <em>and</em> verified by the
 * e-mail link counts. There is no way to become one through the interface or by registering with a chosen
 * address, because the unverified address of a stranger never matches.</p>
 */
@Component
public class PlatformAdminResolver {

    public static final String AUTHORITY = "platform.admin";
    private static final Duration TTL = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final Set<String> admins;
    private final Map<UUID, Map.Entry<Boolean, Instant>> cache = new ConcurrentHashMap<>();

    public PlatformAdminResolver(JdbcTemplate jdbc, @Value("${burokratie.platform-admins:}") String configured) {
        this.jdbc = jdbc;
        this.admins = Arrays.stream(configured.split("[,;\\s]+"))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Whether a workspace belongs to a platform administrator. The administrator's own workspace never expires, is never
     * suspended and is never deleted: losing it would lock the platform's owner out of the platform.
     */
    public boolean protectsTenant(UUID tenantId) {
        if (admins.isEmpty() || tenantId == null) {
            return false;
        }
        Integer found = jdbc.queryForObject("""
                select count(*) from memberships m join users u on u.id = m.user_id
                where m.tenant_id = ? and m.status = 'ACTIVE' and u.email_verified_at is not null
                  and lower(u.email::text) = any (?)
                """, Integer.class, tenantId, (Object) admins.toArray(new String[0]));
        return found != null && found > 0;
    }

    /** Sets the subscriptions of the administrators' workspaces to "no end". Called at start and every hour. */
    public int keepAdminWorkspacesOpen() {
        if (admins.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                update subscriptions s set status = 'ACTIVE', access_until = now() + interval '100 years',
                       retention_until = null, data_choice = null, data_choice_at = null
                where s.tenant_id in (
                    select m.tenant_id from memberships m join users u on u.id = m.user_id
                    where m.status = 'ACTIVE' and u.email_verified_at is not null and lower(u.email::text) = any (?))
                  and (s.status <> 'ACTIVE' or s.access_until < now() + interval '50 years')
                """, (Object) admins.toArray(new String[0]));
    }

    public boolean isPlatformAdmin(UUID userId) {
        if (admins.isEmpty() || userId == null) {
            return false;
        }
        var cached = cache.get(userId);
        if (cached != null && cached.getValue().plus(TTL).isAfter(Instant.now())) {
            return cached.getKey();
        }
        Boolean result = jdbc.query("""
                select lower(email::text) as email, email_verified_at is not null as verified
                from users where id = ? and status = 'ACTIVE'
                """, rs -> rs.next() && rs.getBoolean("verified") && admins.contains(rs.getString("email")), userId);
        boolean admin = Boolean.TRUE.equals(result);
        cache.put(userId, Map.entry(admin, Instant.now()));
        return admin;
    }
}
