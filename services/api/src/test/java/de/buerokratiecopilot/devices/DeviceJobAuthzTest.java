package de.buerokratiecopilot.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Verifies who may receive a job (spec §22, G-001 acceptance).
 *
 * <p>The acceptance criterion is that an unauthorised device does not receive jobs. Jobs carry documents,
 * so a machine that has not been released must get nothing — not an empty answer, and certainly not
 * work — and a device may have been revoked between asking and being served, which is why the check is
 * repeated at the hand-over rather than only at authentication.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class DeviceJobAuthzTest {

    @Autowired
    private DeviceService devices;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Auftrag Test', 'COMPANY') returning id
                """, UUID.class, "job-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Administrator', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "dj-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    /** A device that has been registered and released. */
    private DeviceService.Registered workingDevice(UUID tenantId, String identifier) {
        DeviceService.Registered registered = RequestContext.runAs(tenantId,
                () -> devices.register(tenantId, identifier, "Scanner", "SCANNER", "PULL", person()));
        RequestContext.runAs(tenantId, () -> {
            devices.activate(tenantId, registered.deviceId(), person());
            return null;
        });
        return registered;
    }

    @Test
    @DisplayName("an unauthorised device receives no job, even when one is waiting")
    void unauthorisedDeviceReceivesNothing() {
        UUID tenantId = tenant();
        DeviceService.Registered pending = RequestContext.runAs(tenantId,
                () -> devices.register(tenantId, "scanner-1", "Scanner", "SCANNER", "PULL", person()));
        RequestContext.runAs(tenantId, () -> {
            devices.queueJob(tenantId, pending.deviceId(), "SCAN", "{\"source\":\"adf\"}", null);
            return null;
        });

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> devices.claimNext(tenantId, pending.deviceId())))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHENTICATED));

        assertThat(jdbc.queryForObject("select status from device_jobs where tenant_id = ?", String.class,
                tenantId))
                .as("the job belongs to nobody until a released device takes it")
                .isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("a released device receives the waiting job")
    void releasedDeviceReceivesTheJob() {
        UUID tenantId = tenant();
        DeviceService.Registered device = workingDevice(tenantId, "scanner-1");
        RequestContext.runAs(tenantId, () -> {
            devices.queueJob(tenantId, device.deviceId(), "SCAN", "{\"source\":\"adf\"}", null);
            return null;
        });

        Map<String, Object> job = RequestContext.runAs(tenantId,
                () -> devices.claimNext(tenantId, device.deviceId()));

        assertThat(job).containsEntry("job_type", "SCAN")
                .containsEntry("priority", 5)
                .containsEntry("attempt", 1);
        assertThat(job.get("id")).isNotNull();
        assertThat(jdbc.queryForObject("select status from device_jobs where id = ?", String.class,
                job.get("id"))).isEqualTo("CLAIMED");
    }

    @Test
    @DisplayName("a job is handed out once, not to everybody who asks")
    void aJobIsHandedOutOnce() {
        UUID tenantId = tenant();
        DeviceService.Registered first = workingDevice(tenantId, "scanner-1");
        DeviceService.Registered second = workingDevice(tenantId, "scanner-2");
        RequestContext.runAs(tenantId, () -> {
            devices.queueJob(tenantId, first.deviceId(), "SCAN", "{}", null);
            return null;
        });

        Map<String, Object> claimed = RequestContext.runAs(tenantId,
                () -> devices.claimNext(tenantId, first.deviceId()));
        Map<String, Object> nothing = RequestContext.runAs(tenantId,
                () -> devices.claimNext(tenantId, second.deviceId()));

        assertThat(claimed).isNotEmpty();
        assertThat(nothing)
                .as("the same document must not be scanned twice by two machines")
                .isEmpty();
    }

    @Test
    @DisplayName("a revoked device receives nothing, even with jobs waiting")
    void revokedDeviceReceivesNothing() {
        UUID tenantId = tenant();
        DeviceService.Registered device = workingDevice(tenantId, "scanner-1");
        RequestContext.runAs(tenantId, () -> {
            devices.queueJob(tenantId, device.deviceId(), "SCAN", "{}", null);
            devices.revoke(tenantId, device.deviceId(), person(), "gestohlen");
            return null;
        });

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> devices.claimNext(tenantId, device.deviceId())))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("revocation between authentication and hand-over still stops the job")
    void revocationBetweenTheTwoStepsIsCaught() throws Exception {
        UUID tenantId = tenant();
        DeviceService.Registered device = workingDevice(tenantId, "scanner-1");
        RequestContext.runAs(tenantId, () -> {
            devices.queueJob(tenantId, device.deviceId(), "SCAN", "{}", null);
            return null;
        });
        // The device authenticates, and is then revoked before it asks for work.
        RequestContext.runAs(tenantId, () -> devices.authenticate(device.apiKey()));
        RequestContext.runAs(tenantId, () -> {
            devices.revoke(tenantId, device.deviceId(), person(), "gestohlen");
            return null;
        });

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> devices.claimNext(tenantId, device.deviceId())))
                .as("the status is asked again at the hand-over, because it may have changed")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a device of another tenant cannot take a job")
    void anotherTenantsDeviceCannotTakeAJob() {
        UUID owner = tenant();
        UUID stranger = tenant();
        DeviceService.Registered device = workingDevice(owner, "scanner-1");
        RequestContext.runAs(owner, () -> {
            devices.queueJob(owner, device.deviceId(), "SCAN", "{}", null);
            return null;
        });

        assertThatThrownBy(() -> RequestContext.runAs(stranger,
                () -> devices.claimNext(stranger, device.deviceId())))
                .isInstanceOf(ApiException.class);

        assertThat(jdbc.queryForObject("select count(*) from device_jobs where tenant_id = ? "
                + "and status = 'CLAIMED'", Integer.class, owner)).isZero();
    }

    @Test
    @DisplayName("a device cannot finish another device's job")
    void aDeviceCannotFinishAnothersJob() {
        UUID tenantId = tenant();
        DeviceService.Registered first = workingDevice(tenantId, "scanner-1");
        DeviceService.Registered second = workingDevice(tenantId, "scanner-2");
        UUID jobId = RequestContext.runAs(tenantId, () -> {
            UUID queued = devices.queueJob(tenantId, first.deviceId(), "SCAN", "{}", null);
            devices.claimNext(tenantId, first.deviceId());
            return queued;
        });

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            devices.finishJob(tenantId, second.deviceId(), jobId, "COMPLETED", null, null);
            return null;
        })).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a finished job cannot be claimed again")
    void aFinishedJobIsNotClaimedAgain() {
        UUID tenantId = tenant();
        DeviceService.Registered device = workingDevice(tenantId, "scanner-1");
        UUID jobId = RequestContext.runAs(tenantId, () -> {
            UUID queued = devices.queueJob(tenantId, device.deviceId(), "SCAN", "{}", null);
            devices.claimNext(tenantId, device.deviceId());
            devices.finishJob(tenantId, device.deviceId(), queued, "COMPLETED", null, null);
            return queued;
        });

        assertThat(RequestContext.runAs(tenantId, () -> devices.claimNext(tenantId, device.deviceId())))
                .isEmpty();
        assertThat(jdbc.queryForObject("select status from device_jobs where id = ?", String.class, jobId))
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a job that expired is not handed out")
    void anExpiredJobIsNotHandedOut() {
        UUID tenantId = tenant();
        DeviceService.Registered device = workingDevice(tenantId, "scanner-1");
        RequestContext.runAs(tenantId, () -> {
            UUID jobId = devices.queueJob(tenantId, device.deviceId(), "SCAN", "{}", null);
            jdbc.update("update device_jobs set expires_at = now() - interval '1 hour' where id = ?",
                    jobId);
            return null;
        });

        assertThat(RequestContext.runAs(tenantId, () -> devices.claimNext(tenantId, device.deviceId())))
                .as("work that is no longer worth doing should not be started")
                .isEmpty();
    }

    @Test
    @DisplayName("an empty queue is an empty answer, not a failure")
    void anEmptyQueueIsNotAFailure() {
        UUID tenantId = tenant();
        DeviceService.Registered device = workingDevice(tenantId, "scanner-1");

        assertThat(RequestContext.runAs(tenantId, () -> devices.claimNext(tenantId, device.deviceId())))
                .isEmpty();
    }

    @Test
    @DisplayName("claiming increments the attempt count")
    void claimingCountsTheAttempt() {
        UUID tenantId = tenant();
        DeviceService.Registered device = workingDevice(tenantId, "scanner-1");
        UUID jobId = RequestContext.runAs(tenantId, () -> devices.queueJob(tenantId, device.deviceId(),
                "SCAN", "{}", null));

        RequestContext.runAs(tenantId, () -> devices.claimNext(tenantId, device.deviceId()));

        assertThat(jdbc.queryForObject("select attempt from device_jobs where id = ?", Integer.class,
                jobId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the job record says which device took it")
    void theJobSaysWhichDevice() {
        UUID tenantId = tenant();
        DeviceService.Registered device = workingDevice(tenantId, "scanner-1");
        RequestContext.runAs(tenantId, () -> devices.queueJob(tenantId, device.deviceId(), "SCAN", "{}",
                null));

        Map<String, Object> job = RequestContext.runAs(tenantId,
                () -> devices.claimNext(tenantId, device.deviceId()));

        assertThat(job).containsEntry("deviceId", device.deviceId().toString());
    }

    @Test
    @DisplayName("a job type the schema does not know is refused")
    void unknownJobTypeIsRefused() {
        UUID tenantId = tenant();
        DeviceService.Registered device = workingDevice(tenantId, "scanner-1");

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> devices.queueJob(tenantId, device.deviceId(), "DELETE_EVERYTHING", "{}", null)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
