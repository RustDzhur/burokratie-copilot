package de.buerokratiecopilot.shared.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerHeartbeatTest {

    @Test
    @DisplayName("the worker writes the current time, so a container health check can tell a live worker from a stuck one")
    void writesTheCurrentTime(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("heartbeat");
        Instant before = Instant.now();

        new WorkerHeartbeat(file.toString()).beat();

        Instant written = Instant.parse(Files.readString(file));
        assertThat(written).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
    }

    @Test
    @DisplayName("an unwritable location does not stop the worker")
    void unwritableLocationIsTolerated(@TempDir Path dir) {
        new WorkerHeartbeat(dir.resolve("missing/dir/heartbeat").toString()).beat();
    }
}
