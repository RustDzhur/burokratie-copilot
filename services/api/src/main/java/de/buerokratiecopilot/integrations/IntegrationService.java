package de.buerokratiecopilot.integrations;

import java.time.Instant;
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
 * The integration hub: connecting external services and keeping their credentials (spec §29, E-001).
 *
 * <p>Two acceptances shape this class.</p>
 *
 * <p><strong>Tokens are stored encrypted.</strong> A credential is encrypted before it reaches the
 * database, and nothing here ever writes a secret in the clear. The test that checks this reads the
 * stored bytes, not the service's own report about them: a platform that claims to encrypt and does not
 * would pass any check that trusts it.</p>
 *
 * <p><strong>A disabled integration does not synchronise.</strong> The check is in the one method that
 * starts a run, rather than in each caller, because a guard that callers have to remember is a guard
 * that will be forgotten. A disabled integration is also one whose credentials are not handed out —
 * otherwise "disabled" would mean "not scheduled" and nothing more.</p>
 *
 * <p>Nothing invents a provider capability. An integration whose status says it is not supported stays
 * that way, and the platform says so rather than pretending a run might work.</p>
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    /** Statuses in which a synchronisation may run. */
    private static final List<String> RUNNABLE_STATUSES = List.of("CONNECTED", "DEGRADED");

    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;

    public IntegrationService(JdbcTemplate jdbc, CredentialCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** What connecting produced. */
    public record Connection(UUID integrationId, String status, boolean credentialsStored) {
    }

    /** A run of a synchronisation. */
    public record SyncRun(UUID runId, UUID integrationId, String status, Instant startedAt) {
    }

    /**
     * Registers or updates an integration and stores its credentials encrypted.
     *
     * @param provider   the provider key, e.g. {@code IMAP} or {@code BANK_AGGREGATOR}
     * @param category   what the integration is for
     * @param fields     the credential fields; each value is encrypted, none is stored as given
     * @param credentialType the shape of the credential, as the schema names it
     */
    @Transactional
    public Connection connect(UUID tenantId, UUID workspaceId, String provider, String category,
                              String displayName, String credentialType, Map<String, String> fields,
                              List<String> scopes, String connectedAccount, UUID userId) {
        if (provider == null || provider.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Integration braucht einen Anbieter");
        }
        UUID integrationId = jdbc.query("""
                select id from integrations where tenant_id = ? and provider = ?
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, provider).stream().findFirst()
                .orElse(null);

        if (integrationId == null) {
            integrationId = jdbc.queryForObject("""
                    insert into integrations (id, tenant_id, workspace_id, provider, category,
                                              display_name, status, config, granted_scopes,
                                              connected_account, connected_by, connected_at, is_enabled,
                                              consecutive_failures, requires_owner_action)
                    values (gen_random_uuid(), ?, ?, ?, ?, ?, 'CONNECTED', '{}'::jsonb,
                            ?::jsonb, ?, ?, now(), true, 0, false)
                    returning id
                    """, UUID.class, tenantId, workspaceId, provider, category,
                    displayName == null ? provider : displayName, toJsonArray(scopes), connectedAccount,
                    userId);
        }
        else {
            UUID existing = integrationId;
            jdbc.update("""
                    update integrations set status = 'CONNECTED', is_enabled = true,
                           connected_account = ?, connected_by = ?, connected_at = now(),
                           consecutive_failures = 0, requires_owner_action = false, last_error_code = null,
                           last_error_message = null, granted_scopes = ?::jsonb, updated_at = now()
                    where id = ? and tenant_id = ?
                    """, connectedAccount, userId, toJsonArray(scopes), existing, tenantId);
        }

        boolean stored = false;
        if (fields != null && !fields.isEmpty()) {
            // The secret is encrypted before it is written; the database never sees the plaintext.
            String encrypted = cipher.encryptFields(fields, tenantId, integrationId);
            jdbc.update("""
                    insert into integration_credentials (id, integration_id, tenant_id, credential_type,
                                                         encrypted_payload, key_version, scopes)
                    values (gen_random_uuid(), ?, ?, ?, ?, 1, ?::jsonb)
                    """, integrationId, tenantId, credentialType == null ? "OAUTH2" : credentialType,
                    encrypted, toJsonArray(scopes));
            stored = true;
        }

        log.info("Integration {} ({}) connected for tenant {}", provider, integrationId, tenantId);
        return new Connection(integrationId, "CONNECTED", stored);
    }

    /** Disconnects an integration: it keeps its record and loses the ability to run. */
    @Transactional
    public void disable(UUID tenantId, UUID integrationId, String reason) {
        int updated = jdbc.update("""
                update integrations set status = 'DISABLED', is_enabled = false, updated_at = now()
                where id = ? and tenant_id = ?
                """, integrationId, tenantId);
        if (updated == 0) {
            throw ApiException.notFound("integration");
        }
        log.info("Integration {} disabled: {}", integrationId, reason);
    }

    /**
     * Starts a synchronisation.
     *
     * <p>Refused unless the integration is connected. This is the acceptance criterion in code: there is
     * no path from a disabled integration to a run.</p>
     */
    @Transactional
    public SyncRun startSync(UUID tenantId, UUID integrationId, String runType, UUID userId) {
        Map<String, Object> integration = jdbc.query("""
                select status, is_enabled, provider from integrations where id = ? and tenant_id = ?
                """, rs -> rs.next() ? Map.of("status", rs.getString("status"), "enabled",
                        rs.getBoolean("is_enabled"), "provider", rs.getString("provider")) : null,
                integrationId, tenantId);
        if (integration == null) {
            throw ApiException.notFound("integration");
        }

        String status = String.valueOf(integration.get("status"));
        boolean enabled = Boolean.TRUE.equals(integration.get("enabled"));
        if (!enabled || !RUNNABLE_STATUSES.contains(status)) {
            throw ApiException.conflict("Die Integration ist " + status
                    + " und führt keine Synchronisierung aus. Erst verbinden, dann synchronisieren.");
        }

        UUID runId = jdbc.queryForObject("""
                insert into integration_sync_runs (id, integration_id, tenant_id, run_type, status,
                                                   items_processed, items_failed, details, started_at)
                values (gen_random_uuid(), ?, ?, ?, 'RUNNING', 0, 0, '{}'::jsonb, now())
                returning id
                """, UUID.class, integrationId, tenantId, runType == null ? "MANUAL" : runType);

        log.info("Sync run {} started for integration {}", runId, integrationId);
        return new SyncRun(runId, integrationId, "RUNNING", Instant.now());
    }

    /** Finishes a run and records how it went, including the failures. */
    @Transactional
    public void finishSync(UUID tenantId, UUID runId, String status, int processed, int failed,
                           String errorCode, String errorMessage) {
        jdbc.update("""
                update integration_sync_runs
                set status = ?, items_processed = ?, items_failed = ?, error_code = ?, error_message = ?,
                    finished_at = now()
                where id = ? and tenant_id = ?
                """, status, processed, failed, errorCode, errorMessage, runId, tenantId);

        // The integration's own state follows the run: a failure count that never resets would make the
        // status meaningless, and a success that does not clear it would leave an integration degraded
        // forever.
        if ("FAILED".equals(status)) {
            jdbc.update("""
                    update integrations set consecutive_failures = consecutive_failures + 1,
                           last_sync_status = 'FAILED', last_error_code = ?, last_error_message = ?,
                           last_sync_at = now(), status = 'ERROR', updated_at = now()
                    where id = (select integration_id from integration_sync_runs where id = ?)
                      and tenant_id = ?
                    """, errorCode, errorMessage, runId, tenantId);
        }
        else {
            jdbc.update("""
                    update integrations set consecutive_failures = 0, last_sync_status = ?,
                           last_error_code = null, last_error_message = null, last_sync_at = now(),
                           status = 'CONNECTED', updated_at = now()
                    where id = (select integration_id from integration_sync_runs where id = ?)
                      and tenant_id = ?
                    """, status, runId, tenantId);
        }
    }

    /**
     * The credential fields of an integration, decrypted for use.
     *
     * <p>Refused for a disabled integration: the credentials of something that may not run are not handed
     * out, or "disabled" would only mean "not scheduled".</p>
     */
    @Transactional(readOnly = true)
    public Map<String, String> credentials(UUID tenantId, UUID integrationId) {
        Map<String, Object> integration = jdbc.query("""
                select status, is_enabled from integrations where id = ? and tenant_id = ?
                """, rs -> rs.next() ? Map.of("status", rs.getString("status"), "enabled",
                        rs.getBoolean("is_enabled")) : null, integrationId, tenantId);
        if (integration == null) {
            throw ApiException.notFound("integration");
        }
        if (!Boolean.TRUE.equals(integration.get("enabled"))) {
            throw ApiException.conflict("Die Integration ist deaktiviert; ihre Zugangsdaten werden nicht "
                    + "herausgegeben");
        }

        List<String> payloads = jdbc.query("""
                select encrypted_payload from integration_credentials
                where integration_id = ? and tenant_id = ?
                order by created_at desc
                """, (rs, row) -> rs.getString("encrypted_payload"), integrationId, tenantId);
        if (payloads.isEmpty()) {
            throw ApiException.notFound("credentials of this integration");
        }
        return cipher.decryptFields(payloads.getFirst(), tenantId, integrationId);
    }

    /** What the platform knows about an integration, without any secret in it. */
    @Transactional(readOnly = true)
    public Map<String, Object> describe(UUID tenantId, UUID integrationId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select provider, category, display_name, status, is_enabled, last_sync_at,
                       last_sync_status, consecutive_failures, requires_owner_action, owner_action_hint,
                       last_error_code, last_error_message
                from integrations where id = ? and tenant_id = ?
                """, integrationId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("integration");
        }
        Map<String, Object> integration = rows.getFirst();
        Map<String, Object> described = new LinkedHashMap<>(integration);
        described.put("hasCredentials", !jdbc.query("""
                select encrypted_payload from integration_credentials
                where integration_id = ? and tenant_id = ?
                """, (rs, row) -> rs.getString(1), integrationId, tenantId).isEmpty());
        return described;
    }

    /** Runs of an integration, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> runs(UUID tenantId, UUID integrationId) {
        return jdbc.queryForList("""
                select id, run_type, status, items_processed, items_failed, error_code, error_message,
                       started_at, finished_at
                from integration_sync_runs
                where integration_id = ? and tenant_id = ?
                order by started_at desc
                """, integrationId, tenantId);
    }

    private static String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(value -> "\"" + value.replace("\"", "") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
}
