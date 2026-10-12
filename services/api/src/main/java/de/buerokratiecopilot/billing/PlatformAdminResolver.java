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
