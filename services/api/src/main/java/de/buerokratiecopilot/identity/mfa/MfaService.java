package de.buerokratiecopilot.identity.mfa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
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

import de.buerokratiecopilot.integrations.CredentialCipher;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The second factor (spec §33, A-014).
 *
 * <p>An account with a second factor is only as strong as the moment it was switched on. Three things
 * have to hold, and the first is the one that is usually missed.</p>
 *
 * <p><strong>Enrolment is not activation.</strong> A new secret is stored {@code confirmed_at = NULL} and
 * does not count as a second factor until a code generated from it has been presented. Otherwise a user
 * who scanned nothing — or scanned it into the wrong application — would be locked out by a factor they
 * cannot produce, and would have to be rescued by an administrator.</p>
 *
 * <p><strong>The secret is encrypted at rest</strong>, with the same cipher as every other credential in
 * this platform. A TOTP secret is a shared secret: anybody who reads it can generate codes forever.</p>
 *
 * <p><strong>Recovery codes are stored hashed and work once.</strong> They exist because telephones are
 * lost, and a recovery code that can be used twice is a password that was written down.</p>
 *
 * <p>Nothing in the algorithm is this platform's invention: RFC 6238 is implemented as published, because
 * a second factor that deviates from the standard is one no authenticator application accepts.</p>
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);

    /** How many recovery codes a user gets; enough to survive a lost telephone and a spare. */
    private static final int RECOVERY_CODE_COUNT = 10;

    /** How long an enrolment stays valid before it has to be started again. */
    private static final long ENROLMENT_TTL_SECONDS = 900;

    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;

    public MfaService(JdbcTemplate jdbc, CredentialCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** An enrolment that has been started but not confirmed. */
    public record Enrolment(UUID methodId, String secretBase32, String provisioningUri) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("methodId", methodId.toString());
            map.put("secret", secretBase32);
            map.put("provisioningUri", provisioningUri);
            map.put("note", "Der Faktor ist erst aktiv, wenn ein Code daraus bestätigt wurde.");
            return map;
        }
    }

    /** What a confirmation produced. */
    public record Confirmation(UUID methodId, List<String> recoveryCodes) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("methodId", methodId.toString());
            map.put("recoveryCodes", recoveryCodes);
            map.put("note", "Diese Codes werden nur einmal angezeigt.");
            return map;
        }
    }

    /** Whether a verification succeeded, and how. */
    public record Verification(boolean valid, String method) {

        public Map<String, Object> asMap() {
            return Map.of("valid", valid, "method", method == null ? "" : method);
        }
    }

    /**
     * Starts an enrolment.
     *
     * <p>The secret is encrypted before it is stored, and the method is not usable until a code from it
     * has been confirmed.</p>
     */
    @Transactional
    public Enrolment enrol(UUID tenantId, UUID userId, String label) {
        requireUser(tenantId, userId);

        byte[] secret = Totp.newSecret();
        String encrypted = cipher.encrypt(Totp.base32(secret), tenantId, userId);
        UUID methodId = jdbc.queryForObject("""
                insert into mfa_methods (id, user_id, type, secret_encrypted, label)
                values (gen_random_uuid(), ?, 'TOTP', ?, ?)
                returning id
                """, UUID.class, userId, encrypted, label == null ? "Authenticator" : label);

        String account = emailOf(userId);
        log.info("MFA enrolment {} started for user {}", methodId, userId);
        return new Enrolment(methodId, Totp.base32(secret),
                Totp.provisioningUri("Bürokratie-Copilot", account, secret));
    }

    /**
     * Confirms an enrolment with a code from the application.
     *
     * <p>Only now does the factor exist in the sense that matters: until a code has been produced from
     * it, nobody has shown that they hold the secret, and treating an unconfirmed secret as a second
     * factor locks people out of their own accounts.</p>
     */
    @Transactional
    public Confirmation confirm(UUID tenantId, UUID userId, UUID methodId, String code) {
        Map<String, Object> method = requireMethod(tenantId, userId, methodId);
        if (method.get("confirmed_at") != null) {
            throw ApiException.conflict("Dieser Faktor ist bereits bestätigt");
        }
        Instant created = ((java.sql.Timestamp) method.get("created_at")).toInstant();
        if (created.plusSeconds(ENROLMENT_TTL_SECONDS).isBefore(Instant.now())) {
            throw ApiException.conflict("Diese Einrichtung ist abgelaufen und muss neu begonnen werden");
        }

        byte[] secret = secretOf(tenantId, userId, method);
        if (!Totp.isValid(secret, code, Instant.now().getEpochSecond())) {
            throw new ApiException(ErrorCode.MFA_INVALID, "Der Code stimmt nicht");
        }

        jdbc.update("""
                update mfa_methods set confirmed_at = now(), last_used_at = now() where id = ?
                """, methodId);

        List<String> codes = issueRecoveryCodes(userId);
        audit(tenantId, userId, "MFA_ENABLED");
        log.info("MFA method {} confirmed for user {}", methodId, userId);
        return new Confirmation(methodId, codes);
    }

    /**
     * Verifies a code at sign-in.
     *
     * <p>A recovery code is accepted as well, and is consumed: it exists for the telephone that was lost,
     * and a code that can be used twice is a password somebody wrote down.</p>
     */
    @Transactional
    public Verification verify(UUID tenantId, UUID userId, String code) {
        List<Map<String, Object>> methods = jdbc.queryForList("""
                select id, secret_encrypted, confirmed_at from mfa_methods
                where user_id = ? and type = 'TOTP' and disabled_at is null
                """, userId);

        for (Map<String, Object> method : methods) {
            if (method.get("confirmed_at") == null) {
                // An unconfirmed enrolment is not a factor; skipping it is what keeps a half-finished
                // setup from locking somebody out.
                continue;
            }
            byte[] secret = decodeBase32(cipher.decrypt((String) method.get("secret_encrypted"),
                    tenantId, userId));
            if (Totp.isValid(secret, code, Instant.now().getEpochSecond())) {
                jdbc.update("update mfa_methods set last_used_at = now() where id = ?", method.get("id"));
                return new Verification(true, "TOTP");
            }
        }

        if (consumeRecoveryCode(userId, code)) {
            audit(tenantId, userId, "MFA_RECOVERY_CODE_USED");
            log.info("Recovery code used by user {}", userId);
            return new Verification(true, "RECOVERY_CODE");
        }
        return new Verification(false, null);
    }

    /**
     * Whether a user has an active second factor.
     *
     * <p>An enrolment that was started and never confirmed does not count — the question is whether
     * somebody could actually produce a code.</p>
     */
    @Transactional(readOnly = true)
    public boolean hasActiveFactor(UUID userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from mfa_methods
                where user_id = ? and confirmed_at is not null and disabled_at is null
                """, Integer.class, userId);
        return count != null && count > 0;
    }

    /**
     * Switches a factor off.
     *
     * <p>The secret stays where it is, encrypted. The schema ties it to the type — a TOTP row is by
     * definition one that has a secret — and clearing the column would leave a row contradicting its own
     * type. What makes the factor unusable is {@code disabled_at}, which every verification checks, and
     * the recovery codes are deleted because those are the part that would otherwise stay usable.</p>
     */
    @Transactional
    public void disable(UUID tenantId, UUID userId, UUID methodId) {
        requireMethod(tenantId, userId, methodId);
        jdbc.update("""
                update mfa_methods set disabled_at = now() where id = ?
                """, methodId);
        jdbc.update("delete from mfa_recovery_codes where user_id = ? and used_at is null", userId);
        audit(tenantId, userId, "MFA_DISABLED");
        log.info("MFA method {} disabled for user {}", methodId, userId);
    }

    /**
     * Requires a second factor for a tenant.
     *
     * <p>Configuration, because whether an organisation demands one is its decision and not the
     * platform's. It is recorded in the settings the tenant already has.</p>
     */
    @Transactional
    public void requireMfa(UUID tenantId, boolean required, UUID changedBy) {
        jdbc.update("""
                insert into system_settings (id, tenant_id, setting_key, category, value, value_type,
                                             description, requires_verification, version, is_active,
                                             updated_by)
                values (gen_random_uuid(), ?, 'security.require_mfa', 'SECURITY', ?::jsonb, 'BOOLEAN',
                        '{"de":"Zweiter Faktor erforderlich"}'::jsonb, false, 1, true, ?)
                on conflict (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
                             setting_key)
                do update set value = excluded.value, updated_by = excluded.updated_by, updated_at = now()
                """, tenantId, String.valueOf(required), changedBy);
    }

    /** Whether a tenant requires a second factor. */
    @Transactional(readOnly = true)
    public boolean isMfaRequired(UUID tenantId) {
        List<Boolean> values = jdbc.query("""
                select (value #>> '{}')::boolean as required from system_settings
                where setting_key = 'security.require_mfa' and (tenant_id = ? or tenant_id is null)
                  and is_active = true
                order by tenant_id nulls last limit 1
                """, (rs, row) -> rs.getBoolean("required"), tenantId);
        return !values.isEmpty() && Boolean.TRUE.equals(values.getFirst());
    }

    /** How many unused recovery codes a user has left. */
    @Transactional(readOnly = true)
    public int remainingRecoveryCodes(UUID userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from mfa_recovery_codes where user_id = ? and used_at is null
                """, Integer.class, userId);
        return count == null ? 0 : count;
    }

    /** The factors of a user, without any secret. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID userId) {
        return jdbc.queryForList("""
                select id, type, label, confirmed_at, last_used_at, disabled_at,
                       (secret_encrypted is not null) as has_secret
                from mfa_methods where user_id = ? order by created_at
                """, userId);
    }

    // ------------------------------------------------------------------ internals

    private List<String> issueRecoveryCodes(UUID userId) {
        // Old unused codes are replaced rather than added to: a growing pile of forgotten codes is a
        // growing pile of ways in.
        jdbc.update("delete from mfa_recovery_codes where user_id = ? and used_at is null", userId);

        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code = Totp.newRecoveryCode();
            codes.add(code);
            jdbc.update("""
                    insert into mfa_recovery_codes (id, user_id, code_hash)
                    values (gen_random_uuid(), ?, ?)
                    """, userId, hash(code));
        }
        return List.copyOf(codes);
    }

    private boolean consumeRecoveryCode(UUID userId, String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        String normalized = presented.strip().toLowerCase(java.util.Locale.ROOT);
        // The update both consumes and checks: two simultaneous attempts cannot both succeed on one code.
        int consumed = jdbc.update("""
                update mfa_recovery_codes set used_at = now()
                where user_id = ? and code_hash = ? and used_at is null
                """, userId, hash(normalized));
        return consumed > 0;
    }

    private byte[] secretOf(UUID tenantId, UUID userId, Map<String, Object> method) {
        String stored = (String) method.get("secret_encrypted");
        if (stored == null) {
            throw ApiException.conflict("Für diesen Faktor ist kein Geheimnis hinterlegt");
        }
        return decodeBase32(cipher.decrypt(stored, tenantId, userId));
    }

    /** Decodes the base32 the platform stored, which is what the provisioning URI carried. */
    public static byte[] decodeBase32(String encoded) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        String cleaned = encoded.toUpperCase(java.util.Locale.ROOT).replace("=", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsLeft = 0;
        for (char character : cleaned.toCharArray()) {
            int value = alphabet.indexOf(character);
            if (value < 0) {
                throw new IllegalArgumentException("Ungültiges Base32-Zeichen");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private Map<String, Object> requireMethod(UUID tenantId, UUID userId, UUID methodId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, secret_encrypted, confirmed_at, created_at from mfa_methods
                where id = ? and user_id = ? and disabled_at is null
                """, methodId, userId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("MFA method");
        }
        return rows.getFirst();
    }

    /** The user has to exist and belong to the tenant the caller is acting in. */
    private void requireUser(UUID tenantId, UUID userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from memberships where tenant_id = ? and user_id = ? and status = 'ACTIVE'
                """, Integer.class, tenantId, userId);
        if (count == null || count == 0) {
            throw ApiException.notFound("user");
        }
    }

    private String emailOf(UUID userId) {
        return jdbc.queryForObject("select email from users where id = ?", String.class, userId);
    }

    private void audit(UUID tenantId, UUID userId, String action) {
        jdbc.update("""
                insert into audit_events (id, tenant_id, actor_user_id, actor_type, category, action,
                                          entity_type, entity_id, description, changes, result, severity)
                values (gen_random_uuid(), ?, ?, 'USER', 'AUTHENTICATION', ?, 'user', ?, ?, '{}'::jsonb,
                        'SUCCESS', 'NOTICE')
                """, tenantId, userId, action, userId, action);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }
}
