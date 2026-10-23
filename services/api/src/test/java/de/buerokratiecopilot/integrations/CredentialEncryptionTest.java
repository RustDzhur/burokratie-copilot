package de.buerokratiecopilot.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.integrations.CredentialCipher.CredentialCipherException;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies that tokens are stored encrypted (spec §29, E-001 acceptance).
 *
 * <p>The test reads the bytes in the database rather than asking the service whether it encrypted them.
 * A platform that claimed to encrypt and did not would pass any check that trusted its own answer, and
 * this is one claim where trusting the claimant is exactly the mistake.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class CredentialEncryptionTest {

    @Autowired
    private IntegrationService integrations;

    @Autowired
    private CredentialCipher cipher;

    @Autowired
    private JdbcTemplate jdbc;

    private static final String SECRET = "geheimes-refresh-token-4711";

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Integration Test', 'COMPANY') returning id
                """, UUID.class, "int-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private Map<String, String> fields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("accessToken", SECRET);
        fields.put("refreshToken", "refresh-4711");
        fields.put("clientSecret", "client-secret-4711");
        return fields;
    }

    @Test
    @DisplayName("the stored bytes contain the secret nowhere, in any encoding")
    void storedBytesAreNotTheSecret() {
        UUID tenantId = tenant();

        IntegrationService.Connection connection = integrations.connect(tenantId, null, "IMAP", "EMAIL",
                "Postfach", "OAUTH2", fields(), java.util.List.of("mail.read"), "postfach@example.de",
                null);

        String stored = jdbc.queryForObject("""
                select encrypted_payload from integration_credentials where integration_id = ?
                """, String.class, connection.integrationId());

        assertThat(stored).doesNotContain(SECRET);
        assertThat(stored).doesNotContain("refresh-4711");
        assertThat(stored).doesNotContain("client-secret-4711");
        // Not merely obfuscated by a different encoding either.
        assertThat(stored).doesNotContain(Base64.getEncoder().encodeToString(
                SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String[] parts = stored.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo("v1");
        assertThat(Base64.getDecoder().decode(parts[1])).hasSize(12);
        assertThat(Base64.getDecoder().decode(parts[2]).length).isGreaterThan(SECRET.length());
    }

    @Test
    @DisplayName("a secret survives the round trip and comes back unchanged")
    void secretsRoundTrip() {
        UUID tenantId = tenant();
        IntegrationService.Connection connection = integrations.connect(tenantId, null, "SMTP", "EMAIL",
                "Versand", "PASSWORD", fields(), null, null, null);

        Map<String, String> decrypted = integrations.credentials(tenantId, connection.integrationId());

        assertThat(decrypted).containsEntry("accessToken", SECRET)
                .containsEntry("refreshToken", "refresh-4711");
    }

    @Test
    @DisplayName("a payload from one tenant cannot be decrypted as another tenant's")
    void ciphertextIsBoundToItsTenant() {
        UUID first = tenant();
        UUID second = tenant();

        UUID integrationId = UUID.fromString("00000000-0000-0000-0000-000000000047");
        String encrypted = cipher.encrypt(SECRET, first, integrationId);

        // Moving a secret into another tenant's row must not work; that is why the tenant is part of
        // the associated data rather than only of the row it sits in.
        assertThatThrownBy(() -> cipher.decrypt(encrypted, second, integrationId))
                .isInstanceOf(CredentialCipherException.class);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, first, UUID.randomUUID()))
                .as("the right tenant is not enough; the payload also has to belong to the integration")
                .isInstanceOf(CredentialCipherException.class);
        assertThat(cipher.decrypt(encrypted, first, integrationId)).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("the same payload encrypted twice gives different bytes")
    void nonceIsFreshEveryTime() {
        UUID tenantId = tenant();
        UUID integrationId = UUID.randomUUID();

        String first = cipher.encrypt(SECRET, tenantId, integrationId);
        String second = cipher.encrypt(SECRET, tenantId, integrationId);

        // Reusing a nonce with GCM does not weaken the encryption, it breaks it.
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first, tenantId, integrationId)).isEqualTo(SECRET);
        assertThat(cipher.decrypt(second, tenantId, integrationId)).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("a tampered payload is refused instead of returning something")
    void tamperedPayloadIsRefused() {
        UUID tenantId = tenant();
        UUID integrationId = UUID.randomUUID();
        String encrypted = cipher.encrypt(SECRET, tenantId, integrationId);

        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";

        assertThatThrownBy(() -> cipher.decrypt(tampered, tenantId, integrationId))
                .isInstanceOf(CredentialCipherException.class);
    }

    @Test
    @DisplayName("a value that was never encrypted is not mistaken for one")
    void plaintextIsNotAcceptedAsCiphertext() {
        UUID tenantId = tenant();
        UUID integrationId = UUID.randomUUID();

        assertThatThrownBy(() -> cipher.decrypt(SECRET, tenantId, integrationId))
                .isInstanceOf(CredentialCipherException.class);
        assertThat(cipher.looksEncrypted(SECRET)).isFalse();
        assertThat(cipher.looksEncrypted(cipher.encrypt(SECRET, tenantId, integrationId))).isTrue();
    }

    @Test
    @DisplayName("all credential fields travel in one payload, so none can be rotated alone")
    void onePayloadForOneCredential() {
        UUID tenantId = tenant();
        IntegrationService.Connection connection = integrations.connect(tenantId, null, "GOOGLE_WORKSPACE",
                "CALENDAR", "Kalender", "SERVICE_ACCOUNT", fields(), null, null, null);

        Integer rows = jdbc.queryForObject("""
                select count(*) from integration_credentials where integration_id = ?
                """, Integer.class, connection.integrationId());

        assertThat(rows).isEqualTo(1);
        assertThat(integrations.credentials(tenantId, connection.integrationId())).hasSize(3);
    }

    @Test
    @DisplayName("a disabled integration does not hand out its credentials")
    void disabledIntegrationWithholdsCredentials() {
        UUID tenantId = tenant();
        IntegrationService.Connection connection = integrations.connect(tenantId, null, "ACCOUNTING_DATEV",
                "ACCOUNTING", "Buchhaltung", "API_KEY", fields(), null, null, null);

        integrations.disable(tenantId, connection.integrationId(), "Zugang widerrufen");

        assertThatThrownBy(() -> integrations.credentials(tenantId, connection.integrationId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("deaktiviert");
    }

    @Test
    @DisplayName("the description of an integration contains no secret")
    void descriptionContainsNoSecret() {
        UUID tenantId = tenant();
        IntegrationService.Connection connection = integrations.connect(tenantId, null, "SMTP",
                "SIGNATURE", "Signatur", "CERTIFICATE", fields(), null, null, null);

        Map<String, Object> described = integrations.describe(tenantId, connection.integrationId());

        assertThat(described).containsEntry("hasCredentials", true);
        assertThat(described.values().stream().map(String::valueOf).toList())
                .allSatisfy(value -> assertThat(value).doesNotContain(SECRET));
    }
}
