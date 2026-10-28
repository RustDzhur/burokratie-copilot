package de.buerokratiecopilot.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Verifies the runtime licence and answers whether agent calls are allowed.
 *
 * <p>The service never fails startup because of a licence: an unlicensed installation starts in
 * {@code LICENSE_MISSING} so that the operator can reach health, authentication and the licence
 * status endpoint, read the reason, and install a key. What is blocked is execution, not the
 * diagnosis. The same reasoning applies to an expired key: it does not switch the product off
 * overnight; the fourteen-day grace window runs, then the installation becomes read-only.</p>
 *
 * <p><strong>Licences are perpetual by default.</strong> A key without {@code expiresAt} is
 * permanently {@code LICENSE_ACTIVE}: no grace window, no renewal schedule, no expiry warnings.
 * Only keys that carry {@code expiresAt} keep the expiry/grace/read-only behaviour.</p>
 *
 * <p><strong>Revocation.</strong> The signed key alone cannot be un-issued, so the installation
 * asks the vendor's cabinet ({@code LICENSE_CHECK_URL}) every six hours whether the licence is
 * still valid. A revocation is a hard stop for agent calls (402) with no grace period. When the
 * cabinet is unreachable the last known answer is kept and a warning is logged: a vendor outage
 * must not switch a customer's installation off. The answer is cached in
 * {@code /app/data/license-state.json} so a restart during an outage does not forget it.</p>
 *
 * <p>The key is read once at startup. Replacing it is a documented restart, not a hot reload:
 * a licence file that changes under a running process would make two nodes of the same
 * installation disagree about what they are allowed to do.</p>
 */
@Service
public class LicenseService {

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    /** A licence file larger than this is not a licence token; it is a mistake. */
    private static final long MAX_LICENSE_FILE_BYTES = 64 * 1024;

    private final LicenseProperties properties;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final LicenseStatusClient statusClient;
    private final LicenseStateStore stateStore;

    private volatile Loaded loaded;

    /** The last verdict the cabinet gave, or the cached one when it cannot be asked. */
    private volatile LicenseRevocation revocation = LicenseRevocation.unknown();

    public LicenseService(LicenseProperties properties, ObjectMapper mapper, JdbcTemplate jdbc,
                          LicenseStatusClient statusClient, LicenseStateStore stateStore) {
        this.properties = properties;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.statusClient = statusClient;
        this.stateStore = stateStore;
    }

    /** The immutable result of reading the key and its environment at one point in time. */
    private record Loaded(boolean enforced, LicensePayload payload, String missingReason,
                          Instant loadedAt) {
    }

    /**
     * What a client may see about the licence. It contains no key material: the token is a
     * credential for the vendor's relationship with the customer, not for the API.
     */
    public record LicenseStatus(
            LicenseState state,
            String reason,
            String licenseId,
            String customer,
            String instanceId,
            String plan,
            int maxAgents,
            long maxRunsPerMonth,
            List<String> features,
            Instant issuedAt,
            Instant expiresAt,
            long daysRemaining,
            long graceDaysRemaining,
            boolean readOnly,
            Instant checkedAt,
            boolean perpetual,
            Instant revokedAt,
            String revocationReason,
            Instant revocationCheckedAt) {
    }

    @PostConstruct
    void loadAtStartup() {
        reload();
        LicenseStatus status = status();
        switch (status.state()) {
            case LICENSE_NOT_ENFORCED -> log.debug("licence enforcement is off");
            case LICENSE_ACTIVE -> log.info("Licence active: licence {}, customer {}, plan {}, {}",
                    status.licenseId(), status.customer(), status.plan(),
                    status.perpetual() ? "perpetual" : "expires " + status.expiresAt());
            case LICENSE_GRACE -> log.warn("Licence expired on {}; grace period ends in {} day(s). "
                            + "Replace the key before then or the installation becomes read-only.",
                    status.expiresAt(), status.graceDaysRemaining());
            case LICENSE_READ_ONLY -> log.warn("Licence expired on {} and the grace period is over; "
                    + "agent execution is read-only until a valid key is installed.", status.expiresAt());
            case LICENSE_REVOKED -> log.warn("Licence {} was revoked{}; agent execution is disabled. "
                            + "Install a new key to continue.",
                    status.licenseId(),
                    status.revokedAt() == null ? "" : " on " + status.revokedAt());
            case LICENSE_MISSING -> log.warn("No usable runtime licence ({}). Agent endpoints answer 402; "
                    + "health and /api/v1/auth/* stay available.", status.reason());
        }
    }

