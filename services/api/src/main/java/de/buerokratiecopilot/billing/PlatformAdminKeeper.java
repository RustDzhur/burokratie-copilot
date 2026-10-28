package de.buerokratiecopilot.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.context.RequestContext;

/** The platform administrator's own workspace has no end date: it is renewed at start and every hour. */
@Component
public class PlatformAdminKeeper {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminKeeper.class);

    private final PlatformAdminResolver admins;

    public PlatformAdminKeeper(PlatformAdminResolver admins) {
        this.admins = admins;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStart() {
        keep();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void hourly() {
        keep();
    }

    void keep() {
        try {
            int changed = RequestContext.runAs((java.util.UUID) null, admins::keepAdminWorkspacesOpen);
            if (changed > 0) {
                log.info("{} administrator workspace(s) set to no end date", changed);
            }
        }
        catch (RuntimeException ex) {
            log.warn("The administrator workspace could not be kept open: {}", ex.getMessage());
        }
    }
}
