package de.buerokratiecopilot.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.shared.error.ApiErrorResponse;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Installs a runtime licence key from the operator surface.
 *
 * <p>Without this class the only way to replace a key was to edit {@code runtime.env} and restart
 * the API. The installer is often not on the machine and does not have a shell; the runtime admin
 * page (and its {@code POST /api/v1/runtime/license}) is the surface they do have. The key is
 * verified <em>before</em> anything is written, so a typo never replaces a working licence with a
 * broken one.</p>
 *
 * <p>The file is written atomically ({@code temp -> rename}) and restricted to the owner
 * ({@code 0600}). The key itself is never logged: the log line names the file and the resulting
 * state, not the credential.</p>
 *
 * <p>Which file is written: the configured {@code LICENSE_KEY_FILE} when set, otherwise
 * {@link #DEFAULT_KEY_FILE}, which is where the runtime compose stack mounts its data volume. The
 * same file is then made the source for {@link LicenseService#reload()} so the installation does
 * not have to be restarted for the key to take effect.</p>
 */
@Service
public class LicenseKeyInstaller {

    private static final Logger log = LoggerFactory.getLogger(LicenseKeyInstaller.class);

    /** Where the runtime admin UI stores a key when LICENSE_KEY_FILE is not configured. */
    public static final String DEFAULT_KEY_FILE = "/app/data/license.key";

    /** Mirrors the limit of {@link LicenseService}: a larger file is not a licence token. */
    private static final long MAX_KEY_BYTES = 64L * 1024L;

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private final LicenseProperties properties;
    private final ObjectMapper mapper;
    private final LicenseService license;

    public LicenseKeyInstaller(LicenseProperties properties, ObjectMapper mapper,
                               LicenseService license) {
        this.properties = properties;
        this.mapper = mapper;
        this.license = license;
    }

    /**
     * A warning the page can translate: the code is stable, the message is the English fallback
     * for clients that do not translate it.
     */
    public record Warning(String code, String message) {
    }

    /**
     * What the caller may see after a successful install. {@code warnings} explains everything the
     * operator has to fix on the host (an environment key that wins again after a restart, a
     * missing {@code LICENSE_KEY_FILE}, a key that is already expired).
     */
    public record InstallResult(String keyFile, List<Warning> warnings,
                                LicenseService.LicenseStatus license) {
    }

    /**
     * Verifies, stores and activates a licence key.
     *
     * @throws ApiException 400 when the key does not verify, 409 when the installation cannot
     *                      verify any key (no secret, no instance id), 500 when the file cannot be
     *                      written
     */
    public InstallResult install(String licenseKey) {
        String key = normalize(licenseKey);
        LicensePayload payload = verify(key);
        Path target = keyFile();
        List<Warning> warnings = new ArrayList<>();

        String inlineKey = properties.getKey();
        boolean inlineConfigured = inlineKey != null && !inlineKey.isBlank();
        boolean keyFileConfigured = properties.getKeyFile() != null
                && !properties.getKeyFile().isBlank();

        writeKey(target, key);

        // The newly stored file has to become the key this process reads. An inline LICENSE_KEY
        // would win in LicenseService.readKey(), so it is cleared for the running instance; the
        // warning below tells the operator that the environment wins again after a restart.
        if (inlineConfigured) {
            properties.setKey("");
        }
        if (!keyFileConfigured) {
            properties.setKeyFile(target.toString());
        }

        license.reload();
        LicenseService.LicenseStatus status = license.status();
        if (status.state() == LicenseState.LICENSE_MISSING) {
            // Verification passed, so this is a file or environment race, not a bad key. Report it
            // instead of answering "installed" while the installation still cannot run agents.
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "The licence key was stored but the running instance could not load it",
                    List.of(new ApiErrorResponse.FieldViolation("licenseKey", "NOT_LOADED",
                            "The key was written to " + target + " but reload reported: "
                                    + status.reason())));
        }

        if (inlineConfigured) {
            warnings.add(new Warning("ENV_KEY_OVERRIDES",
                    "LICENSE_KEY is also configured in the environment. It takes precedence again "
                            + "after a restart: remove LICENSE_KEY or set LICENSE_KEY_FILE="
                            + target + " so this key survives."));
        }
        if (!keyFileConfigured) {
            warnings.add(new Warning("KEY_FILE_NOT_CONFIGURED",
                    "LICENSE_KEY_FILE was not configured. Set LICENSE_KEY_FILE=" + target
                            + " so the key is read again after a restart."));
        }
        if (!properties.isEnforced()) {
            warnings.add(new Warning("ENFORCEMENT_DISABLED",
                    "Licence enforcement is disabled on this installation "
                            + "(BC_LICENSE_ENFORCEMENT=false); the key is stored but not enforced."));
        }
        if (status.state() == LicenseState.LICENSE_READ_ONLY) {
            warnings.add(new Warning("READ_ONLY",
                    "The key is valid but its licence expired beyond the grace period; "
                            + "agent execution stays read-only."));
        }
        log.info("Licence key installed at {} (licence {}, state {})", target, payload.licenseId(),
                status.state());
        return new InstallResult(target.toString(), List.copyOf(warnings), status);
    }

    private String normalize(String licenseKey) {
        if (licenseKey == null || licenseKey.isBlank()) {
            throw invalid("EMPTY", "The licence key is empty.");
        }
        String key = licenseKey.strip();
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw invalid("TOO_LARGE",
                    "The licence key is larger than 64 KiB; that is not a licence token.");
        }
        return key;
    }

    /**
     * Runs the same verification the runtime runs at startup. It needs the shared secret and the
     * instance id, because a key for another installation must not be stored as if it were valid.
     */
    private LicensePayload verify(String key) {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "This installation cannot verify a licence key: LICENSE_SECRET is not configured",
                    List.of(new ApiErrorResponse.FieldViolation("licenseKey", "LICENSE_SECRET_MISSING",
                            "LICENSE_SECRET is not configured on the server. A licence key cannot be "
                                    + "verified without it; ask the vendor for the secret that signed "
                                    + "your key.")));
        }
        if (secret.strip().length() < 32) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "This installation cannot verify a licence key: LICENSE_SECRET is too short",
                    List.of(new ApiErrorResponse.FieldViolation("licenseKey", "LICENSE_SECRET_TOO_SHORT",
                            "LICENSE_SECRET must be at least 32 characters; the key cannot be "
                                    + "verified with a shorter secret.")));
        }
        String instanceId = readInstanceId();
        if (instanceId == null) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "This installation cannot verify a licence key: the instance identifier is missing",
                    List.of(new ApiErrorResponse.FieldViolation("licenseKey", "INSTANCE_ID_MISSING",
                            "The instance identifier file is missing or empty ("
                                    + properties.getInstanceIdFile() + "). It is created by the "
                                    + "installer; without it a key cannot be bound to this server.")));
        }
        try {
            return LicenseTokenVerifier.verify(key, secret, instanceId, mapper);
        }
        catch (LicenseTokenVerifier.InvalidLicenseException ex) {
            // The verifier's reason is safe for the operator: it names the failed check, never the
            // key, the signature or the secret.
            throw invalid(ex.reason().name(), ex.getMessage());
        }
    }

    private Path keyFile() {
        String configured = properties.getKeyFile();
        String value = configured == null || configured.isBlank()
                ? DEFAULT_KEY_FILE : configured.strip();
        try {
            return Path.of(value).toAbsolutePath().normalize();
        }
        catch (InvalidPathException ex) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "The configured licence key path is not a valid file path",
                    List.of(new ApiErrorResponse.FieldViolation("licenseKey", "INVALID_KEY_FILE",
                            "LICENSE_KEY_FILE is not a usable path: " + value)));
        }
    }

    private String readInstanceId() {
        String file = properties.getInstanceIdFile();
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path) || Files.size(path) > 4096) {
                return null;
            }
            String value = Files.readString(path, StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? null : value;
        }
        catch (IOException | InvalidPathException ex) {
            return null;
        }
    }

    /** Writes the key through a temporary file in the same directory, then renames it into place. */
    private void writeKey(Path target, String key) {
        Path directory = target.getParent();
        Path temporary = null;
        try {
            if (directory == null) {
                throw new IOException("the path has no directory");
            }
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".license-", ".tmp");
            Files.writeString(temporary, key + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            restrict(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            // A pre-existing file keeps its own mode across REPLACE_EXISTING on some file systems.
            restrict(target);
        }
        catch (IOException | SecurityException ex) {
            log.warn("Licence key file {} could not be written: {}", target,
                    ex.getClass().getSimpleName());
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "The licence key file could not be written",
                    List.of(new ApiErrorResponse.FieldViolation("licenseKey", "KEY_FILE_NOT_WRITABLE",
                            "The server could not write " + target + " ("
                                    + ex.getClass().getSimpleName() + "). The installation's data "
                                    + "directory must be writable by the API user.")));
        }
        finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                }
                catch (IOException ignored) {
                    // The rename already moved it; a leftover temp file is harmless.
                }
            }
        }
    }

    private void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        }
        catch (UnsupportedOperationException ex) {
            // Windows/NTFS: POSIX modes do not exist. The directory's ACL decides.
            log.debug("POSIX permissions are not supported on this file system");
        }
        catch (IOException ex) {
            log.warn("Could not restrict licence key permissions to 0600: {}",
                    ex.getClass().getSimpleName());
        }
    }

    private static ApiException invalid(String code, String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message,
                List.of(new ApiErrorResponse.FieldViolation("licenseKey", code, message)));
    }
}