    /** Re-reads the configured key. Public so the tests and an operator tool can re-check without waiting for a restart. */
    public synchronized void reload() {
        revocation = LicenseRevocation.unknown();
        if (!properties.isEnforced()) {
            loaded = new Loaded(false, null, null, Instant.now());
            return;
        }
        String key = readKey();
        if (key == null) {
            loaded = new Loaded(true, null, "LICENSE_KEY / LICENSE_KEY_FILE is not configured",
                    Instant.now());
            return;
        }
        if (properties.getSecret() == null || properties.getSecret().isBlank()) {
            loaded = new Loaded(true, null, "LICENSE_SECRET is not configured",
                    Instant.now());
            return;
        }
        if (properties.getSecret().strip().length() < 32) {
            // The minting utility enforces the same minimum. A short HMAC secret
            // is a configuration error, not a licence that can be checked.
            loaded = new Loaded(true, null,
                    "LICENSE_SECRET must be at least 32 characters", Instant.now());
            return;
        }
        String instanceId = readInstanceId();
        if (instanceId == null) {
            loaded = new Loaded(true, null,
                    "the instance identifier file is missing or empty: " + properties.getInstanceIdFile(),
                    Instant.now());
            return;
        }
        try {
            LicensePayload payload = LicenseTokenVerifier.verify(key, properties.getSecret(),
                    instanceId, mapper);
            // A cached revocation only applies to the licence it was written for.
            revocation = stateStore.read(payload.licenseId(), payload.instanceId())
                    .orElse(LicenseRevocation.unknown());
            if (revocation.revoked()) {
                log.warn("Licence {} is marked revoked in the cached state ({}); agent execution "
                                + "stays disabled until the cabinet says otherwise",
                        payload.licenseId(),
                        revocation.checkedAt() == null ? "unknown time" : revocation.checkedAt());
            }
            loaded = new Loaded(true, payload, null, Instant.now());
        }
        catch (LicenseTokenVerifier.InvalidLicenseException ex) {
            // The reason is safe to log; the token, the signature and the secret are not.
            loaded = new Loaded(true, null, ex.reason() + ": " + ex.getMessage(), Instant.now());
        }
    }

    /**
     * Asks the cabinet whether the licence is still valid. Runs every six hours
     * ({@code burokratie.license.check-interval-ms}, default 21600000 ms) and once shortly after
     * startup. A failure keeps the last known state and logs a warning: the vendor's console being
     * down is not a reason to stop a customer's installation.
     */
    @Scheduled(initialDelayString = "${burokratie.license.check-initial-delay-ms:60000}",
            fixedDelayString = "${burokratie.license.check-interval-ms:21600000}")
    public void refreshRevocation() {
        if (!properties.isEnforced()) {
            return;
        }
        Loaded current = loaded;
        if (current == null || current.payload() == null) {
            return;
        }
        if (!statusClient.isConfigured()) {
            log.debug("LICENSE_CHECK_URL is not configured; the licence is trusted by signature only");
            return;
        }
        LicensePayload payload = current.payload();
        try {
            LicenseStatusClient.Answer answer = statusClient.check(payload.licenseId(),
                    payload.instanceId());
            LicenseRevocation next = new LicenseRevocation(!answer.active(), answer.revokedAt(),
                    answer.reason(), Instant.now());
            boolean changed = next.revoked() != revocation.revoked();
            revocation = next;
            stateStore.write(payload.licenseId(), payload.instanceId(), next);
            if (changed && next.revoked()) {
                log.warn("Licence {} was revoked by the vendor{}. Agent calls now answer 402.",
                        payload.licenseId(),
                        answer.reason() == null || answer.reason().isBlank()
                                ? "" : ": " + answer.reason());
            }
            else if (changed) {
                log.info("Licence {} is valid again according to the vendor console.",
                        payload.licenseId());
            }
        }
        catch (LicenseStatusClient.UnavailableException ex) {
            // Keep the last known state. For a perpetual licence that is "active", which is the
            // behaviour the customer paid for; for a revoked licence it stays revoked.
            log.warn("Licence status check failed ({}); keeping the last known state ({}). "
                            + "Agent execution is not blocked by a vendor-console outage.",
                    ex.getMessage(), revocation.revoked() ? "revoked" : "active");
        }
    }

