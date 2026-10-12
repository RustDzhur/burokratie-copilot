package de.buerokratiecopilot.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The subscription of a tenant: which plan, how long access lasts, and whether access is blocked now.
 *
 * <p><strong>One date decides.</strong> {@code access_until} is the only thing the gate looks at. The free trial
 * sets it to the end of the trial, every payment moves it forward, and a platform administrator can move it by
 * hand. {@code status} is a label for people (trial, paid, expired, suspended); a suspended tenant is blocked
 * whatever the date says.</p>
 *
 * <p>The gate runs on every request, so the answer is cached for a short time per tenant. A change made here
 * clears the entry on this instance at once; another instance sees it after the cache has aged out.</p>
 */
@Service
public class SubscriptionService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(20);

    private final JdbcTemplate jdbc;
    private final PlatformSettings settings;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    public SubscriptionService(JdbcTemplate jdbc, PlatformSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    private record Cached(boolean blocked, Instant loadedAt) {
    }

    /** The plan that belongs to an account type chosen at registration. */
    public static String planFor(String accountType) {
        return switch (accountType == null ? "" : accountType) {
            case "PRIVATE_PERSON" -> "PRIVATE";
            case "FAMILY_HOUSEHOLD" -> "FAMILY";
            default -> "COMPANY";
        };
    }

    /** Starts the free trial of a new tenant. */
    @Transactional
    public void startTrial(UUID tenantId, String accountType) {
        Instant end = Instant.now().plus(settings.trialDays(), ChronoUnit.DAYS);
        jdbc.update("""
                insert into subscriptions (tenant_id, plan_code, status, trial_ends_at, access_until)
                values (?, ?, 'TRIAL', ?, ?) on conflict (tenant_id) do nothing
                """, tenantId, planFor(accountType), java.sql.Timestamp.from(end), java.sql.Timestamp.from(end));
    }

    /** True when this tenant may not use the platform right now. A tenant without a row is not blocked. */
    public boolean isBlocked(UUID tenantId) {
        if (tenantId == null) {
            return false;
        }
        Cached cached = cache.get(tenantId);
        if (cached != null && cached.loadedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.blocked();
        }
        List<Boolean> rows = jdbc.query("""
                select (status = 'SUSPENDED' or access_until < now()) from subscriptions where tenant_id = ?
                """, (rs, i) -> rs.getBoolean(1), tenantId);
        boolean blocked = !rows.isEmpty() && rows.getFirst();
        cache.put(tenantId, new Cached(blocked, Instant.now()));
        return blocked;
    }

    /** What the interface shows about the subscription of a tenant. */
    @Transactional(readOnly = true)
    public Map<String, Object> view(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select s.plan_code, p.name as plan_name, p.price_cents, p.currency, s.status, s.trial_ends_at,
                       s.paid_until, s.access_until, s.retention_until, s.data_choice,
                       (s.status = 'SUSPENDED' or s.access_until < now()) as blocked,
                       greatest(0, ceil(extract(epoch from (s.access_until - now())) / 86400))::int as days_left
                from subscriptions s join plans p on p.code = s.plan_code where s.tenant_id = ?
                """, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("subscription");
        }
        Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
        row.put("retention_days", settings.retentionDays());
        return row;
    }

    @Transactional
    public void changePlan(UUID tenantId, String planCode) {
        Integer known = jdbc.queryForObject("select count(*) from plans where code = ? and is_active", Integer.class,
                planCode);
        if (known == null || known == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Tarif: " + planCode);
        }
        jdbc.update("update subscriptions set plan_code = ? where tenant_id = ?", planCode, tenantId);
    }

    /** Adds paid time. Time left over from the trial or a previous payment is kept, not thrown away. */
    @Transactional
    public void extend(UUID tenantId, int months) {
        jdbc.update("""
                update subscriptions set
                    access_until = greatest(access_until, now()) + make_interval(months => ?),
                    paid_until = greatest(coalesce(paid_until, access_until), now()) + make_interval(months => ?),
                    status = 'ACTIVE', retention_until = null, data_choice = null, data_choice_at = null,
                    reminders_sent = '{}'::jsonb
                where tenant_id = ?
                """, months, months, tenantId);
        cache.remove(tenantId);
    }

    /** Gives free days (support case, goodwill); also reopens an expired tenant. */
    @Transactional
    public void grantDays(UUID tenantId, int days) {
        jdbc.update("""
                update subscriptions set
                    access_until = greatest(access_until, now()) + make_interval(days => ?),
                    status = case when status = 'SUSPENDED' then status else 'TRIAL' end,
                    retention_until = null, data_choice = null, data_choice_at = null, reminders_sent = '{}'::jsonb
                where tenant_id = ?
                """, days, tenantId);
        cache.remove(tenantId);
    }

    @Transactional
    public void setSuspended(UUID tenantId, boolean suspended) {
        jdbc.update("""
                update subscriptions set status = case
                    when ? then 'SUSPENDED'
                    when paid_until is not null and paid_until >= now() then 'ACTIVE'
                    when access_until < now() then 'EXPIRED' else 'TRIAL' end
                where tenant_id = ?
                """, suspended, tenantId);
        cache.remove(tenantId);
    }

    /** Records what the customer decided about the data after the access ended. */
    @Transactional
    public void recordDataChoice(UUID tenantId, String choice) {
        if (!"KEEP".equals(choice) && !"DELETE".equals(choice)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Auswahl muss KEEP oder DELETE sein");
        }
        int changed = jdbc.update("""
                update subscriptions set data_choice = ?, data_choice_at = now()
                where tenant_id = ? and status = 'EXPIRED'
                """, choice, tenantId);
        if (changed == 0) {
            throw ApiException.conflict("Die Datenauswahl ist nur möglich, wenn der Zugang abgelaufen ist");
        }
    }

    /** Test and admin hook: forget what is cached for a tenant. */
    public void forget(UUID tenantId) {
        cache.remove(tenantId);
    }
}
