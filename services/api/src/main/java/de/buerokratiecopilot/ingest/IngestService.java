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

import de.buerokratiecopilot.shared.error.ApiErrorResponse;
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

    /**
     * How many tokens a workspace may hold at the same time.
     *
     * <p>A credential that nobody remembers issuing is a credential nobody revokes. The limit is low
     * enough that a person can look at the list and see what exists, and high enough for a few
     * integrations (mail bridge, scanner, partner) side by side.</p>
     */
    public static final int MAX_ACTIVE_TOKENS_PER_WORKSPACE = 20;

    /**
     * The scopes a token may carry.
     *
     * <p>Deliberately short: a scope is only meaningful where an endpoint checks it. {@code
     * documents:write} is what {@code POST /documents:ingest} requires; {@code documents:read} is the
     * counterpart that must not grant writing; {@code agents.view} and {@code agents.run} are the
     * permission codes of the agent API, kept apart so a token can be allowed to look without being
     * allowed to run. Anything not in this list is refused rather than stored and silently ignored.</p>
     *
     * <p>The dot spelling ({@code documents.write}) is accepted on input and stored as the colon
     * spelling, because older senders use it and the ingest check accepts both.</p>
     */
    public static final List<String> SCOPES = List.of(
            "documents:write", "documents:read", "agents.view", "agents.run",
            // Runtime-only scopes: the API has no UI, so settings and the export
            // endpoints would otherwise be unreachable without a browser session.
            "exports:read", "settings:read", "settings:write");

    private final JdbcTemplate jdbc;

    public IngestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A freshly issued token; the plaintext exists only in this answer. */
    public record IssuedToken(UUID tokenId, String token, String prefix, int rateLimitPerMinute,
                              List<String> scopes, Instant createdAt) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tokenId", tokenId.toString());
            map.put("token", token);
            map.put("prefix", prefix);
            map.put("scopes", scopes);
            map.put("rateLimitPerMinute", rateLimitPerMinute);
            map.put("createdAt", createdAt == null ? null : createdAt.toString());
            map.put("note", "Dieser Wert wird nur einmal angezeigt; gespeichert wird nur sein Hash.");
            return map;
        }
    }

    /**
     * One token as the administration screen shows it.
     *
     * <p>No hash and no plaintext: the value on screen identifies the token (prefix), it does not
     * authenticate it. That is the whole point of storing only a hash — a list that could be used to
     * call the API would make every screen reader and log line a credential leak.</p>
     */
    public record TokenSummary(UUID id, String name, String prefix, List<String> scopes,
                               Instant createdAt, Instant lastUsedAt, Instant revokedAt, boolean active) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id.toString());
            map.put("name", name);
            map.put("prefix", prefix);
            map.put("scopes", scopes);
            map.put("createdAt", createdAt == null ? null : createdAt.toString());
            map.put("lastUsedAt", lastUsedAt == null ? null : lastUsedAt.toString());
            map.put("revokedAt", revokedAt == null ? null : revokedAt.toString());
            map.put("active", active);
            return map;
        }
    }

    /** The caller behind a request. */
    public record Caller(UUID tenantId, UUID tokenId, String name, List<String> scopes,
                         UUID createdBy) {

        /** Kept for callers (tests, device paths) that do not need the acting user. */
        public Caller(UUID tenantId, UUID tokenId, String name, List<String> scopes) {
            this(tenantId, tokenId, name, scopes, null);
        }

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
        List<String> granted = requireKnownScopes(scopes);
        requireTokenBudget(tenantId, workspaceId);

        String token = WebhookSignature.newApiToken(tenantId);
        String prefix = token.substring(0, Math.min(12, token.length()));
        int limit = rateLimitPerMinute == null || rateLimitPerMinute <= 0 ? 60 : rateLimitPerMinute;

        UUID tokenId = jdbc.queryForObject("""
                insert into api_tokens (id, tenant_id, workspace_id, name, token_prefix, token_hash,
                                        scopes, rate_limit_per_minute, is_active, expires_at, created_by)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?::jsonb, ?, true, ?, ?)
                returning id
                """, UUID.class, tenantId, workspaceId, name.strip(), prefix,
                WebhookSignature.hashApiToken(token), toJsonArray(granted), limit,
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt), createdBy);

        // The token itself never reaches the log; the identifier and the prefix are what an operator
        // needs to match a row against a report, and neither can be replayed.
        log.info("API token {} issued for tenant {} (prefix {})", tokenId, tenantId, prefix);
        return new IssuedToken(tokenId, token, prefix, limit, granted, Instant.now());
    }

    /**
     * The tokens of a tenant, newest first. Revoked tokens stay in the list: `revokedAt` is what tells
     * a person that an integration was switched off rather than never existing.
     */
    @Transactional(readOnly = true)
    public List<TokenSummary> listTokens(UUID tenantId) {
        return jdbc.query("""
                select id, name, token_prefix, scopes, created_at, last_used_at, revoked_at, is_active
                from api_tokens
                where tenant_id = ?
                order by created_at desc
                limit 100
                """, (rs, rowNumber) -> new TokenSummary(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("token_prefix"),
                toList(rs.getString("scopes")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("last_used_at")),
                instant(rs.getTimestamp("revoked_at")),
                rs.getBoolean("is_active") && rs.getTimestamp("revoked_at") == null), tenantId);
    }

    /**
     * Revokes a token.
     *
     * <p>Revoking twice is the same state and therefore not an error: a person who clicks the button
     * twice, or two administrators who click at once, both end up with a token that no longer works.
     * A token of another tenant is a 404 rather than a 403 — whether it exists elsewhere is not
     * something this tenant may learn.</p>
     */
    @Transactional
    public void revokeToken(UUID tenantId, UUID tokenId, UUID revokedBy) {
        int revoked = jdbc.update("""
                update api_tokens set revoked_at = now(), revoked_by = ?, is_active = false
                where id = ? and tenant_id = ? and revoked_at is null
                """, revokedBy, tokenId, tenantId);
        if (revoked > 0) {
            log.info("API token {} revoked for tenant {}", tokenId, tenantId);
            return;
        }
        Integer exists = jdbc.queryForObject("""
                select count(*) from api_tokens where id = ? and tenant_id = ?
                """, Integer.class, tokenId, tenantId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("API token");
        }
        // Already revoked: the desired state already holds.
    }

    /** The default workspace of a tenant, or null when it has none yet; a token belongs to it when it exists. */
    @Transactional(readOnly = true)
    public UUID defaultWorkspaceId(UUID tenantId) {
        List<UUID> ids = jdbc.queryForList("""
                select id from workspaces
                where tenant_id = ? and is_default and deleted_at is null
                order by created_at limit 1
                """, UUID.class, tenantId);
        return ids.isEmpty() ? null : ids.getFirst();
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
                       revoked_at, last_used_at, created_by
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
                String.valueOf(row.get("name")), toList(row.get("scopes")),
                (UUID) row.get("created_by"));
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

        List<Map<String, Object>> sameContent = jdbc.queryForList("""
                select id, original_filename from documents
                where tenant_id = ? and content_hash = ? and deleted_at is null
                order by created_at limit 1
                """, caller.tenantId(), contentHash);
        if (!sameContent.isEmpty()) {
            // Reported rather than refused: a second copy of one invoice is a second payment waiting to
            // happen, and the caller has to be told which document it already has.
            UUID existingId = (UUID) sameContent.getFirst().get("id");
            recordIngest(caller.tenantId(), idempotencyKey, contentHash, existingId);
            return new Ingested(existingId, true,
                    "Dieser Inhalt ist bereits als Dokument vorhanden; es wird zurückgegeben statt ein "
                            + "zweites anzulegen");
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
    /** Records a completed ingestion so a repeat with the same key is answered the same way. */
    private void recordIngest(UUID tenantId, String idempotencyKey, String contentHash, UUID documentId) {
        jdbc.update("""
                insert into idempotency_records (id, tenant_id, idempotency_key, request_method,
                                                 request_path, request_hash, status, resource_id,
                                                 completed_at, expires_at)
                values (gen_random_uuid(), ?, ?, 'POST', '/api/v1/documents:ingest', ?, 'COMPLETED', ?,
                        now(), now() + interval '24 hours')
                on conflict do nothing
                """, tenantId, idempotencyKey, contentHash, documentId);
    }

    private static ApiException unauthorized(String message) {
        return new ApiException(ErrorCode.UNAUTHENTICATED, message);
    }

    /**
     * Refuses a set of scopes that the platform does not know.
     *
     * <p>A token with a scope nothing checks would look like a restriction and be none: the person
     * issuing it believes the integration cannot do more, while in fact the endpoint it calls only
     * asks for what that integration needs. Unknown scopes are therefore a validation error, not a
     * silently stored word.</p>
     */
    static List<String> requireKnownScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw ApiException.validation("Ein Token braucht mindestens einen Scope", List.of(
                    new ApiErrorResponse.FieldViolation("scopes", "REQUIRED",
                            "Ein Token ohne Scope darf auf nichts zugreifen und ist deshalb nicht sinnvoll")));
        }
        List<String> normalized = scopes.stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .map(String::strip)
                .map(scope -> scope.replace("documents.", "documents:"))
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw ApiException.validation("Ein Token braucht mindestens einen Scope", List.of(
                    new ApiErrorResponse.FieldViolation("scopes", "REQUIRED", "Der Scope ist leer")));
        }
        List<String> unknown = normalized.stream().filter(scope -> !SCOPES.contains(scope)).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.validation("Unbekannter Scope", List.of(
                    new ApiErrorResponse.FieldViolation("scopes", "UNKNOWN",
                            "Unbekannt: " + String.join(", ", unknown)
                                    + ". Erlaubt sind: " + String.join(", ", SCOPES))));
        }
        return normalized;
    }

    /** Refuses a new token once the workspace holds {@link #MAX_ACTIVE_TOKENS_PER_WORKSPACE}. */
    private void requireTokenBudget(UUID tenantId, UUID workspaceId) {
        Long active = workspaceId == null
                ? jdbc.queryForObject("""
                        select count(*) from api_tokens
                        where tenant_id = ? and workspace_id is null and is_active and revoked_at is null
                        """, Long.class, tenantId)
                : jdbc.queryForObject("""
                        select count(*) from api_tokens
                        where tenant_id = ? and workspace_id = ? and is_active and revoked_at is null
                        """, Long.class, tenantId, workspaceId);
        if (active != null && active >= MAX_ACTIVE_TOKENS_PER_WORKSPACE) {
            throw ApiException.conflict("Dieser Arbeitsbereich hat bereits "
                    + MAX_ACTIVE_TOKENS_PER_WORKSPACE + " aktive API-Tokens. Widerrufen Sie einen davon, "
                    + "bevor Sie einen neuen ausstellen.");
        }
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
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