    /** The current status, computed against the current clock so expiry happens without a restart. */
    public LicenseStatus status() {
        Loaded current = loaded == null ? new Loaded(properties.isEnforced(), null,
                "the licence has not been loaded yet", Instant.now()) : loaded;
        Instant now = Instant.now();
        LicenseRevocation currentRevocation = revocation;
        if (!current.enforced()) {
            return new LicenseStatus(LicenseState.LICENSE_NOT_ENFORCED, null, null, null, null,
                    null, 0, 0, List.of(), null, null, 0, 0, false, now, false,
                    null, null, currentRevocation.checkedAt());
        }
        if (current.payload() == null) {
            return new LicenseStatus(LicenseState.LICENSE_MISSING, current.missingReason(), null,
                    null, readInstanceIdQuietly(), null, 0, 0, List.of(), null, null, 0, 0, false, now,
                    false, null, null, currentRevocation.checkedAt());
        }
        LicensePayload payload = current.payload();
        LicenseState state;
        if (currentRevocation.revoked()) {
            // A revoked key is stopped immediately: no grace window, no read-only phase.
            state = LicenseState.LICENSE_REVOKED;
        }
        else {
            state = LicenseTokenVerifier.stateFor(payload, now, properties.getGraceDays());
        }
        Instant expires = payload.expiresAtInstant();
        long daysRemaining = expires == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(now, expires);
        Instant graceEnd = expires == null ? null
                : expires.plusSeconds(Math.max(0, properties.getGraceDays()) * 86_400L);
        long graceDaysRemaining = state == LicenseState.LICENSE_GRACE && graceEnd != null
                ? Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(now, graceEnd)) : 0;
        return new LicenseStatus(state, null, payload.licenseId(), payload.customer(),
                payload.instanceId(), payload.plan(), payload.maxAgents(),
                payload.maxRunsPerMonth(), payload.features(), payload.issuedAtInstant(), expires,
                daysRemaining, graceDaysRemaining, state.readOnly(), now, payload.perpetual(),
                currentRevocation.revokedAt(), currentRevocation.reason(),
                currentRevocation.checkedAt());
    }

    /** True when a licence is enforced at all; the guard filters are no-ops otherwise. */
    public boolean isEnforced() {
        return properties.isEnforced();
    }

    /**
     * Called before every agent run. A missing licence is a 402 for the agent surface; a revoked
     * licence is a 402 with its own code, because the operator's fix is different (install a new
     * key, not wait for a grace period). A run count above the licensed monthly allowance is a 402
     * with a third code, so a client can tell "buy a licence" from "the plan's run budget is
     * exhausted".
     */
    public void assertRunAllowed(UUID tenantId) {
        if (!properties.isEnforced()) {
            return;
        }
        LicenseStatus status = status();
        if (status.state() == LicenseState.LICENSE_REVOKED) {
            throw new ApiException(ErrorCode.LICENSE_REVOKED,
                    "This runtime licence was revoked; agent execution is disabled");
        }
        if (status.state() == LicenseState.LICENSE_READ_ONLY) {
            throw new ApiException(ErrorCode.LICENSE_READ_ONLY,
                    "The runtime licence expired and the grace period is over; agent execution is read-only");
        }
        if (!status.state().agentCallsAllowed()) {
            throw new ApiException(ErrorCode.LICENSE_MISSING,
                    "No valid runtime licence is installed; agent execution is disabled");
        }
        long limit = status.maxRunsPerMonth();
        if (limit <= 0 || tenantId == null) {
            return;
        }
        Long used = jdbc.queryForObject("""
                select count(*) from agent_runs
                where tenant_id = ? and started_at >= date_trunc('month', now())
                """, Long.class, tenantId);
        if (used != null && used >= limit) {
            throw new ApiException(ErrorCode.LICENSE_RUN_LIMIT,
                    "The monthly agent run allowance of " + limit + " is exhausted");
        }
    }

    private String readKey() {
        String inline = properties.getKey();
        if (inline != null && !inline.isBlank()) {
            return inline.strip();
        }
        String file = properties.getKeyFile();
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_LICENSE_FILE_BYTES) {
                return null;
            }
            String value = Files.readString(path, StandardCharsets.UTF_8).strip();
            return value.isEmpty() ? null : value;
        }
        catch (IOException ex) {
            log.warn("Licence file {} could not be read: {}", file, ex.getClass().getSimpleName());
            return null;
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
        catch (IOException ex) {
            return null;
        }
    }

    private String readInstanceIdQuietly() {
        try {
            return readInstanceId();
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    /** For the installer and the status page: never the key or the secret. */
    public Long secondsSinceLoaded() {
        Loaded current = loaded;
        return current == null ? null : Duration.between(current.loadedAt(), Instant.now()).toSeconds();
    }
}
