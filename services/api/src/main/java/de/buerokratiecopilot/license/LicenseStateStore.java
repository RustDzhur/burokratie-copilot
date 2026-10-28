package de.buerokratiecopilot.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Caches the last revocation answer on disk, next to the other installation data.
 *
 * <p>Without the cache a restart during a cabinet outage would forget a revocation and hand a
 * revoked installation its agent surface back. The file therefore keeps the verdict, the
 * identifiers it belongs to and when it was received. A snapshot whose {@code licenseId} or
 * {@code instanceId} does not match the installed key is ignored: a replacement licence must not
 * inherit the revocation of the old one.</p>
 *
 * <p>The file contains no secret and no key — only the licence id, the instance id and the
 * verdict, all of which are already visible to an operator.</p>
 */
@Component
public class LicenseStateStore {

    private static final Logger log = LoggerFactory.getLogger(LicenseStateStore.class);
    private static final int FORMAT_VERSION = 1;

    private final LicenseProperties properties;
    private final ObjectMapper json;

    public LicenseStateStore(LicenseProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    private Path path() {
        String configured = properties.getStateFile();
        return Path.of(configured == null || configured.isBlank()
                ? "/app/data/license-state.json" : configured);
    }

    /** The cached verdict for exactly this licence and installation, if one was written. */
    public Optional<LicenseRevocation> read(String licenseId, String instanceId) {
        Path file = path();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) > 64 * 1024) {
                log.warn("Licence state cache {} is larger than a licence state; ignoring it", file);
                return Optional.empty();
            }
            JsonNode root = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            String cachedLicenseId = root.path("licenseId").asText(null);
            String cachedInstanceId = root.path("instanceId").asText(null);
            if (licenseId == null || !licenseId.equals(cachedLicenseId)
                    || instanceId == null || !instanceId.equals(cachedInstanceId)) {
                log.info("Licence state cache belongs to another licence or installation; ignoring it");
                return Optional.empty();
            }
            if (!root.has("revoked") || !root.get("revoked").isBoolean()) {
                return Optional.empty();
            }
            return Optional.of(new LicenseRevocation(
                    root.get("revoked").asBoolean(),
                    instant(root.path("revokedAt").asText(null)),
                    root.path("reason").asText(null),
                    instant(root.path("checkedAt").asText(null))));
        }
        catch (IOException | RuntimeException ex) {
            log.warn("Licence state cache {} could not be read: {}", file,
                    ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Writes the verdict atomically and with owner-only permissions. Failure is logged, never fatal. */
    public void write(String licenseId, String instanceId, LicenseRevocation revocation) {
        Path file = path();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", FORMAT_VERSION);
        body.put("licenseId", licenseId);
        body.put("instanceId", instanceId);
        body.put("revoked", revocation.revoked());
        body.put("revokedAt", revocation.revokedAt() == null ? null : revocation.revokedAt().toString());
        body.put("reason", revocation.reason());
        body.put("checkedAt", revocation.checkedAt() == null ? null : revocation.checkedAt().toString());
        Path temp = null;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temp = (parent == null ? Path.of(".") : parent)
                    .resolve(".license-state." + UUID.randomUUID() + ".tmp");
            Files.writeString(temp, json.writeValueAsString(body), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            }
            catch (UnsupportedOperationException | IOException ex) {
                log.debug("Could not tighten permissions of {}", temp);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        }
        catch (IOException | RuntimeException ex) {
            log.warn("Could not persist the licence state to {}: {}", file,
                    ex.getClass().getSimpleName());
        }
        finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                }
                catch (IOException ex) {
                    log.debug("Could not remove temporary licence state file");
                }
            }
        }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        }
        catch (RuntimeException ex) {
            return null;
        }
    }
}
