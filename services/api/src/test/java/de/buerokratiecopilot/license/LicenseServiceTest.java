package de.buerokratiecopilot.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The licence contract that matters operationally: a valid key is accepted, a perpetual key never
 * expires, an expired key moves through grace into read-only, and a key for another installation is
 * never accepted.
 */
class LicenseServiceTest {

    @TempDir
    Path temp;

    private static final String CUSTOMER = "Muster GmbH";
    private static final String LICENSE_ID = "LIC-2026-0001";

    @Test
    @DisplayName("a valid, correctly signed key for this instance is active")
    void activeKey() throws Exception {
        String instance = UUID.randomUUID().toString();
        String secret = LicenseTestTokens.newSecret();
        Instant now = Instant.now();
        String token = LicenseTestTokens.mint(secret, LICENSE_ID, CUSTOMER, instance, "ENTERPRISE",
                126, 5000, List.of("agents", "workflow", "ingest"),
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30)));

        LicenseService.LicenseStatus status = service(token, secret, instance, 14).status();

        assertThat(status.state()).isEqualTo(LicenseState.LICENSE_ACTIVE);
        assertThat(status.readOnly()).isFalse();
        assertThat(status.perpetual()).isFalse();
        assertThat(status.licenseId()).isEqualTo(LICENSE_ID);
        assertThat(status.customer()).isEqualTo(CUSTOMER);
        assertThat(status.plan()).isEqualTo("ENTERPRISE");
        assertThat(status.instanceId()).isEqualTo(instance);
        assertThat(status.maxAgents()).isEqualTo(126);
        assertThat(status.maxRunsPerMonth()).isEqualTo(5000);
        assertThat(status.features()).containsExactly("agents", "workflow", "ingest");
        assertThat(status.daysRemaining()).isBetween(28L, 30L);
    }

    @Test
    @DisplayName("a key without expiresAt is perpetual: active now and at any time later")
    void perpetualKey() throws Exception {
        String instance = UUID.randomUUID().toString();
        String secret = LicenseTestTokens.newSecret();
        Instant now = Instant.now();
        // No expiresAt in the payload at all — the default shape the cabinet issues.
        String token = LicenseTestTokens.mintWithExpirySeconds(secret, LICENSE_ID, CUSTOMER, instance,
                "ENTERPRISE", 126, 5000, List.of("agents"), now.minus(Duration.ofDays(1)), null);

        LicenseService.LicenseStatus status = service(token, secret, instance, 14).status();

        assertThat(status.state()).isEqualTo(LicenseState.LICENSE_ACTIVE);
        assertThat(status.readOnly()).isFalse();
        assertThat(status.perpetual()).isTrue();
        assertThat(status.expiresAt()).isNull();
        assertThat(status.daysRemaining()).isZero();
        assertThat(status.graceDaysRemaining()).isZero();
        assertThat(status.licenseId()).isEqualTo(LICENSE_ID);
    }

    @Test
    @DisplayName("a perpetual key never enters grace or read-only, however far the clock moves")
    void perpetualKeyNeverExpires() {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        LicensePayload payload = LicenseTestTokens.payload("instance-a", now.minus(Duration.ofDays(1)),
                null);

        assertThat(LicenseTokenVerifier.stateFor(payload, now, 14)).isEqualTo(LicenseState.LICENSE_ACTIVE);
        assertThat(LicenseTokenVerifier.stateFor(payload, now.plus(Duration.ofDays(3650)), 14))
                .isEqualTo(LicenseState.LICENSE_ACTIVE);
        assertThat(payload.perpetual()).isTrue();
        assertThat(payload.expiresAtInstant()).isNull();
    }

    @Test
    @DisplayName("an expired key is in grace for 14 days and then read-only")
    void graceAndReadOnly() throws Exception {
        String instance = UUID.randomUUID().toString();
        String secret = LicenseTestTokens.newSecret();
        Instant now = Instant.now();
        String recent = LicenseTestTokens.mint(secret, LICENSE_ID, CUSTOMER, instance, "ENTERPRISE",
                126, 5000, List.of(), now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(2)));
        LicenseService.LicenseStatus grace = service(recent, secret, instance, 14).status();
        assertThat(grace.state()).isEqualTo(LicenseState.LICENSE_GRACE);
        assertThat(grace.readOnly()).isFalse();
        assertThat(grace.graceDaysRemaining()).isBetween(11L, 12L);

        String old = LicenseTestTokens.mint(secret, LICENSE_ID, CUSTOMER, instance, "ENTERPRISE",
                126, 5000, List.of(), now.minus(Duration.ofDays(60)), now.minus(Duration.ofDays(20)));
        LicenseService.LicenseStatus readOnly = service(old, secret, instance, 14).status();
        assertThat(readOnly.state()).isEqualTo(LicenseState.LICENSE_READ_ONLY);
        assertThat(readOnly.readOnly()).isTrue();
    }

    @Test
    @DisplayName("the state boundary is exact: expiry plus grace, not a rounding of days")
    void stateBoundaryIsExact() {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        LicensePayload payload = LicenseTestTokens.payload("instance-a",
                now.minus(Duration.ofDays(20)), now.minus(Duration.ofDays(14)));

        assertThat(LicenseTokenVerifier.stateFor(payload, now, 14))
                .as("exactly at the end of grace is still grace").isEqualTo(LicenseState.LICENSE_GRACE);
        assertThat(LicenseTokenVerifier.stateFor(payload, now.plusSeconds(1), 14))
                .isEqualTo(LicenseState.LICENSE_READ_ONLY);
        assertThat(LicenseTokenVerifier.stateFor(payload, now.minus(Duration.ofDays(1)), 14))
                .isEqualTo(LicenseState.LICENSE_GRACE);
        assertThat(LicenseTokenVerifier.stateFor(payload, now.minus(Duration.ofDays(15)), 14))
                .isEqualTo(LicenseState.LICENSE_ACTIVE);
    }

    @Test
    @DisplayName("a key for another installation is refused, not quietly accepted")
    void foreignInstanceIsRefused() throws Exception {
        String thisInstance = UUID.randomUUID().toString();
        String otherInstance = UUID.randomUUID().toString();
        String secret = LicenseTestTokens.newSecret();
        Instant now = Instant.now();
        String token = LicenseTestTokens.mint(secret, LICENSE_ID, CUSTOMER, otherInstance,
                "ENTERPRISE", 126, 5000, List.of(), now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(30)));

        LicenseService.LicenseStatus status = service(token, secret, thisInstance, 14).status();

        assertThat(status.state()).isEqualTo(LicenseState.LICENSE_MISSING);
        assertThat(status.reason()).contains("WRONG_INSTANCE");
        assertThat(status.readOnly()).isFalse();
    }

    @Test
    @DisplayName("a modified payload, a wrong secret and a malformed token are all LICENSE_MISSING")
    void tamperingIsRefused() throws Exception {
        String instance = UUID.randomUUID().toString();
        String secret = LicenseTestTokens.newSecret();
        Instant now = Instant.now();
        String token = LicenseTestTokens.mint(secret, LICENSE_ID, CUSTOMER, instance, "ENTERPRISE",
                126, 5000, List.of(), now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30)));

        assertThat(service(LicenseTestTokens.withTamperedPayload(token), secret, instance, 14)
                .status().reason()).contains("BAD_SIGNATURE");
        assertThat(service(token, LicenseTestTokens.newSecret(), instance, 14)
                .status().reason()).contains("BAD_SIGNATURE");
        assertThat(service("not-a-token", secret, instance, 14)
                .status().reason()).contains("MALFORMED");
        assertThat(service("a.b.c", secret, instance, 14)
                .status().reason()).contains("MALFORMED");
    }

    @Test
    @DisplayName("a missing key or secret is a state, not a crash")
    void missingConfiguration() throws Exception {
        String instance = UUID.randomUUID().toString();
        Path instanceFile = Files.writeString(temp.resolve("instance-id"), instance);
        LicenseProperties properties = new LicenseProperties();
        properties.setEnforced(true);
        properties.setKey("");
        properties.setSecret(LicenseTestTokens.newSecret());
        properties.setInstanceIdFile(instanceFile.toString());

        LicenseService service = newService(properties);
        service.reload();
        assertThat(service.status().state()).isEqualTo(LicenseState.LICENSE_MISSING);
        assertThat(service.status().reason()).contains("LICENSE_KEY");

        properties.setKey("key");
        properties.setSecret("");
        service.reload();
        assertThat(service.status().state()).isEqualTo(LicenseState.LICENSE_MISSING);
        assertThat(service.status().reason()).contains("LICENSE_SECRET");
    }

    @Test
    @DisplayName("with enforcement off the installation is not blocked")
    void enforcementOff() {
        LicenseProperties properties = new LicenseProperties();
        LicenseService service = newService(properties);
        service.reload();

        assertThat(service.status().state()).isEqualTo(LicenseState.LICENSE_NOT_ENFORCED);
        assertThat(service.status().readOnly()).isFalse();
        // No database is present in this unit test; the no-op branch must return before it.
        service.assertRunAllowed(UUID.randomUUID());
    }

    @Test
    @DisplayName("a missing licence fails an agent run with 402, not with a database error")
    void missingLicenceBlocksRuns() throws Exception {
        String instance = UUID.randomUUID().toString();
        Path instanceFile = Files.writeString(temp.resolve("instance-id"), instance);
        LicenseProperties properties = new LicenseProperties();
        properties.setEnforced(true);
        properties.setKey("");
        properties.setSecret(LicenseTestTokens.newSecret());
        properties.setInstanceIdFile(instanceFile.toString());
        LicenseService service = newService(properties);

        ApiException failure = assertThrows(ApiException.class,
                () -> service.assertRunAllowed(UUID.randomUUID()));
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.LICENSE_MISSING);
        assertThat(failure.errorCode().status().value()).isEqualTo(402);
    }

    private LicenseService newService(LicenseProperties properties) {
        ObjectMapper mapper = new ObjectMapper();
        properties.setStateFile(temp.resolve("license-state-" + UUID.randomUUID() + ".json").toString());
        return new LicenseService(properties, mapper, null,
                new LicenseStatusClient(properties, mapper),
                new LicenseStateStore(properties, mapper));
    }

    private LicenseService service(String token, String secret, String instanceId, int graceDays)
            throws Exception {
        Path instanceFile = Files.writeString(temp.resolve("instance-id-" + UUID.randomUUID()),
                instanceId);
        LicenseProperties properties = new LicenseProperties();
        properties.setEnforced(true);
        properties.setKey(token);
        properties.setSecret(secret);
        properties.setInstanceIdFile(instanceFile.toString());
        properties.setGraceDays(graceDays);
        LicenseService service = newService(properties);
        service.reload();
        return service;
    }
}
