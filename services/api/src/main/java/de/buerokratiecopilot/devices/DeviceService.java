package de.buerokratiecopilot.devices;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The device registry (spec §22, G-001).
 *
 * <p>Devices are the one part of the platform that sits outside it, on somebody's desk or in a copy room,
 * and they are reached by a <strong>pull</strong>: a device asks for work, nothing connects to it. The
 * specification requires that no port is opened on the device, and that shapes everything here — the
 * platform cannot call a device, so a device has to identify itself every time it asks.</p>
 *
 * <p><strong>Registering a device is not authorising it.</strong> A device that has just appeared is
 * {@code PENDING} and receives nothing until a person activates it. A scanner that registers itself is
 * still an unknown machine in the building, and the jobs it would receive contain documents.</p>
 *
 * <p><strong>A revoked device loses access at once.</strong> Not at the next token refresh, not at the
 * end of a session: the key is checked on every request, and a revoked device is refused on the request
 * that follows. That is the only useful meaning of revocation for a device that may have been stolen.</p>
 *
 * <p>The API key is shown once and stored only as a hash, like the ingest tokens. A platform that kept
 * the key could hand out a device's identity from its own database.</p>
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    /** The statuses in which a device may receive work. */
    private static final List<String> WORKING_STATUSES = List.of("ACTIVE");

    private final JdbcTemplate jdbc;

    public DeviceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A newly registered device, with the key that exists only in this answer. */
    public record Registered(UUID deviceId, String identifier, String status, String apiKey) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("deviceId", deviceId.toString());
            map.put("identifier", identifier);
            map.put("status", status);
            map.put("apiKey", apiKey);
            map.put("note", "Dieser Schlüssel wird nur einmal angezeigt; gespeichert wird nur sein Hash. "
                    + "Das Gerät ist noch nicht freigegeben.");
            return map;
        }
    }

    /** An authenticated device. */
    public record Device(UUID deviceId, UUID tenantId, String identifier, String name, String deviceType,
                         String status) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("deviceId", deviceId.toString());
            map.put("identifier", identifier);
            map.put("name", name);
            map.put("deviceType", deviceType);
            map.put("status", status);
            return map;
        }
    }

    /**
     * Registers a device.
     *
     * <p>The device starts as {@code PENDING}: it exists, and it may not do anything yet.</p>
     */
    @Transactional
    public Registered register(UUID tenantId, String identifier, String name, String deviceType,
                               String connectionType, UUID registeredBy) {
        if (identifier == null || identifier.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Gerät braucht eine Kennung");
        }
        if (name == null || name.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Gerät braucht einen Namen");
        }

        String type = deviceType == null ? "SCANNER" : deviceType.toUpperCase(java.util.Locale.ROOT);
        String connection = connectionType == null ? "PULL"
                : connectionType.toUpperCase(java.util.Locale.ROOT);

        String apiKey = newApiKey();
        UUID deviceId = jdbc.queryForObject("""
                insert into devices (id, tenant_id, identifier, name, device_type, connection_type,
                                     status, api_key_hash, capabilities, registered_by, registered_at)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, 'PENDING', ?, '{}'::jsonb, ?, now())
                returning id
                """, UUID.class, tenantId, identifier.strip(), name.strip(), type, connection,
                hash(apiKey), registeredBy);

        log.info("Device {} registered for tenant {} as {}", deviceId, tenantId, type);
        return new Registered(deviceId, identifier.strip(), "PENDING", apiKey);
    }

    /**
     * Activates a device.
     *
     * <p>A person decides that this machine may receive documents. Until then it is a machine in the
     * building that the platform happens to know about.</p>
     */
    @Transactional
    public void activate(UUID tenantId, UUID deviceId, UUID activatedBy) {
        if (activatedBy == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Eine Freigabe braucht eine Person; sonst gibt niemand ein Gerät frei");
        }
        int updated = jdbc.update("""
                update devices set status = 'ACTIVE', updated_at = now()
                where id = ? and tenant_id = ? and status = 'PENDING'
                """, deviceId, tenantId);
        if (updated == 0) {
            throw ApiException.conflict("Dieses Gerät kann nicht freigegeben werden; es ist nicht im "
                    + "Status PENDING");
        }
        log.info("Device {} activated by {}", deviceId, activatedBy);
    }

    /**
     * Revokes a device.
     *
     * <p>From the next request on, the key does not work. A device that has been stolen is not asked to
     * stop using its key; it is refused.</p>
     */
    @Transactional
    public void revoke(UUID tenantId, UUID deviceId, UUID revokedBy, String reason) {
        int updated = jdbc.update("""
                update devices set status = 'REVOKED', revoked_at = now(), revoked_by = ?,
                       last_error = ?, updated_at = now()
                where id = ? and tenant_id = ?
                """, revokedBy, reason, deviceId, tenantId);
        if (updated == 0) {
            throw ApiException.notFound("device");
        }
        // Jobs the device had claimed are freed rather than left with it: work assigned to a machine that
        // may no longer work is work that would otherwise never be done.
        jdbc.update("""
                update device_jobs set status = 'QUEUED', claimed_at = null, started_at = null
                where device_id = ? and tenant_id = ? and status in ('CLAIMED', 'RUNNING')
                """, deviceId, tenantId);
        log.info("Device {} revoked by {}: {}", deviceId, revokedBy, reason);
    }

    /**
     * Authenticates a device by its key.
     *
     * @throws ApiException when the key is unknown, or the device may not work
     */
    @Transactional
    public Device authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            throw unauthorized("Kein Geräteschlüssel übergeben");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, tenant_id, identifier, name, device_type, status from devices
                where api_key_hash = ?
                """, hash(presentedKey));
        if (rows.isEmpty()) {
            throw unauthorized("Der Geräteschlüssel ist unbekannt");
        }
        Map<String, Object> row = rows.getFirst();
        String status = String.valueOf(row.get("status"));

        if (!WORKING_STATUSES.contains(status)) {
            // PENDING, REVOKED, UNSUPPORTED and the rest all mean the same thing to a caller: this device
            // does not receive work, and the reason is not its business to guess at.
            throw unauthorized("Das Gerät ist im Status " + status + " und erhält keine Aufträge");
        }

        jdbc.update("update devices set last_seen_at = now(), updated_at = now() where id = ?",
                row.get("id"));
        return new Device((UUID) row.get("id"), (UUID) row.get("tenant_id"),
                String.valueOf(row.get("identifier")), String.valueOf(row.get("name")),
                String.valueOf(row.get("device_type")), status);
    }

    /**
     * Whether a device may receive work.
     *
     * <p>Asked before every hand-over rather than only at authentication: a device may have been revoked
     * between the two.</p>
     */
    @Transactional(readOnly = true)
    public boolean mayReceiveWork(UUID tenantId, UUID deviceId) {
        List<String> statuses = jdbc.query("""
                select status from devices where id = ? and tenant_id = ?
                """, (rs, row) -> rs.getString("status"), deviceId, tenantId);
        return !statuses.isEmpty() && WORKING_STATUSES.contains(statuses.getFirst());
    }

    /** Queues a job for a device. */
    @Transactional
    public UUID queueJob(UUID tenantId, UUID deviceId, String jobType, String parameters,
                         UUID createdBy) {
        String type = jobType == null ? "PING" : jobType.toUpperCase(java.util.Locale.ROOT);
        return jdbc.queryForObject("""
                insert into device_jobs (id, device_id, tenant_id, job_type, status, parameters, priority,
                                         attempt, max_attempts, created_by)
                values (gen_random_uuid(), ?, ?, ?, 'QUEUED', ?::jsonb, 5, 0, 3, ?)
                returning id
                """, UUID.class, deviceId, tenantId, type,
                parameters == null || parameters.isBlank() ? "{}" : parameters, createdBy);
    }

    /**
     * Hands the next job to a device.
     *
     * <p>The device pulls; nothing is pushed to it, because nothing can reach it. The job is claimed with
     * an update that also checks the status, so two devices asking at the same moment cannot both receive
     * the same job — which is the only reason a pull model is safe here.</p>
     *
     * @return the job, or empty when there is nothing to do
     */
    @Transactional
    public Map<String, Object> claimNext(UUID tenantId, UUID deviceId) {
        if (!mayReceiveWork(tenantId, deviceId)) {
            throw unauthorized("Dieses Gerät ist nicht freigegeben und erhält keine Aufträge");
        }

        List<UUID> candidates = jdbc.query("""
                select id from device_jobs
                where tenant_id = ? and device_id = ? and status = 'QUEUED'
                  and (expires_at is null or expires_at > now())
                order by priority, created_at
                limit 1
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, deviceId);
        if (candidates.isEmpty()) {
            return Map.of();
        }

        UUID jobId = candidates.getFirst();
        int claimed = jdbc.update("""
                update device_jobs set status = 'CLAIMED', claimed_at = now(),
                       attempt = attempt + 1, updated_at = now()
                where id = ? and tenant_id = ? and status = 'QUEUED'
                """, jobId, tenantId);
        if (claimed == 0) {
            // Another device took it between the read and the update; the caller simply asks again.
            return Map.of();
        }

        Map<String, Object> job = new LinkedHashMap<>(jdbc.queryForMap("""
                select id, job_type, parameters, priority, attempt, max_attempts, expires_at
                from device_jobs where id = ?
                """, jobId));
        job.put("jobId", job.get("id"));
        job.put("jobType", job.get("job_type"));
        job.put("deviceId", deviceId.toString());
        log.info("Job {} claimed by device {}", jobId, deviceId);
        return job;
    }

    /** Records how a job went. */
    @Transactional
    public void finishJob(UUID tenantId, UUID deviceId, UUID jobId, String status, String errorCode,
                          String errorMessage) {
        int updated = jdbc.update("""
                update device_jobs set status = ?, finished_at = now(), error_code = ?, error_message = ?,
                       updated_at = now()
                where id = ? and device_id = ? and tenant_id = ? and status in ('CLAIMED', 'RUNNING')
                """, status, errorCode, errorMessage, jobId, deviceId, tenantId);
        if (updated == 0) {
            throw ApiException.conflict("Dieser Auftrag gehört nicht zu diesem Gerät oder ist bereits "
                    + "abgeschlossen");
        }
    }

    /**
     * Reports what the device did with a job: the scan it produced, or why it produced none.
     *
     * <p><strong>Both outcomes are recorded.</strong> A job that the device could not carry out - no paper,
     * paper jam, device switched off - has to come back as a finished job with a reason, otherwise it stays
     * QUEUED for ever and the person in front of the scanner sees a spinner that never ends.</p>
     *
     * <p><strong>A rejected upload keeps the job claimable again.</strong> The sheet has already gone through
     * the device; losing the file because the answer was slow would mean scanning again, and the paper is back
     * in the pile. The job returns to QUEUED so the device can offer the result once more.</p>
     */
    @Transactional
    public Map<String, Object> reportResult(UUID tenantId, UUID deviceId, UUID jobId, boolean succeeded,
                                            String errorCode, String errorMessage, UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, status from device_jobs
                 where id = ? and device_id = ? and tenant_id = ?
                """, jobId, deviceId, tenantId);
        if (rows.isEmpty()) {
            throw unauthorized("Dieser Auftrag gehört nicht zu diesem Gerät");
        }
        String status = String.valueOf(rows.getFirst().get("status"));
        if (!"CLAIMED".equals(status) && !"QUEUED".equals(status)) {
            // A finished job is finished: a device that calls twice gets the same answer, not a second run.
            return Map.of("jobId", jobId.toString(), "status", status, "note", "Der Auftrag ist bereits abgeschlossen");
        }

        if (succeeded) {
            jdbc.update("""
                    update device_jobs
                       set status = 'COMPLETED', finished_at = now(), updated_at = now(),
                           document_id = ?, error_code = null, error_message = null
                     where id = ? and tenant_id = ?
                    """, documentId, jobId, tenantId);
            return Map.of("jobId", jobId.toString(), "status", "COMPLETED");
        }

        boolean retryable = jobBelongsToRetryableFailure(errorCode);
        jdbc.update("""
                update device_jobs
                   set status = ?, finished_at = case when ? then null else now() end,
                       updated_at = now(), error_code = ?, error_message = ?,
                       claimed_at = case when ? then null else claimed_at end
                 where id = ? and tenant_id = ?
                """, retryable ? "QUEUED" : "FAILED", retryable, errorCode, errorMessage, retryable,
                jobId, tenantId);
        return Map.of("jobId", jobId.toString(), "status", retryable ? "QUEUED" : "FAILED",
                "note", retryable ? "Der Auftrag liegt wieder bereit" : "Der Auftrag ist fehlgeschlagen");
    }

    /**
     * Whether the failure is one a second attempt could survive.
     *
     * <p>A missing scanner or an empty feeder is worth retrying later; a document the platform rejected is
     * not, and retrying it would only repeat the error.</p>
     */
    private static boolean jobBelongsToRetryableFailure(String errorCode) {
        if (errorCode == null) {
            return true;
        }
        return switch (errorCode) {
            case "NO_PAPER", "DEVICE_BUSY", "JAMMED", "OFFLINE", "UPLOAD_FAILED" -> true;
            default -> false;
        };
    }

    /**
     * Records that the agent is alive, and what it is.
     *
     * <p>The version and operating system are what a person looks at when a specific workstation misbehaves;
     * without them every problem is "the scanner does not work" on a fleet of identical machines.</p>
     */
    @Transactional
    public void heartbeat(UUID tenantId, UUID deviceId, String version, String osName) {
        jdbc.update("""
                update devices
                   set last_seen_at = now(), updated_at = now(),
                       app_version = coalesce(?, app_version),
                       os_name = coalesce(?, os_name)
                 where id = ? and tenant_id = ?
                """, version, osName, deviceId, tenantId);
    }

    /** The devices of a tenant, without any secret. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId) {
        return jdbc.queryForList("""
                select id, identifier, name, device_type, connection_type, status, last_seen_at,
                       last_error, revoked_at,
                       (api_key_hash is not null) as has_key
                from devices where tenant_id = ? order by registered_at
                """, tenantId);
    }

    /** A device's own description, for its diagnostics. */
    @Transactional(readOnly = true)
    public Map<String, Object> describe(UUID tenantId, UUID deviceId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, identifier, name, device_type, connection_type, status, last_seen_at,
                       last_error, capabilities, app_version, os_name
                from devices where id = ? and tenant_id = ?
                """, deviceId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("device");
        }
        return new LinkedHashMap<>(rows.getFirst());
    }

    private static ApiException unauthorized(String message) {
        return new ApiException(ErrorCode.UNAUTHENTICATED, message);
    }

    /** A key that is shown once and stored only as a hash. */
    static String newApiKey() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return "bcdev_" + HexFormat.of().formatHex(bytes);
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }

    /** When a device was last seen, for a caller asking whether a machine is still alive. */
    @Transactional(readOnly = true)
    public Instant lastSeen(UUID tenantId, UUID deviceId) {
        List<java.sql.Timestamp> seen = jdbc.query("""
                select last_seen_at from devices where id = ? and tenant_id = ?
                """, (rs, row) -> rs.getTimestamp("last_seen_at"), deviceId, tenantId);
        return seen.isEmpty() || seen.getFirst() == null ? null : seen.getFirst().toInstant();
    }
}
