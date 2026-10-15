package de.buerokratiecopilot.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes demo cabinets that nobody closed, so that a visitor who just left the page leaves nothing behind. */
@Component
public class DemoPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(DemoPurgeJob.class);

    private final DemoService demo;

    public DemoPurgeJob(DemoService demo) {
        this.demo = demo;
    }

    @Scheduled(fixedDelayString = "${burokratie.demo.purge-poll-ms:600000}", initialDelayString = "${burokratie.demo.purge-initial-delay-ms:120000}")
    public void run() {
        try {
            demo.purgeExpired();
        }
        catch (RuntimeException ex) {
            log.warn("Demo purge failed: {}", ex.getMessage());
        }
    }
}
