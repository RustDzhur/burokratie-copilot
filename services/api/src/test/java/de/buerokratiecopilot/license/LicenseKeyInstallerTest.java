package de.buerokratiecopilot.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.shared.error.ApiErrorResponse;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The contract of the licence installer behind {@code POST /api/v1/runtime/license}.
 *
 * <p>What matters operationally: a valid key is stored with owner-only permissions and activates
 * without a restart, and <em>every</em> invalid key is refused before the existing key file is
 * touched. A typo must never replace a working licence with a broken one.</p>
 */
class LicenseKeyInstallerTest {

    @TempDir
    Path temp;

    private static final String CUSTOMER = "Muster GmbH";
    private static final String LICENSE_ID = "LIC-2026-0042";

    private LicenseProperties properties;
    private LicenseService license;
    private LicenseKeyInstaller installer;
    private String secret;
    private String instanceId;
    private Path keyFile;

    @BeforeEach
    void setUp() throws Exception {
        secret = LicenseTestTokens.newSecret();
        instanceId = UUID.randomUUID().toString();

        Path instanceFile = temp.resolve("instance-id");
        Files.writeString(instanceFile, instanceId, StandardCharsets.UTF_8);
        keyFile = temp.resolve("license.key");

        properties = new LicenseProperties();
        properties.setEnforced(true);
        properties.setSecret(secret);
        properties.setInstanceIdFile(instanceFile.toString());
        properties.setKeyFile(keyFile.toString());

        LicenseService.LicenseStatus status = mock(LicenseService.LicenseStatus.class);
        when(status.state()).thenReturn(LicenseState.LICENSE_ACTIVE);
        license = mock(LicenseService.class);
        when(license.status()).thenReturn(status);

        installer = new LicenseKeyInstaller(properties, new ObjectMapper(), license);
    }

    @Test
    @DisplayName("a valid key is written with owner-only permissions, activates, and is reloaded")
    void installsValidKey() throws Exception {
        String token = mint(instanceId);

        LicenseKeyInstaller.InstallResult result = installer.install(token);

        assertThat(result.keyFile()).isEqualTo(keyFile.toAbsolutePath().normalize().toString());
        assertThat(result.license().state()).isEqualTo(LicenseState.LICENSE_ACTIVE);
        assertThat(Files.readString(keyFile, StandardCharsets.UTF_8).strip())
                .isEqualTo(token.strip());
        verify(license).reload();
        assertThat(result.warnings()).isEmpty();

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyFile);
            assertThat(permissions).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    @DisplayName("garbage is refused with 400 and leaves the existing key file untouched")
    void refusesGarbage() throws Exception {
        Files.writeString(keyFile, "the-old-key", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> installer.install("not-a-licence"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ex.violations()).extracting(ApiErrorResponse.FieldViolation::code)
                            .containsExactly("MALFORMED");
                });

        assertThat(Files.readString(keyFile, StandardCharsets.UTF_8)).isEqualTo("the-old-key");
    }

    @Test
    @DisplayName("a key for another installation is refused, not stored")
    void refusesWrongInstance() {
        String otherInstance = UUID.randomUUID().toString();

        assertThatThrownBy(() -> installer.install(mint(otherInstance)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ex.violations()).extracting(ApiErrorResponse.FieldViolation::code)
                            .containsExactly("WRONG_INSTANCE");
                });
        assertThat(Files.exists(keyFile)).isFalse();
    }

    @Test
    @DisplayName("a tampered payload is refused with a signature error")
    void refusesTamperedPayload() {
        String token = mint(instanceId);
        String[] parts = token.split("\\.", -1);
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2)
                + "AA." + parts[2];

        assertThatThrownBy(() -> installer.install(tampered))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.violations()).extracting(ApiErrorResponse.FieldViolation::code)
                                .containsExactly("BAD_SIGNATURE"));
    }

    @Test
    @DisplayName("without LICENSE_SECRET no key can be verified and nothing is written")
    void refusesWithoutSecret() {
        properties.setSecret("");
        String token = mint(instanceId);

        assertThatThrownBy(() -> installer.install(token))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.violations()).extracting(ApiErrorResponse.FieldViolation::code)
                            .containsExactly("LICENSE_SECRET_MISSING");
                });
        assertThat(Files.exists(keyFile)).isFalse();
    }

    @Test
    @DisplayName("an empty key is a 400, not a file write")
    void refusesEmptyKey() {
        assertThatThrownBy(() -> installer.install("   "))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                    assertThat(ex.violations()).extracting(ApiErrorResponse.FieldViolation::code)
                            .containsExactly("EMPTY");
                });
        assertThat(Files.exists(keyFile)).isFalse();
    }

    private String mint(String instance) {
        Instant now = Instant.now();
        return LicenseTestTokens.mint(secret, LICENSE_ID, CUSTOMER, instance, "ENTERPRISE", 126,
                5000, List.of("agents", "workflow"), now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(30)));
    }
}
