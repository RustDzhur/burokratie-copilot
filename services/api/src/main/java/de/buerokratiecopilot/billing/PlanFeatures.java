package de.buerokratiecopilot.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What a workspace may use: the plan it pays for (a tier: how much and how far). There is one kind of cabinet — the
 * Kanzlei — so a plan is the only thing that decides what is available.
 *
 * <p>The interface hides what is not available, and this class is what the server holds the interface to.</p>
 */
@Component
public class PlanFeatures {

    /** One area of the API and the lowest tier that may use it. */
    record Gate(String pathPrefix, int minTier) {
    }

    private static final List<Gate> GATES = List.of(
            new Gate("/api/v1/invoices", 2),
            new Gate("/api/v1/invoice-numbers", 2),
            new Gate("/api/v1/e-invoicing", 2),
            new Gate("/api/v1/contracts", 1),
            new Gate("/api/v1/templates", 1),
            new Gate("/api/v1/generated-documents", 1),
            new Gate("/api/v1/mail", 1),
            new Gate("/api/v1/telegram", 1),
            new Gate("/api/v1/calendar/feeds", 1),
            new Gate("/api/v1/devices", 1),
            new Gate("/api/v1/radar", 1));
    // Not gated on purpose: /api/v1/audit (the history tab of every document reads it) and /api/v1/advisor (a client
    // invites its advisor whatever the client pays). The menu hides the audit screen below Professional.

    private record Cached(int tier, Integer analyses, Instant loadedAt) {
    }

    private static final Duration TTL = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public PlanFeatures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Cached load(UUID tenantId) {
        Cached cached = cache.get(tenantId);
        if (cached != null && cached.loadedAt().plus(TTL).isAfter(Instant.now())) {
            return cached;
        }
        List<Cached> rows = jdbc.query("""
                -- A tenant without a subscription is not restricted, as the comment below says: the left
                -- joins still produce a row for the tenant, so coalesce(p.tier, 1) silently gave every
                -- subscription-less account the lowest tier and the check below never fired. The case makes
                -- the documented rule true instead of only written down.
                select case when s.tenant_id is null then 3 else coalesce(p.tier, 1) end,
                       p.analyses_per_month
                from tenants t
                left join subscriptions s on s.tenant_id = t.id
                left join plans p on p.code = s.plan_code
                where t.id = ?
                """, (rs, i) -> new Cached(rs.getInt(1), (Integer) rs.getObject(2), Instant.now()), tenantId);
        // A tenant without a subscription row is not restricted (the same rule as the access gate).
        Cached loaded = rows.isEmpty() ? new Cached(3, null, Instant.now()) : rows.getFirst();
        cache.put(tenantId, loaded);
        return loaded;
    }

    public void forget(UUID tenantId) {
        cache.remove(tenantId);
    }

    /** Why a call to {@code path} is refused for this workspace, or null when it may go ahead. */
    public String denialFor(UUID tenantId, String path) {
        if (tenantId == null) {
            return null;
        }
        for (Gate gate : GATES) {
            if (path.equals(gate.pathPrefix()) || path.startsWith(gate.pathPrefix() + "/")) {
                if (load(tenantId).tier() < gate.minTier()) {
                    return "TIER";
                }
                return null;
            }
        }
        return null;
    }

    /** How many documents may be analysed per month, or null for no limit. */
    public Integer analysesPerMonth(UUID tenantId) {
        return load(tenantId).analyses();
    }

    public int tierOf(UUID tenantId) {
        return load(tenantId).tier();
    }
}
