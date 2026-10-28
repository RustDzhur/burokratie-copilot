package de.buerokratiecopilot.radar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.orchestration.OwnerNotifier;
import de.buerokratiecopilot.radar.RadarFindingService.Draft;
import de.buerokratiecopilot.radar.RadarFindingService.Outcome;
import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * Runs the rules and files what they find.
 *
 * <p>A rule that fails is logged and the others still run: one broken rule must not blind the Radar. A new or reopened
 * finding is announced once, if its severity reaches the configured level.</p>
 */
@Service
public class RadarEngine {

    private static final Logger log = LoggerFactory.getLogger(RadarEngine.class);

    /** What one run did. */
    public record Report(int created, int reopened, int updated, int unchanged, int resolved, int failedRules) {
    }

    private final List<RadarRule> rules;
    private final RadarFindingService findings;
    private final SettingsService settings;
    private final OwnerNotifier notifier;
    private final JdbcTemplate jdbc;
    private final de.buerokratiecopilot.billing.PlanFeatures plans;

    public RadarEngine(List<RadarRule> rules, RadarFindingService findings, SettingsService settings, OwnerNotifier notifier,
                       JdbcTemplate jdbc, de.buerokratiecopilot.billing.PlanFeatures plans) {
        this.plans = plans;
        this.rules = rules;
        this.findings = findings;
        this.settings = settings;
        this.notifier = notifier;
        this.jdbc = jdbc;
    }

    public List<RadarRule.Description> descriptions() {
        return rules.stream().map(RadarRule::describe).toList();
    }

    /** The Radar is part of the Personal plan and above (the same tier the API gate asks for). */
    private boolean active(UUID tenantId) {
        return settings.getBoolean("radar.enabled", true) && plans.tierOf(tenantId) >= RADAR_MIN_TIER;
    }

    static final int RADAR_MIN_TIER = 1;

    /** The rules that look at a document that was just processed. */
    public Report evaluateDocument(UUID tenantId, UUID documentId) {
        if (!active(tenantId)) {
            return new Report(0, 0, 0, 0, 0, 0);
        }
        Counter counter = new Counter();
        for (RadarRule rule : rules) {
            try {
                for (Draft draft : rule.onDocument(tenantId, documentId)) {
                    file(tenantId, draft, counter);
                }
            }
            catch (RuntimeException ex) {
                counter.failed++;
                log.error("Radar rule {} failed for document {}", rule.describe().id(), documentId, ex);
            }
        }
        return counter.report();
    }

    /** The rules that look at the whole tenant, plus the snoozes whose time is up. */
    public Report sweep(UUID tenantId) {
        if (!active(tenantId)) {
            return new Report(0, 0, 0, 0, 0, 0);
        }
        Counter counter = new Counter();
        counter.reopened += findings.reopenExpiredSnoozes(tenantId);
        for (RadarRule rule : rules) {
            try {
                List<Draft> drafts = rule.onSweep(tenantId);
                Set<String> named = new HashSet<>();
                for (Draft draft : drafts) {
                    named.add(draft.key());
                    file(tenantId, draft, counter);
                }
                if (rule.sweepIsComplete()) {
                    counter.resolved += resolveGone(tenantId, rule.describe().id(), named);
                }
            }
            catch (RuntimeException ex) {
                counter.failed++;
                log.error("Radar rule {} failed in the sweep of tenant {}", rule.describe().id(), tenantId, ex);
            }
        }
        return counter.report();
    }

    private int resolveGone(UUID tenantId, String ruleId, Set<String> named) {
        List<Map<String, Object>> open = jdbc.queryForList("""
                select id, finding_key from radar_findings
                where tenant_id = ? and rule_id = ? and status in ('NEW', 'UNDER_REVIEW', 'CONFIRMED', 'REOPENED', 'SNOOZED')
                """, tenantId, ruleId);
        int resolved = 0;
        for (Map<String, Object> row : open) {
            if (!named.contains((String) row.get("finding_key"))) {
                findings.transition(tenantId, (UUID) row.get("id"), RadarFindingStatus.RESOLVED, null,
                        "The situation no longer exists", null);
                resolved++;
            }
        }
        return resolved;
    }

    private void file(UUID tenantId, Draft draft, Counter counter) {
        RadarFindingService.Result result = findings.raise(tenantId, draft);
        switch (result.outcome()) {
            case CREATED -> counter.created++;
            case REOPENED -> counter.reopened++;
            case UPDATED -> counter.updated++;
            case UNCHANGED -> counter.unchanged++;
        }
        if ((result.outcome() == Outcome.CREATED || result.outcome() == Outcome.REOPENED) && reachesLevel(draft.severity())
                && findings.claimNotification(tenantId, result.id())) {
            announce(tenantId, result.id(), draft);
        }
    }

    private boolean reachesLevel(RadarSeverity severity) {
        String configured = settings.getString("radar.notify.min_severity").orElse("MEDIUM");
        RadarSeverity floor;
        try {
            floor = RadarSeverity.valueOf(configured.strip().toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            floor = RadarSeverity.MEDIUM;
        }
        return severity.ordinal() >= floor.ordinal();
    }

    private void announce(UUID tenantId, UUID findingId, Draft draft) {
        Notification.Severity severity = Notification.Severity.valueOf(draft.severity().notificationSeverity());
        notifier.tell(tenantId, Notification.Category.RADAR, severity, "radar.finding", draft.title(),
                draft.description() == null ? draft.title() : draft.description(), "radar_finding", findingId,
                "/radar?finding=" + findingId, "radar:" + findingId,
                Map.of("findingId", findingId.toString(), "severity", draft.severity().name(), "ruleId", draft.ruleId()));
    }

    private static final class Counter {
        int created;
        int reopened;
        int updated;
        int unchanged;
        int resolved;
        int failed;

        Report report() {
            return new Report(created, reopened, updated, unchanged, resolved, failed);
        }
    }

    /** All tenant ids, for the scheduler. */
    List<UUID> allTenants() {
        return new ArrayList<>(jdbc.queryForList("select id from tenants", UUID.class));
    }
}
