package de.buerokratiecopilot.demo;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Knows which workspaces are demo cabinets and what a demo cabinet may not do. A demo is open to anybody without a password, so
 * everything that reaches the outside world (mail, integrations, payments), changes the way in (password, tokens) or belongs to
 * the operator stays closed in it.
 */
@Component
public class DemoGuard {

    /** Areas that are closed in a demo cabinet. */
    static final List<String> CLOSED = List.of("/api/v1/mail", "/api/v1/integrations", "/api/v1/telegram", "/api/v1/billing",
            "/api/v1/admin", "/api/v1/advisor", "/api/v1/privacy", "/api/v1/settings/security", "/api/v1/tokens",
            "/api/v1/auth/oidc", "/api/v1/auth/mfa", "/api/v1/mfa", "/api/v1/e-invoicing/send", "/api/v1/outbound");

    /** How many documents one demo cabinet may hold, so that a visitor cannot fill the server. */
    public static final int MAX_DOCUMENTS = 60;

    private record Entry(boolean demo, long loadedAt) {
    }

    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;

    public DemoGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isDemo(UUID tenantId) {
        if (tenantId == null) {
            return false;
        }
        Entry entry = cache.get(tenantId);
        if (entry != null && System.currentTimeMillis() - entry.loadedAt() < 60_000) {
            return entry.demo();
        }
        List<Boolean> rows = jdbc.query("select coalesce(settings ->> 'demo', 'false') = 'true' from tenants where id = ?",
                (rs, i) -> rs.getBoolean(1), tenantId);
        boolean demo = !rows.isEmpty() && rows.getFirst();
        cache.put(tenantId, new Entry(demo, System.currentTimeMillis()));
        return demo;
    }

    public void forget(UUID tenantId) {
        cache.remove(tenantId);
    }

    /** The reason a path is closed in a demo cabinet, or null. */
    public String denialFor(UUID tenantId, String path) {
        if (!isDemo(tenantId)) {
            return null;
        }
        for (String prefix : CLOSED) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return de.buerokratiecopilot.identity.security.AccessPolicy.DEMO_CLOSED + "In der Demo nicht verfügbar: Dieser Bereich verändert Zugangsdaten, versendet nach außen oder gehört dem Betreiber.";
            }
        }
        return null;
    }
}
