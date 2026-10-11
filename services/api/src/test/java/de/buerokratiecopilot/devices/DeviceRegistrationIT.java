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
 * Verifies device registration and revocation (spec §22, G-001 acceptance).
 *
 * <p>An unauthorised device does not receive jobs, and a revoked device loses access. Both are tested
 * from the side that matters: not "the status column says the right word" but "the request is refused".
 * A device sits outside the platform on somebody's desk, and the key it presents is the only thing
 * identifying it.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class DeviceRegistrationIT {

    @Autowired
    private DeviceService devices;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Geräte Test', 'COMPANY') returning id
                """, UUID.class, "dev-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Administrator', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "dv-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    private DeviceService.Registered register(UUID tenantId, String identifier) {
        return RequestContext.runAs(tenantId, () -> devices.register(tenantId, identifier,
                "Scanner im Kopierraum", "SCANNER", "PULL", person()));
    }

    private DeviceService.Device authenticate(UUID tenantId, String key) {
        return RequestContext.runAs(tenantId, () -> devices.authenticate(key));
    }

    @Test
    @DisplayName("a newly registered device is not authorised and receives nothing")
    void registrationIsNotAuthorisation() {
        UUID tenantId = tenant();

        DeviceService.Registered registered = register(tenantId, "scanner-1");

        assertThat(registered.status())
                .as("a scanner that registers itself is still an unknown machine in the building")
                .isEqualTo("PENDING");
        assertThatThrownBy(() -> authenticate(tenantId, registered.apiKey()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHENTICATED))
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("an activated device authenticates")
    void activatedDeviceAuthenticates() {
        UUID tenantId = tenant();
        DeviceService.Registered registered = register(tenantId, "scanner-1");
        RequestContext.runAs(tenantId, () -> {
            devices.activate(tenantId, registered.deviceId(), person());
            return null;
        });

        DeviceService.Device device = authenticate(tenantId, registered.apiKey());

        assertThat(device.identifier()).isEqualTo("scanner-1");
        assertThat(device.status()).isEqualTo("ACTIVE");
        assertThat(device.tenantId()).isEqualTo(tenantId);
    }

    @Test
    @DisplayName("the key is shown once and stored only as a hash")
    void theKeyIsStoredAsAHash() {
        UUID tenantId = tenant();
        DeviceService.Registered registered = register(tenantId, "scanner-1");

        String stored = jdbc.queryForObject("select api_key_hash from devices where id = ?", String.class,
                registered.deviceId());

        assertThat(stored).doesNotContain(registered.apiKey());
        assertThat(stored).isEqualTo(DeviceService.hash(registered.apiKey()));
        assertThat(stored).hasSize(64);
    }

    @Test
    @DisplayName("a revoked device loses access at once")
    void revokedDeviceLosesAccess() {
        UUID tenantId = tenant();
        DeviceService.Registered registered = register(tenantId, "scanner-1");
        RequestContext.runAs(tenantId, () -> {
            devices.activate(tenantId, registered.deviceId(), person());
            return null;
        });
        assertThat(authenticate(tenantId, registered.apiKey())).isNotNull();

        RequestContext.runAs(tenantId, () -> {
            devices.revoke(tenantId, registered.deviceId(), person(), "Gerät gestohlen");
            return null;
        });

        // Not at the next refresh and not at the end of a session: on the request that follows.
        assertThatThrownBy(() -> authenticate(tenantId, registered.apiKey()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("REVOKED");
    }

    @Test
    @DisplayName("revoking frees the jobs the device had claimed")
    void revokingFreesClaimedJobs() {
        UUID tenantId = tenant();
        DeviceService.Registered registered = register(tenantId, "scanner-1");
        RequestContext.runAs(tenantId, () -> {
            devices.activate(tenantId, registered.deviceId(), person());
            devices.queueJob(tenantId, registered.deviceId(), "SCAN", "{}", null);
            devices.claimNext(tenantId, registered.deviceId());
            devices.revoke(tenantId, registered.deviceId(), person(), "weg");
            return null;
        });

        assertThat(jdbc.queryForObject("select status from device_jobs where tenant_id = ?", String.class,
                tenantId))
                .as("work assigned to a machine that may not work is work that would never be done")
                .isEqualTo("QUEUED");
    }

    @Test
    @DisplayName("an unknown key is refused")
    void unknownKeyIsRefused() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> authenticate(tenantId, "bcdev_0000000000000000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("unbekannt");
    }

    @Test
    @DisplayName("a missing key is refused")
    void missingKeyIsRefused() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> authenticate(tenantId, "  "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Kein Geräteschlüssel");
    }

    @Test
    @DisplayName("activation needs a person")
    void activationNeedsAPerson() {
        UUID tenantId = tenant();
        DeviceService.Registered registered = register(tenantId, "scanner-1");

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            devices.activate(tenantId, registered.deviceId(), null);
            return null;
        })).isInstanceOf(ApiException.class).hasMessageContaining("Person");
    }

    @Test
    @DisplayName("a device cannot be activated twice")
    void activationHappensOnce() {
        UUID tenantId = tenant();
        DeviceService.Registered registered = register(tenantId, "scanner-1");
        RequestContext.runAs(tenantId, () -> {
            devices.activate(tenantId, registered.deviceId(), person());
            return null;
        });

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            devices.activate(tenantId, registered.deviceId(), person());
            return null;
        })).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("two devices of one tenant cannot share an identifier")
    void identifierIsUniquePerTenant() {
        UUID tenantId = tenant();
        register(tenantId, "scanner-1");

        assertThatThrownBy(() -> register(tenantId, "scanner-1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("the same identifier in another tenant is a different device")
    void identifierIsPerTenant() {
        UUID first = tenant();
        UUID second = tenant();

        DeviceService.Registered one = register(first, "scanner-1");
        DeviceService.Registered two = register(second, "scanner-1");

        assertThat(one.deviceId()).isNotEqualTo(two.deviceId());
    }

    @Test
    @DisplayName("the list of devices contains no key")
    void theListContainsNoKey() {
        UUID tenantId = tenant();
        DeviceService.Registered registered = register(tenantId, "scanner-1");

        var listed = RequestContext.runAs(tenantId, () -> devices.list(tenantId));

        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst()).containsEntry("has_key", true);
        assertThat(listed.getFirst().values().stream().map(String::valueOf).toList())
                .allSatisfy(value -> assertThat(value).doesNotContain(registered.apiKey()));
    }

    @Test
    @DisplayName("a device of another tenant is not visible")
    void otherTenantSeesNothing() {
        UUID owner = tenant();
        UUID stranger = tenant();
        DeviceService.Registered registered = register(owner, "scanner-1");

        assertThatThrownBy(() -> RequestContext.runAs(stranger,
                () -> devices.describe(stranger, registered.deviceId())))
                .isInstanceOf(ApiException.class);
        assertThat(RequestContext.runAs(stranger, () -> devices.list(stranger))).isEmpty();
    }

    @Test
    @DisplayName("registering requires an identifier and a name")
    void requiredFieldsAreChecked() {
        UUID tenantId = tenant();
        UUID user = person();

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> devices.register(tenantId, "  ",
                "Name", "SCANNER", "PULL", user)))
                .isInstanceOf(ApiException.class).hasMessageContaining("Kennung");
        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> devices.register(tenantId, "id",
                null, "SCANNER", "PULL", user)))
                .isInstanceOf(ApiException.class).hasMessageContaining("Namen");
    }

    @Test
    @DisplayName("a status the schema does not know is refused")
    void unknownDeviceTypeIsRefused() {
        UUID tenantId = tenant();
        UUID user = person();

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> devices.register(tenantId,
                "scanner-1", "Name", "TOASTER", "PULL", user)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    @DisplayName("a device that has been seen reports when")
    void lastSeenIsRecorded() {
        UUID tenantId = tenant();
        DeviceService.Registered registered = register(tenantId, "scanner-1");
        RequestContext.runAs(tenantId, () -> {
            devices.activate(tenantId, registered.deviceId(), person());
            return null;
        });

        authenticate(tenantId, registered.apiKey());

        assertThat(RequestContext.runAs(tenantId,
                () -> devices.lastSeen(tenantId, registered.deviceId()))).isNotNull();
    }

    @Test
    @DisplayName("the registration answer says the device is not yet released")
    void theAnswerIsHonest() {
        UUID tenantId = tenant();

        Map<String, Object> answer = register(tenantId, "scanner-1").asMap();

        assertThat(answer).containsEntry("status", "PENDING");
        assertThat(String.valueOf(answer.get("note"))).contains("nicht freigegeben");
    }
}
