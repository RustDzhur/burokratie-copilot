package de.buerokratiecopilot.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Verifies the document ingest API (spec §30, B-013 acceptance).
 *
 * <p>Two criteria: a repeat with the same key creates no duplicate, and an invalid signature is refused
 * with 401. Both are tested by doing the thing that must fail rather than by asking the service whether
 * it would refuse — a sender that retries after a timeout is the normal case, not an edge case.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestApiIT {

    @Autowired
    private IngestService ingest;

    @Autowired
    private JdbcTemplate jdbc;

    private static final byte[] CONTENT = "Rechnung Inhalt".getBytes(StandardCharsets.UTF_8);

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Ingest Test', 'COMPANY') returning id
                """, UUID.class, "ing-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Partner', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "ip-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    private IngestService.Caller caller(UUID tenantId) {
        IngestService.IssuedToken issued = ingest.issueToken(tenantId, null, "Partner A",
                List.of("documents.write"), 100, null, person());
        return ingest.authenticate(issued.token());
    }

    private long documentCount(UUID tenantId) {
        Long count = jdbc.queryForObject("select count(*) from documents where tenant_id = ?", Long.class,
                tenantId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("a document is ingested once and the answer names it")
    void ingestsOnce() {
        UUID tenantId = tenant();
        IngestService.Caller caller = caller(tenantId);

        IngestService.Ingested result = ingest.ingest(caller, "key-1", "rechnung.pdf", "application/pdf",
                "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1", CONTENT, null);

        assertThat(result.duplicate()).isFalse();
        assertThat(documentCount(tenantId)).isEqualTo(1);
        assertThat(result.documentId()).isNotNull();
    }

    @Test
    @DisplayName("a repeat with the same key does not create a duplicate document")
    void repeatWithTheSameKeyCreatesNoDuplicate() {
        UUID tenantId = tenant();
        IngestService.Caller caller = caller(tenantId);

        IngestService.Ingested first = ingest.ingest(caller, "key-1", "rechnung.pdf", "application/pdf",
                "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1", CONTENT, null);
        IngestService.Ingested second = ingest.ingest(caller, "key-1", "rechnung.pdf", "application/pdf",
                "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1", CONTENT, null);

        assertThat(second.duplicate())
                .as("a sender that retried after a timeout must not produce a second document")
                .isTrue();
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(documentCount(tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("different keys produce different documents")
    void differentKeysProduceDifferentDocuments() {
        UUID tenantId = tenant();
        IngestService.Caller caller = caller(tenantId);

        IngestService.Ingested first = ingest.ingest(caller, "key-a", "a.pdf", "application/pdf", "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3",
                CONTENT, null);
        IngestService.Ingested second = ingest.ingest(caller, "key-b", "b.pdf", "application/pdf", "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3",
                CONTENT, null);

        assertThat(second.documentId()).isNotEqualTo(first.documentId());
        assertThat(documentCount(tenantId)).isEqualTo(2);
    }

    @Test
    @DisplayName("the same key from another tenant is a different request")
    void keysAreScopedToTheTenant() {
        UUID first = tenant();
        UUID second = tenant();

        IngestService.Ingested a = ingest.ingest(caller(first), "shared-key", "a.pdf", "application/pdf",
                "h", CONTENT, null);
        IngestService.Ingested b = ingest.ingest(caller(second), "shared-key", "b.pdf",
                "application/pdf", "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3", CONTENT, null);

        assertThat(a.documentId()).isNotEqualTo(b.documentId());
        assertThat(documentCount(first)).isEqualTo(1);
        assertThat(documentCount(second)).isEqualTo(1);
    }

    @Test
    @DisplayName("an ingest without a key is refused instead of silently creating one per attempt")
    void keyIsRequired() {
        UUID tenantId = tenant();
        IngestService.Caller caller = caller(tenantId);

        assertThatThrownBy(() -> ingest.ingest(caller, null, "a.pdf", "application/pdf", "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3", CONTENT,
                null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Idempotenzschlüssel");
        assertThat(documentCount(tenantId)).isZero();
    }

    @Test
    @DisplayName("a token is shown once and stored only as a hash")
    void theTokenIsNotStored() {
        UUID tenantId = tenant();
        IngestService.IssuedToken issued = ingest.issueToken(tenantId, null, "Partner B",
                List.of("documents.write"), 100, null, person());

        String stored = jdbc.queryForObject("select token_hash from api_tokens where id = ?", String.class,
                issued.tokenId());

        assertThat(stored).doesNotContain(issued.token());
        assertThat(stored).isEqualTo(WebhookSignature.hashApiToken(issued.token()));
        assertThat(issued.asMap()).containsKey("note");
    }

    @Test
    @DisplayName("an unknown token is refused with 401")
    void unknownTokenIsRefused() {
        assertThatThrownBy(() -> ingest.authenticate("bc_falsch_0000"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("a missing token is refused with 401")
    void missingTokenIsRefused() {
        assertThatThrownBy(() -> ingest.authenticate("  "))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("a revoked token is refused")
    void revokedTokenIsRefused() {
        UUID tenantId = tenant();
        IngestService.IssuedToken issued = ingest.issueToken(tenantId, null, "Partner C",
                List.of("documents.write"), 100, null, person());
        jdbc.update("update api_tokens set revoked_at = now(), is_active = false where id = ?",
                issued.tokenId());

        assertThatThrownBy(() -> ingest.authenticate(issued.token()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("widerrufen");
    }

    @Test
    @DisplayName("an expired token is refused")
    void expiredTokenIsRefused() {
        UUID tenantId = tenant();
        IngestService.IssuedToken issued = ingest.issueToken(tenantId, null, "Partner D",
                List.of("documents.write"), 100, Instant.now().minusSeconds(60), person());

        assertThatThrownBy(() -> ingest.authenticate(issued.token()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("abgelaufen");
    }

    @Test
    @DisplayName("the rate limit of the token is enforced")
    void rateLimitIsEnforced() {
        UUID tenantId = tenant();
        IngestService.IssuedToken issued = ingest.issueToken(tenantId, null, "Partner E",
                List.of("documents.write"), 2, null, person());

        ingest.authenticate(issued.token());
        ingest.authenticate(issued.token());

        assertThatThrownBy(() -> ingest.authenticate(issued.token()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode())
                        .isEqualTo(ErrorCode.RATE_LIMITED));
    }

    @Test
    @DisplayName("an invalid signature is refused with 401, before anything is read")
    void invalidSignatureIsRefused() {
        String secret = WebhookSignature.newSecret();
        long now = Instant.now().getEpochSecond();

        assertThatThrownBy(() -> ingest.requireValidSignature(secret, now, CONTENT, "deadbeef"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode())
                        .isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    @DisplayName("a genuine signature is accepted and the webhook is recorded as verified")
    void genuineSignatureIsAccepted() {
        UUID tenantId = tenant();
        String secret = WebhookSignature.newSecret();
        long now = Instant.now().getEpochSecond();
        String signature = WebhookSignature.sign(secret, now, CONTENT);

        ingest.requireValidSignature(secret, now, CONTENT, signature);

        UUID eventId = ingest.recordWebhook(tenantId, null, "document.received", "{\"a\":1}", signature,
                true, "wh-key-1");
        assertThat(eventId).isNotNull();
        assertThat(jdbc.queryForObject("select signature_verified from webhook_events where id = ?",
                Boolean.class, eventId)).isTrue();
    }

    @Test
    @DisplayName("a webhook recorded without a valid signature is marked as rejected")
    void unverifiedWebhookIsMarkedRejected() {
        UUID tenantId = tenant();

        UUID eventId = ingest.recordWebhook(tenantId, null, "document.received", "{\"a\":1}", "bad", false,
                "wh-key-2");

        assertThat(jdbc.queryForObject("select status from webhook_events where id = ?", String.class,
                eventId)).isEqualTo("REJECTED");
    }
}
