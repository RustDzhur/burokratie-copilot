package de.buerokratiecopilot.calendar.caldav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.integrations.CredentialCipher;
import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * The app password of a CalDAV connection goes through the same cipher as mailbox passwords
 * ({@link CredentialCipher}, AES-256-GCM bound to tenant and connection id). This test pins the
 * contract the connection service relies on: the stored value never contains the plaintext, decrypts
 * back only for the right tenant and connection, and refuses everything else.
 */
class CalDavPasswordCipherTest {

    private static final String PASSWORD = "app-passwort-geheim-4711";

    private CredentialCipher cipher() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i + 1);
        }
        BurokratieProperties properties = new BurokratieProperties();
        properties.getSecurity().getEncryption().setKeyBase64(Base64.getEncoder().encodeToString(key));
        return new CredentialCipher(properties);
    }

    @Test
    @DisplayName("the stored value has the cipher's shape and does not contain the password")
    void storedValueIsNotThePassword() {
        CredentialCipher cipher = cipher();
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        String stored = cipher.encrypt(PASSWORD, tenantId, connectionId);

        assertThat(stored).startsWith("v1.");
        assertThat(cipher.looksEncrypted(stored)).isTrue();
        assertThat(stored).doesNotContain(PASSWORD);
        assertThat(stored).doesNotContain(Base64.getEncoder().encodeToString(
                PASSWORD.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(cipher.decrypt(stored, tenantId, connectionId)).isEqualTo(PASSWORD);
    }

    @Test
    @DisplayName("every encryption is fresh: the same password never produces the same stored value")
    void freshNonce() {
        CredentialCipher cipher = cipher();
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        assertThat(cipher.encrypt(PASSWORD, tenantId, connectionId))
                .isNotEqualTo(cipher.encrypt(PASSWORD, tenantId, connectionId));
    }

    @Test
    @DisplayName("a stored value cannot be read under another tenant or connection")
    void boundToItsOwner() {
        CredentialCipher cipher = cipher();
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        String stored = cipher.encrypt(PASSWORD, tenantId, connectionId);

        assertThatThrownBy(() -> cipher.decrypt(stored, UUID.randomUUID(), connectionId))
                .isInstanceOf(CredentialCipher.CredentialCipherException.class);
        assertThatThrownBy(() -> cipher.decrypt(stored, tenantId, UUID.randomUUID()))
                .isInstanceOf(CredentialCipher.CredentialCipherException.class);
    }

    @Test
    @DisplayName("a missing key refuses to work instead of encrypting with a fallback")
    void keyIsRequired() {
        BurokratieProperties properties = new BurokratieProperties();
        assertThatThrownBy(() -> new CredentialCipher(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BC_ENCRYPTION_KEY");
    }
}
