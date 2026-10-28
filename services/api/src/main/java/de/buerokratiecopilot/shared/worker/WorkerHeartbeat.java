package de.buerokratiecopilot.shared.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Proof of life of the worker.
 *
 * <p>The worker runs without a web server (`spring.main.web-application-type: none`), so it has no port a health check could
 * ask: the container's HTTP health check on port 8081 could never succeed and Docker reported the worker as unhealthy
 * from the first minute. The worker writes the current time to a file every few seconds instead; the container's
 * health check looks at how old the file is. A worker whose scheduler thread is stuck stops writing and is reported as
 * unhealthy, which is what a health check is for.</p>
 */
@Component
@Profile("worker")
public class WorkerHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeat.class);

    private final Path file;

    public WorkerHeartbeat(@Value("${burokratie.worker.heartbeat-file:/tmp/worker-heartbeat}") String file) {
        this.file = Path.of(file);
    }

    @Scheduled(fixedDelayString = "${burokratie.worker.heartbeat-ms:10000}", initialDelay = 0)
    public void beat() {
        try {
            Files.writeString(file, Instant.now().toString());
        }
        catch (IOException ex) {
            log.warn("The worker heartbeat file {} could not be written: {}", file, ex.getMessage());
        }
    }
}
