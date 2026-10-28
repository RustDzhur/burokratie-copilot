package de.buerokratiecopilot.publicstats;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The numbers on the landing page: what the platform really holds, counted from the database across every customer workspace.
 * Only totals leave this class, never a title, a name or a workspace; demo cabinets are not counted, because their data is invented.
 * The answer is kept for a minute so that the public page cannot be used to make the database count all day.
 */
@RestController
@RequestMapping("/api/v1/public/stats")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "Public", description = "Totals for the public landing page")
public class PublicStatsController {

    private static final long KEEP_MILLIS = 60_000;

    private record Cached(Map<String, Object> value, long at) {
    }

    private final JdbcTemplate jdbc;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public PublicStatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    @Operation(summary = "Totals of documents, cases and completed tasks of real (not demo) workspaces")
    public Map<String, Object> totals() {
        Cached cached = cache.get();
        if (cached != null && System.currentTimeMillis() - cached.at() < KEEP_MILLIS) {
            return cached.value();
        }
        // No tenant scope: the count has to cross workspaces, and only the totals are returned.
        Map<String, Object> fresh = RequestContext.runAs((java.util.UUID) null, () -> {
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("documents", count("""
                    select count(*) from documents d join tenants t on t.id = d.tenant_id
                    where d.deleted_at is null and d.processing_status <> 'DUPLICATE'
                      and coalesce(t.settings ->> 'demo', 'false') <> 'true'
                    """));
            totals.put("cases", count("""
                    select count(*) from cases c join tenants t on t.id = c.tenant_id
                    where c.deleted_at is null and coalesce(t.settings ->> 'demo', 'false') <> 'true'
                    """));
            totals.put("tasksDone", count("""
                    select count(*) from tasks k join tenants t on t.id = k.tenant_id
                    where k.deleted_at is null and k.status = 'DONE' and coalesce(t.settings ->> 'demo', 'false') <> 'true'
                    """));
            totals.put("asOf", Instant.now().toString());
            return totals;
        });
        cache.set(new Cached(fresh, System.currentTimeMillis()));
        return fresh;
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
