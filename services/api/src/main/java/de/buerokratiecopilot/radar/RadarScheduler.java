package de.buerokratiecopilot.radar;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.context.RequestContext;

/** Time passes without any event: a deadline comes close, a task becomes overdue. The sweep notices both. */
@Component
public class RadarScheduler {

    private static final Logger log = LoggerFactory.getLogger(RadarScheduler.class);

    private final RadarEngine engine;
    private final boolean enabled;

    public RadarScheduler(RadarEngine engine, @Value("${burokratie.radar.sweep-enabled:true}") boolean enabled) {
        this.engine = engine;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${burokratie.radar.sweep-interval-ms:3600000}",
            initialDelayString = "${burokratie.radar.sweep-initial-delay-ms:120000}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            for (UUID tenantId : engine.allTenants()) {
                try {
                    RadarEngine.Report report = RequestContext.runAs(tenantId, () -> engine.sweep(tenantId));
                    if (report.created() + report.reopened() + report.resolved() > 0) {
                        log.info("Radar sweep for tenant {}: {}", tenantId, report);
                    }
                }
                catch (RuntimeException ex) {
                    log.error("Radar sweep failed for tenant {}", tenantId, ex);
                }
            }
        }
        catch (RuntimeException ex) {
            log.error("Radar sweep could not list tenants", ex);
        }
    }
}
