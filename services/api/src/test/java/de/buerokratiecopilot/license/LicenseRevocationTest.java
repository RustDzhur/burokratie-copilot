package de.buerokratiecopilot.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Revocation is the one thing a signed perpetual licence cannot express on its own. These tests
 * pin the three behaviours the owner asked for:
 *
 * <ul>
 *   <li>the cabinet says "revoked" → the installation is {@code LICENSE_REVOKED} and agent calls
 *       answer 402, with no grace period;</li>
 *   <li>the cabinet cannot be reached → the last known state stands and nothing is blocked;</li>
 *   <li>the verdict survives a restart, but only for the licence it belongs to.</li>
 * </ul>
 *
 * <p>The "cabinet" is a real loopback HTTP server: the client's URL building, status handling and
 * JSON parsing are exercised, not mocked.</p>
 */
class LicenseRevocationTest {

    private static final String CUSTOMER = "Muster GmbH";
    private static final String LICENSE_ID = "LIC-2026-0007";

    @TempDir
    Path temp;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    @DisplayName("a revoked licence turns the installation into LICENSE_REVOKED with a 402 for agent runs")
    void revokedLicenceBlocksAgentRuns() throws Exception {
        Fixture fixture = fixture("""
                {"active":false,"revokedAt":"2026-10-01T09:30:00Z","reason":"Vertrag beendet"}
                """);

        fixture.service.refreshRevocation();

        LicenseService.LicenseStatus status = fixture.service.status();
        assertThat(status.state()).isEqualTo(LicenseState.LICENSE_REVOKED);
        assertThat(status.readOnly()).isFalse();
        assertThat(status.revokedAt()).isEqualTo(Instant.parse("2026-10-01T09:30:00Z"));
        assertThat(status.revocationReason()).isEqualTo("Vertrag beendet");
        assertThat(status.revocationCheckedAt()).isNotNull();
        assertThat(status.state().agentCallsAllowed()).isFalse();

        ApiException failure = assertThrows(ApiException.class,
                () -> fixture.service.assertRunAllowed(null));
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.LICENSE_REVOKED);
        assertThat(failure.errorCode().status().value()).isEqualTo(402);
    }

    @Test
    @DisplayName("an active perpetual licence stays active when the cabinet confirms it")
    void activeLicenceStaysActive() throws Exception {
        Fixture fixture = fixture("""
                {"active":true,"revokedAt":null,"reason":null}
                """);

        fixture.service.refreshRevocation();

        LicenseService.LicenseStatus status = fixture.service.status();
        assertThat(status.state()).isEqualTo(LicenseState.LICENSE_ACTIVE);
        assertThat(status.perpetual()).isTrue();
        assertThat(status.revokedAt()).isNull();
        assertThat(status.revocationCheckedAt()).isNotNull();
        fixture.service.assertRunAllowed(null);
    }

    @Test
    @DisplayName("an unreachable cabinet does not block: the last known state stands and a warning is logged")
    void unreachableCabinetDoesNotBlock() throws Exception {
        Fixture fixture = fixtureUnreachable();

        fixture.service.refreshRevocation();

        LicenseService.LicenseStatus status = fixture.service.status();
        assertThat(status.state()).isEqualTo(LicenseState.LICENSE_ACTIVE);
        assertThat(status.perpetual()).isTrue();
        // No network answer means no new timestamp; the last known state is unchanged.
        assertThat(status.revocationCheckedAt()).isNull();
        fixture.service.assertRunAllowed(null);
        // Nothing was cached from a failed check.
        assertThat(Files.exists(fixture.stateFile)).isFalse();
    }

    @Test
    @DisplayName("a 5xx from the cabinet is a failed check, not a revocation")
    void serverErrorIsNotRevocation() throws Exception {
        Fixture fixture = fixtureStatus(503, "{}");

        fixture.service.refreshRevocation();

        assertThat(fixture.service.status().state()).isEqualTo(LicenseState.LICENSE_ACTIVE);
    }

    @Test
    @DisplayName("the revoked verdict survives a restart while the cabinet is down")
    void revocationSurvivesRestart() throws Exception {
        Fixture first = fixture("""
                {"active":false,"revokedAt":"2026-10-01T09:30:00Z","reason":"Vertrag beendet"}
                """);
        first.service.refreshRevocation();
        assertThat(first.service.status().state()).isEqualTo(LicenseState.LICENSE_REVOKED);

        // Same key, same state file, but the cabinet is now unreachable: the cached verdict wins.
        LicenseProperties restarted = properties(first.token, first.secret, first.instanceId,
                first.stateFile, "http://127.0.0.1:" + closedPort() + "/api/license/status");
        LicenseService service = new LicenseService(restarted, new ObjectMapper(), null,
                new LicenseStatusClient(restarted, new ObjectMapper()),
                new LicenseStateStore(restarted, new ObjectMapper()));
        service.reload();
        service.refreshRevocation();

        assertThat(service.status().state()).isEqualTo(LicenseState.LICENSE_REVOKED);
        assertThat(service.status().revocationReason()).isEqualTo("Vertrag beendet");
    }

    @Test
    @DisplayName("a cached revocation of another licence id is ignored")
    void cacheIsBoundToTheLicence() throws Exception {
        Fixture fixture = fixture("""
                {"active":false,"revokedAt":"2026-10-01T09:30:00Z","reason":"old key"}
                """);
        // Simulate a stale cache written for a different licence id.
        LicenseStateStore store = new LicenseStateStore(fixture.properties, new ObjectMapper());
        store.write("LIC-SOMETHING-ELSE", fixture.instanceId,
                new LicenseRevocation(true, Instant.now(), "old key", Instant.now()));

        fixture.service.reload();

        assertThat(fixture.service.status().state()).isEqualTo(LicenseState.LICENSE_ACTIVE);
    }

    /* ── fixtures ─────────────────────────────────────────────────────── */

    private record Fixture(LicenseService service, LicenseProperties properties,
                           String token, String secret, String instanceId, Path stateFile) {
    }

    private Fixture fixture(String body) throws Exception {
        return fixtureStatus(200, body);
    }

    private Fixture fixtureStatus(int status, String body) throws Exception {
        int port = startServer(status, body);
        String instance = UUID.randomUUID().toString();
        String secret = LicenseTestTokens.newSecret();
        String token = LicenseTestTokens.mintWithExpirySeconds(secret, LICENSE_ID, CUSTOMER,
                instance, "ENTERPRISE", 126, 0, List.of("agents"), Instant.now(), null);
        Path stateFile = temp.resolve("license-state-" + UUID.randomUUID() + ".json");
        LicenseProperties properties = properties(token, secret, instance, stateFile,
                "http://127.0.0.1:" + port + "/api/license/status");
        return new Fixture(service(properties), properties, token, secret, instance, stateFile);
    }

    private Fixture fixtureUnreachable() throws Exception {
        String instance = UUID.randomUUID().toString();
        String secret = LicenseTestTokens.newSecret();
        String token = LicenseTestTokens.mintWithExpirySeconds(secret, LICENSE_ID, CUSTOMER,
                instance, "ENTERPRISE", 126, 0, List.of("agents"), Instant.now(), null);
        Path stateFile = temp.resolve("license-state-" + UUID.randomUUID() + ".json");
        LicenseProperties properties = properties(token, secret, instance, stateFile,
                "http://127.0.0.1:" + closedPort() + "/api/license/status");
        return new Fixture(service(properties), properties, token, secret, instance, stateFile);
    }

    private static LicenseService service(LicenseProperties properties) {
        LicenseService service = new LicenseService(properties, new ObjectMapper(), null,
                new LicenseStatusClient(properties, new ObjectMapper()),
                new LicenseStateStore(properties, new ObjectMapper()));
        service.reload();
        return service;
    }

    private static LicenseProperties properties(String token, String secret, String instance,
                                                Path stateFile, String checkUrl) throws IOException {
        Path instanceFile = Files.createTempFile("bc-license-instance", ".id");
        Files.writeString(instanceFile, instance);
        LicenseProperties properties = new LicenseProperties();
        properties.setEnforced(true);
        properties.setKey(token);
        properties.setSecret(secret);
        properties.setInstanceIdFile(instanceFile.toString());
        properties.setStateFile(stateFile.toString());
        properties.setCheckUrl(checkUrl);
        return properties;
    }

    private int startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/license/status", exchange -> {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return server.getAddress().getPort();
    }

    /** A port nothing listens on: a deterministic connection refusal without external network. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
