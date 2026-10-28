package de.buerokratiecopilot.integrations;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Encrypts integration credentials at rest (spec §29, E-001).
 *
 * <p>AES-256-GCM, with the key from configuration. Two decisions are worth stating.</p>
 *
 * <p><strong>The key is required, never defaulted.</strong> If it is missing the cipher refuses to work
 * rather than falling back to something. A fallback key would encrypt every deployment's secrets with
 * the same well-known value, and nobody would notice until it mattered.</p>
 *
 * <p><strong>The ciphertext is bound to where it belongs.</strong> The tenant and integration identifiers
 * are used as associated data, so a stolen payload cannot be moved into another tenant's row and
 * decrypted there. Encryption without that binding protects the bytes but not their meaning.</p>
 *
 * <p>A fresh random nonce is generated for every encryption. Reusing a nonce with GCM is not a weakness
 * but a break, so the nonce is never derived from the data.</p>
 */
@Component
public class CredentialCipher {

    /** The stored shape: version, nonce, ciphertext — all base64, joined by dots. */
    private static final String PREFIX = "v1";

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(BurokratieProperties properties) {
        String configured = properties.getSecurity().getEncryption().getKeyBase64();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("BC_ENCRYPTION_KEY is missing: credentials could not be "
                    + "encrypted, and the platform does not store them in the open");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configured.strip());
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalStateException("BC_ENCRYPTION_KEY is not valid base64", ex);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("BC_ENCRYPTION_KEY must be 32 bytes for AES-256, was "
                    + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /**
     * Encrypts a payload.
     *
     * @param tenantId      the tenant the secret belongs to
     * @param integrationId the integration it belongs to
     */
    public String encrypt(String plaintext, java.util.UUID tenantId, java.util.UUID integrationId) {
        if (plaintext == null) {
            throw new IllegalArgumentException("Nothing to encrypt");
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            random.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(associatedData(tenantId, integrationId));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getEncoder();
            return PREFIX + "." + encoder.encodeToString(nonce) + "." + encoder.encodeToString(ciphertext);
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Das Geheimnis konnte nicht verschlüsselt werden", ex);
        }
    }

    /**
     * Decrypts a payload that belongs to the given tenant and integration.
     *
     * @throws CredentialCipherException when the payload does not belong there or was tampered with,
     *                                   which is the same answer on purpose: a caller must not be able
     *                                   to tell "wrong tenant" from "corrupted" and learn from it
     */
    public String decrypt(String stored, java.util.UUID tenantId, java.util.UUID integrationId) {
        if (stored == null || !stored.startsWith(PREFIX + ".")) {
            throw new CredentialCipherException("Unbekanntes Format des gespeicherten Geheimnisses");
        }
        String[] parts = stored.split("\\.", 3);
        if (parts.length != 3) {
            throw new CredentialCipherException("Das gespeicherte Geheimnis ist unvollständig");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(parts[1]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[2]);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(associatedData(tenantId, integrationId));

            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        }
        catch (java.security.GeneralSecurityException | IllegalArgumentException ex) {
            throw new CredentialCipherException(
                    "Das Geheimnis passt nicht zu diesem Mandanten und dieser Integration oder wurde "
                            + "verändert");
        }
    }

    /** Whether a stored value has the shape this cipher produces, without decrypting it. */
    public boolean looksEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX + ".") && stored.split("\\.").length == 3;
    }

    /**
     * Encrypts a map of credential fields as one JSON document.
     *
     * <p>The whole set goes into one payload rather than one row per field: a token is only meaningful
     * together with its refresh token and client, and splitting them would allow half a credential to be
     * rotated without the other half.</p>
     */
    public String encryptFields(Map<String, String> fields, java.util.UUID tenantId,
                                java.util.UUID integrationId) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                json.append(',');
            }
            json.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
            first = false;
        }
        json.append('}');
        return encrypt(json.toString(), tenantId, integrationId);
    }

    /** The fields of an encrypted credential payload. */
    public Map<String, String> decryptFields(String stored, java.util.UUID tenantId,
                                             java.util.UUID integrationId) {
        String json = decrypt(stored, tenantId, integrationId);
        Map<String, String> fields = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        while (matcher.find()) {
            fields.put(unescape(matcher.group(1)), unescape(matcher.group(2)));
        }
        return fields;
    }

    private static byte[] associatedData(java.util.UUID tenantId, java.util.UUID integrationId) {
        return ((tenantId == null ? "" : tenantId.toString()) + "|"
                + (integrationId == null ? "" : integrationId.toString()))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** Thrown when a stored secret cannot be read. */
    public static class CredentialCipherException extends RuntimeException {

        public CredentialCipherException(String message) {
            super(message);
        }
    }
}
