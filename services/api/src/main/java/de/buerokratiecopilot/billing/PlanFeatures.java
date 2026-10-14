package de.buerokratiecopilot.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What a workspace may use: decided by two independent things, the plan it pays for (a tier: how much and how far) and
 * its cabinet (a person, a family or a company: what is relevant to it at all). A family does not see invoices
 * whatever it pays; a company on Free does not get contract control.
 *
 * <p>The interface hides what is not available, and this class is what the server holds the interface to.</p>
 */
@Component
public class PlanFeatures {

    /** One area of the API, the lowest tier that may use it and the cabinets it is meant for (null: all). */
    record Gate(String pathPrefix, int minTier, Set<String> cabinets) {
    }

    private static final Set<String> COMPANY = Set.of("company");
    private static final Set<String> FAMILY = Set.of("family");

    private static final List<Gate> GATES = List.of(
            new Gate("/api/v1/invoices", 2, COMPANY),
            new Gate("/api/v1/invoice-numbers", 2, COMPANY),
            new Gate("/api/v1/banking", 2, COMPANY),
            new Gate("/api/v1/e-invoicing", 2, COMPANY),
            new Gate("/api/v1/family", 1, FAMILY),
            new Gate("/api/v1/contracts", 1, null),
            new Gate("/api/v1/templates", 1, null),
            new Gate("/api/v1/generated-documents", 1, null),
            new Gate("/api/v1/mail", 1, null),
            new Gate("/api/v1/telegram", 1, null),
            new Gate("/api/v1/calendar/feeds", 1, null),
            new Gate("/api/v1/devices", 1, null),
            new Gate("/api/v1/printing", 1, null),
            new Gate("/api/v1/ai/assistant", 1, null),
            new Gate("/api/v1/radar", 1, null));
    // Not gated on purpose: /api/v1/audit (the history tab of every document reads it) and /api/v1/advisor (a client
    // invites its advisor whatever the client pays). The menu hides the audit screen below Professional.

    private record Cached(int tier, String cabinet, Integer analyses, Instant loadedAt) {
    }

    private static final Duration TTL = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public PlanFeatures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The cabinet an account type belongs to. */
    public static String cabinetOf(String accountType) {
        return switch (accountType == null ? "" : accountType) {
            case "PRIVATE_PERSON" -> "private";
            case "FAMILY_HOUSEHOLD" -> "family";
            default -> "company";
        };
    }

    private Cached load(UUID tenantId) {
        Cached cached = cache.get(tenantId);
        if (cached != null && cached.loadedAt().plus(TTL).isAfter(Instant.now())) {
            return cached;
        }
        List<Cached> rows = jdbc.query("""
                select coalesce(p.tier, 1), t.account_type, p.analyses_per_month
                from tenants t
                left join subscriptions s on s.tenant_id = t.id
                left join plans p on p.code = s.plan_code
                where t.id = ?
                """, (rs, i) -> new Cached(rs.getInt(1), cabinetOf(rs.getString(2)),
                (Integer) rs.getObject(3), Instant.now()), tenantId);
        // A tenant without a subscription row is not restricted (the same rule as the access gate).
        Cached loaded = rows.isEmpty() ? new Cached(3, "company", null, Instant.now()) : rows.getFirst();
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
                Cached plan = load(tenantId);
                if (gate.cabinets() != null && !gate.cabinets().contains(plan.cabinet())) {
                    return "CABINET";
                }
                if (plan.tier() < gate.minTier()) {
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
