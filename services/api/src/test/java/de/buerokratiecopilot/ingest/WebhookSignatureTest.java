package de.buerokratiecopilot.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies webhook signatures (spec §30, B-013 acceptance).
 *
 * <p>The acceptance criterion is that an invalid signature gives 401 — that is, the request is refused
 * before anything is read from it. These tests are about the signature itself: what it covers, what it
 * refuses, and what it refuses even when the signature is genuine.</p>
 */
class WebhookSignatureTest {

    private static final String SECRET = "ein-sehr-langes-webhook-geheimnis-4711";
    private static final long NOW = 1_772_000_000L;
    private static final byte[] BODY = "{\"documentId\":\"abc\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("a genuine signature is accepted")
    void genuineSignatureIsAccepted() {
        String signature = WebhookSignature.sign(SECRET, NOW, BODY);

        assertThat(WebhookSignature.isValid(SECRET, NOW, BODY, signature, NOW)).isTrue();
        assertThat(WebhookSignature.looksLikeSignature(signature)).isTrue();
    }

    @Test
    @DisplayName("a signature over different content is refused")
    void signatureDoesNotCoverOtherContent() {
        String signature = WebhookSignature.sign(SECRET, NOW, BODY);
        byte[] other = "{\"documentId\":\"xyz\"}".getBytes(StandardCharsets.UTF_8);

        // The body is covered, so a captured signature cannot be reused with another payload.
        assertThat(WebhookSignature.isValid(SECRET, NOW, other, signature, NOW)).isFalse();
    }

    @Test
    @DisplayName("a signature for another moment is refused")
    void signatureCoversTheTimestamp() {
        String signature = WebhookSignature.sign(SECRET, NOW, BODY);

        assertThat(WebhookSignature.isValid(SECRET, NOW + 1, BODY, signature, NOW + 1)).isFalse();
    }

    @Test
    @DisplayName("a signature from a foreign secret is refused")
    void anotherSecretDoesNotSign() {
        String signature = WebhookSignature.sign("ein-anderes-geheimnis", NOW, BODY);

        assertThat(WebhookSignature.isValid(SECRET, NOW, BODY, signature, NOW)).isFalse();
    }

    @Test
    @DisplayName("an old signature is refused even though it is genuine")
    void oldSignatureIsRefused() {
        String signature = WebhookSignature.sign(SECRET, NOW, BODY);

        // A genuine signature of an old request is still a replay, and replaying it must not work.
        assertThat(WebhookSignature.isValid(SECRET, NOW, BODY, signature, NOW + 3600)).isFalse();
        assertThat(WebhookSignature.isValid(SECRET, NOW, BODY, signature, NOW + 200)).isTrue();
    }

    @Test
    @DisplayName("a timestamp far in the future is refused as well")
    void futureTimestampIsRefused() {
        String signature = WebhookSignature.sign(SECRET, NOW + 7200, BODY);

        assertThat(WebhookSignature.isValid(SECRET, NOW + 7200, BODY, signature, NOW)).isFalse();
    }

    @Test
    @DisplayName("a missing or malformed signature is refused, not treated as absent")
    void missingSignatureIsRefused() {
        assertThat(WebhookSignature.isValid(SECRET, NOW, BODY, null, NOW)).isFalse();
        assertThat(WebhookSignature.isValid(SECRET, NOW, BODY, "", NOW)).isFalse();
        assertThat(WebhookSignature.isValid(SECRET, NOW, BODY, "nicht-hex", NOW)).isFalse();
        assertThat(WebhookSignature.isValid(SECRET, NOW, BODY, "abc", NOW)).isFalse();
    }

    @Test
    @DisplayName("the signature is case insensitive, because hex is written both ways")
    void signatureIsCaseInsensitive() {
        String signature = WebhookSignature.sign(SECRET, NOW, BODY);

        assertThat(WebhookSignature.isValid(SECRET, NOW, BODY, signature.toUpperCase(), NOW)).isTrue();
    }

    @Test
    @DisplayName("signing without a secret is refused rather than producing a weak one")
    void signingNeedsASecret() {
        assertThatThrownBy(() -> WebhookSignature.sign(null, NOW, BODY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebhookSignature.sign("  ", NOW, BODY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an empty body can be signed and verified")
    void emptyBodyWorks() {
        String signature = WebhookSignature.sign(SECRET, NOW, new byte[0]);

        assertThat(WebhookSignature.isValid(SECRET, NOW, new byte[0], signature, NOW)).isTrue();
        assertThat(WebhookSignature.isValid(SECRET, NOW, null, signature, NOW)).isTrue();
    }

    @Test
    @DisplayName("an API token is shown once and stored only as a hash")
    void tokensAreHashed() {
        String token = WebhookSignature.newApiToken(java.util.UUID.randomUUID());

        assertThat(token).startsWith("bc_");
        assertThat(token.length()).isGreaterThan(40);

        String hash = WebhookSignature.hashApiToken(token);
        assertThat(hash).hasSize(64);
        // The hash is what the database holds; the token itself must not be recoverable from it.
        assertThat(hash).doesNotContain(token);
        assertThat(WebhookSignature.hashApiToken(token)).isEqualTo(hash);
    }

    @Test
    @DisplayName("two issued tokens differ")
    void tokensAreUnique() {
        java.util.UUID tenantId = java.util.UUID.randomUUID();

        assertThat(WebhookSignature.newApiToken(tenantId))
                .isNotEqualTo(WebhookSignature.newApiToken(tenantId));
    }

    @Test
    @DisplayName("two generated secrets differ, so one deployment cannot sign for another")
    void secretsAreUnique() {
        assertThat(WebhookSignature.newSecret()).isNotEqualTo(WebhookSignature.newSecret());
        assertThat(WebhookSignature.newSecret()).hasSize(64);
    }

    @Test
    @DisplayName("the expected headers are documented, so a sender does not guess")
    void expectedHeadersAreDocumented() {
        assertThat(WebhookSignature.expectedHeaders())
                .containsKeys("X-BC-Signature", "X-BC-Timestamp", "X-BC-Idempotency-Key");
    }

    @Test
    @DisplayName("a changed byte anywhere in the body breaks the signature")
    void everyByteIsCovered() {
        String signature = WebhookSignature.sign(SECRET, NOW, BODY);
        byte[] changed = BODY.clone();
        changed[changed.length - 2] = (byte) (changed[changed.length - 2] ^ 0x01);

        assertThat(WebhookSignature.isValid(SECRET, NOW, changed, signature, NOW)).isFalse();
    }
}
