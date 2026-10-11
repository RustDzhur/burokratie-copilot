package de.buerokratiecopilot.ingest;

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
 * Document ingest from external sources (spec §30, B-013).
 *
 * <p>An endpoint that accepts documents from outside is an open door unless three things hold, and all
 * three are implemented here.</p>
 *
 * <p><strong>Authentication.</strong> An API token is shown exactly once, at issue, and only its hash is
 * stored. A platform that kept the token itself would leak every integration's credential the moment its
 * database did.</p>
 *
 * <p><strong>Idempotency.</strong> A repeat with the same key does not create a duplicate. This is not a
 * nicety: senders retry on timeouts, and a retry that produces a second document means the same invoice
 * is paid twice.</p>
 *
 * <p><strong>Signature.</strong> A caller that does not hold the shared secret is refused before
 * anything it sent is read. The order matters — the body is verified first, and a failed verification
 * leaves no record of a partial ingest.</p>
 *
 * <p>The rate limit is per token and taken from the token's own row rather than from a global constant,
 * because how much a partner may send is a decision about that partner.</p>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final JdbcTemplate jdbc;

    public IngestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A freshly issued token; the plaintext exists only in this answer. */
    public record IssuedToken(UUID tokenId, String token, String prefix, int rateLimitPerMinute) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tokenId", tokenId.toString());
            map.put("token", token);
            map.put("prefix", prefix);
            map.put("rateLimitPerMinute", rateLimitPerMinute);
            map.put("note", "Dieser Wert wird nur einmal angezeigt; gespeichert wird nur sein Hash.");
            return map;
        }
    }

    /** The caller behind a request. */
    public record Caller(UUID tenantId, UUID tokenId, String name, List<String> scopes) {

        public boolean may(String scope) {
            return scopes.contains(scope) || scopes.contains("documents.write");
        }
    }

    /** What an ingest produced. */
    public record Ingested(UUID documentId, boolean duplicate, String message) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("documentId", documentId.toString());
            map.put("duplicate", duplicate);
            map.put("message", message);
            return map;
        }
    }

    /** Issues an API token; the plaintext is returned once and never stored. */
    @Transactional
    public IssuedToken issueToken(UUID tenantId, UUID workspaceId, String name, List<String> scopes,
                                  Integer rateLimitPerMinute, Instant expiresAt, UUID createdBy) {
        if (name == null || name.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Token braucht einen Namen");
        }
        String token = WebhookSignature.newApiToken(tenantId);
        String prefix = token.substring(0, Math.min(12, token.length()));
        int limit = rateLimitPerMinute == null || rateLimitPerMinute <= 0 ? 60 : rateLimitPerMinute;

        UUID tokenId = jdbc.queryForObject("""
                insert into api_tokens (id, tenant_id, workspace_id, name, token_prefix, token_hash,
                                        scopes, rate_limit_per_minute, is_active, expires_at, created_by)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?::jsonb, ?, true, ?, ?)
                returning id
                """, UUID.class, tenantId, workspaceId, name.strip(), prefix,
                WebhookSignature.hashApiToken(token), toJsonArray(scopes), limit, expiresAt, createdBy);

        log.info("API token {} issued for tenant {} (prefix {})", tokenId, tenantId, prefix);
        return new IssuedToken(tokenId, token, prefix, limit);
    }

    /**
     * Authenticates a presented token.
     *
     * @throws ApiException with 401 when the token is unknown, revoked, expired or over its rate limit
     */
    @Transactional
    public Caller authenticate(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            throw unauthorized("Kein API-Token übergeben");
        }
        String hash = WebhookSignature.hashApiToken(presentedToken);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, tenant_id, name, scopes, rate_limit_per_minute, is_active, expires_at,
                       revoked_at, last_used_at
                from api_tokens where token_hash = ?
                """, hash);
        if (rows.isEmpty()) {
            throw unauthorized("Der API-Token ist unbekannt");
        }
        Map<String, Object> row = rows.getFirst();

        if (!Boolean.TRUE.equals(row.get("is_active")) || row.get("revoked_at") != null) {
            throw unauthorized("Der API-Token wurde widerrufen");
        }
        Object expiresAt = row.get("expires_at");
        if (expiresAt != null && ((java.sql.Timestamp) expiresAt).toInstant().isBefore(Instant.now())) {
            throw unauthorized("Der API-Token ist abgelaufen");
        }

        int limit = ((Number) row.get("rate_limit_per_minute")).intValue();
        // The window is the current minute; the counter is advanced in a single statement so two
        // simultaneous requests cannot both read the same value.
        String bucket = "api_token:" + row.get("id");
        java.sql.Timestamp windowStart = java.sql.Timestamp
                .from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
        Integer used = jdbc.queryForObject("""
                insert into rate_limit_counters (bucket_key, window_start, counter, updated_at)
                values (?, ?, 1, now())
                on conflict (bucket_key, window_start)
                do update set counter = rate_limit_counters.counter + 1, updated_at = now()
                returning counter
                """, Integer.class, bucket, windowStart);

        if (used != null && used > limit) {
            throw rateLimited("Das Kontingent von " + limit + " Anfragen je Minute ist aufgebraucht");
        }
        jdbc.update("update api_tokens set last_used_at = now() where id = ?", row.get("id"));

        return new Caller((UUID) row.get("tenant_id"), (UUID) row.get("id"),
                String.valueOf(row.get("name")), toList(row.get("scopes")));
    }

    /**
     * Ingests a document.
     *
     * <p>The idempotency key is checked against the table the platform already keeps for that purpose,
     * with its unique constraint doing the deciding rather than a read-then-write in application code:
     * two simultaneous retries would both pass a check and only one can pass the constraint.</p>
     */
    @Transactional
    public Ingested ingest(Caller caller, String idempotencyKey, String filename, String mimeType,
                           String contentHash, byte[] content, UUID workspaceId) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ein Idempotenzschlüssel ist erforderlich; ohne ihn erzeugt jeder Wiederholungsversuch "
                            + "ein weiteres Dokument");
        }

        List<Map<String, Object>> existing = jdbc.queryForList("""
                select resource_id, status from idempotency_records
                where tenant_id = ? and idempotency_key = ?
                """, caller.tenantId(), idempotencyKey);
        if (!existing.isEmpty()) {
            Object resourceId = existing.getFirst().get("resource_id");
            if (resourceId != null) {
                // The same key means the same request. Returning the document that already exists is the
                // point: a sender that retried after a timeout must not produce a second document.
                return new Ingested((UUID) resourceId, true,
                        "Dieser Schlüssel wurde bereits verarbeitet; das vorhandene Dokument wird "
                                + "zurückgegeben");
            }
            throw ApiException.conflict("Dieser Idempotenzschlüssel wird gerade verarbeitet");
        }

        jdbc.update("""
                insert into idempotency_records (id, tenant_id, idempotency_key, request_method,
                                                 request_path, request_hash, status, expires_at)
                values (gen_random_uuid(), ?, ?, 'POST', '/api/v1/documents:ingest', ?, 'IN_PROGRESS',
                        now() + interval '24 hours')
                """, caller.tenantId(), idempotencyKey, contentHash);

        UUID documentId = jdbc.queryForObject("""
                insert into documents (id, tenant_id, workspace_id, original_filename, internal_name,
                                       mime_type, size_bytes, content_hash, source, processing_status,
                                       review_status)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, 'API', 'RECEIVED', 'PENDING')
                returning id
                """, UUID.class, caller.tenantId(), workspaceId, filename,
                filename == null ? "unbenannt" : filename, mimeType,
                content == null ? 0 : content.length, contentHash);

        jdbc.update("""
                update idempotency_records set status = 'COMPLETED', response_status = 201,
                       resource_type = 'document', resource_id = ?, completed_at = now()
                where tenant_id = ? and idempotency_key = ?
                """, documentId, caller.tenantId(), idempotencyKey);

        log.info("Document {} ingested for tenant {} with key {}", documentId, caller.tenantId(),
                idempotencyKey);
        return new Ingested(documentId, false, "Dokument aufgenommen");
    }

    /** Verifies a webhook signature, which happens before anything the caller sent is read. */
    public void requireValidSignature(String secret, long timestampSeconds, byte[] body,
                                      String presented) {
        if (!WebhookSignature.isValid(secret, timestampSeconds, body, presented,
                Instant.now().getEpochSecond())) {
            // 401 and not 400: the caller did not show that it holds the secret, and a body-level
            // complaint would tell an attacker which part it got right.
            throw unauthorized("Die Signatur ist ungültig");
        }
    }

    /** Records an inbound webhook, signed and verified. */
    @Transactional
    public UUID recordWebhook(UUID tenantId, UUID endpointId, String eventType, String payload,
                              String signature, boolean verified, String idempotencyKey) {
        return jdbc.queryForObject("""
                insert into webhook_events (id, endpoint_id, tenant_id, event_type, payload, direction,
                                            signature, signature_verified, status, attempts,
                                            idempotency_key)
                values (gen_random_uuid(), ?, ?, ?, ?::jsonb, 'INBOUND', ?, ?, ?, 1, ?)
                returning id
                """, UUID.class, endpointId, tenantId, eventType, payload, signature, verified,
                verified ? "RECEIVED" : "REJECTED", idempotencyKey);
    }

    /** 401: the caller did not show that it may send anything. */
    private static ApiException unauthorized(String message) {
        return new ApiException(ErrorCode.UNAUTHENTICATED, message);
    }

    /** 429: the caller may send, but not at this rate. */
    private static ApiException rateLimited(String message) {
        return new ApiException(ErrorCode.RATE_LIMITED, message);
    }

    private static String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream().map(value -> "\"" + value.replace("\"", "") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static List<String> toList(Object json) {
        if (json == null) {
            return List.of();
        }
        return java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(String.valueOf(json)).results()
                .map(match -> match.group(1)).toList();
    }
}
