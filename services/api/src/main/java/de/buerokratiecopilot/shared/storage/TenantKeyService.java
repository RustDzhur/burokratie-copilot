package de.buerokratiecopilot.shared.storage;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The data key of each workspace: random, stored wrapped by the platform key, unwrapped only in memory.
 *
 * <p>Without the platform key ({@code BC_ENCRYPTION_KEY}, 32 bytes) the service says it is {@link #available() unavailable} and
 * documents are stored as they were before; a production start without the key is refused elsewhere. A key that cannot be read
 * for a workspace (another workspace's context, a deleted row) is an error, never a silent fallback to plaintext.</p>
 */
@Service
public class TenantKeyService {

    private final JdbcTemplate jdbc;
    /** The key is read and created in a transaction of its own, so it gets a connection that belongs to the workspace of the key. */
    private final org.springframework.transaction.support.TransactionTemplate separate;
    private final SecretKey platformKey;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, SecretKey> cache = new ConcurrentHashMap<>();

    public TenantKeyService(JdbcTemplate jdbc, BurokratieProperties properties,
                            org.springframework.transaction.PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        if (transactions != null) {
            this.separate = new org.springframework.transaction.support.TransactionTemplate(transactions);
            this.separate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        else {
            this.separate = null;
        }
        SecretKey key = null;
        try {
            byte[] raw = Base64.getDecoder().decode(properties.getSecurity().getEncryption().getKeyBase64().strip());
            if (raw.length == 32) {
                key = new SecretKeySpec(raw, "AES");
            }
        }
        catch (IllegalArgumentException | NullPointerException ex) {
            key = null;
        }
        this.platformKey = key;
    }

    public boolean available() {
        return platformKey != null;
    }

    public int version() {
        return 1;
    }

    /** The key of the workspace; created on first use. */
    public SecretKey keyOf(UUID tenantId) {
        if (platformKey == null) {
            throw new IllegalStateException("No platform key is configured");
        }
        return cache.computeIfAbsent(tenantId, id -> RequestContext.runAs(id,
                () -> separate == null ? load(id) : separate.execute(status -> load(id))));
    }

    private SecretKey load(UUID tenantId) {
        List<byte[]> rows = jdbc.query("select wrapped_key from tenant_data_keys where tenant_id = ?", (rs, i) -> rs.getBytes(1), tenantId);
        if (rows.isEmpty()) {
            byte[] fresh = new byte[32];
            random.nextBytes(fresh);
            jdbc.update("insert into tenant_data_keys (tenant_id, key_version, wrapped_key) values (?, 1, ?) on conflict do nothing",
                    tenantId, wrap(fresh, tenantId));
            // Another instance may have inserted first; the stored one is the key, whichever won.
            rows = jdbc.query("select wrapped_key from tenant_data_keys where tenant_id = ?", (rs, i) -> rs.getBytes(1), tenantId);
        }
        return new SecretKeySpec(unwrap(rows.getFirst(), tenantId), "AES");
    }

    private byte[] wrap(byte[] key, UUID tenantId) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, platformKey, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(tenantId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(key);
            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return out;
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("The data key could not be wrapped", ex);
        }
    }

    private byte[] unwrap(byte[] wrapped, UUID tenantId) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, platformKey, new GCMParameterSpec(128, wrapped, 0, 12));
            cipher.updateAAD(tenantId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return cipher.doFinal(wrapped, 12, wrapped.length - 12);
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("The data key of the workspace cannot be unwrapped (wrong platform key?)", ex);
        }
    }
}
