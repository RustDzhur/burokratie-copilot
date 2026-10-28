package de.buerokratiecopilot.shared.idempotency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiErrorResponse;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Makes unsafe requests idempotent through the {@code Idempotency-Key} header (spec §7.4, §16.3).
 *
 * <p>Behaviour:</p>
 * <ul>
 *   <li>a request without the header is passed through unchanged — idempotency is opt-in,</li>
 *   <li>the first request with a key performs the operation and its successful response is stored,</li>
 *   <li>a repeat with the same key and the same payload returns the stored response without running
 *       the operation again,</li>
 *   <li>a repeat while the first request is still running answers 409 instead of doing the work
 *       twice (a double click must not create two invoices),</li>
 *   <li>the same key with a different payload answers 422: silently replaying a different operation
 *       would be worse than an error,</li>
 *   <li>a failed attempt releases the key so the client can correct the input and retry.</li>
 * </ul>
 *
 * <p>Scope: the filter acts on authenticated requests with a small, non-multipart body. Document
 * uploads are deliberately excluded — buffering a large upload to fingerprint it would break the
 * streaming requirement of spec §27, and duplicates of documents are handled by the content hash
 * instead (task B-004).</p>
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";

    /** Bounds the memory a single request can cost; larger payloads are not fingerprinted. */
    private static final int MAX_BUFFERED_BODY = 1024 * 1024;

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:\\-]{8,200}$");

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public IdempotencyFilter(IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (!requiresProtection(request) || key == null || key.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        UUID tenantId = RequestContext.tenantId().orElse(null);
        if (tenantId == null) {
            // Public endpoints (registration, sign-in) have no tenant yet; a cross-tenant
            // deduplication table must not exist, so they are passed through unchanged.
            chain.doFilter(request, response);
            return;
        }

        if (!KEY_PATTERN.matcher(key).matches()) {
            writeError(response, ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must be 8-200 characters of [A-Za-z0-9._:-]");
            return;
        }

        byte[] body = bufferBody(request);
        if (body == null) {
            chain.doFilter(request, response);
            return;
        }

        String hash = fingerprint(request.getMethod(), request.getRequestURI(), body);
        IdempotencyService.Claim claim = idempotencyService.claim(tenantId, key, request.getMethod(),
                request.getRequestURI(), hash);

        switch (claim.outcome()) {
            case REPLAY -> replayStoredResponse(response, claim.record());
            case IN_PROGRESS -> writeError(response, ErrorCode.CONFLICT,
                    "A request with this Idempotency-Key is still being processed");
            case KEY_REUSED -> writeError(response, ErrorCode.CONFLICT,
                    "This Idempotency-Key was already used for a different request");
            case PROCEED -> proceed(request, response, chain, body, claim.record().getId());
        }
    }

    private void proceed(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
                         byte[] body, UUID recordId) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
        }
        finally {
            int status = wrapped.getStatus();
            if (status >= 200 && status < 300) {
                idempotencyService.complete(recordId, status, responseBodyAsMap(wrapped));
            }
            else if (status >= 400) {
                // Only successful operations are remembered; a rejected request must stay retryable.
                idempotencyService.release(recordId);
            }
            wrapped.copyBodyToResponse();
        }
    }

    private void replayStoredResponse(HttpServletResponse response, IdempotencyRecord record) throws IOException {
        response.setStatus(record.getResponseStatus() == null ? 200 : record.getResponseStatus());
        Map<String, Object> stored = record.getResponseBody();
        if (stored == null || stored.isEmpty()) {
            return;
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Idempotent-Replay", "true");
        objectMapper.writeValue(response.getOutputStream(), stored);
    }

    private Map<String, Object> responseBodyAsMap(ContentCachingResponseWrapper response) {
        byte[] content = response.getContentAsByteArray();
        if (content.length == 0 || content.length > MAX_BUFFERED_BODY) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.convertValue(node, Map.class);
            return map;
        }
        catch (IOException | IllegalArgumentException ex) {
            // A response that is not JSON simply has no replayable body; the status is still replayed.
            return Map.of();
        }
    }

    private byte[] bufferBody(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("multipart/")) {
            return null;
        }
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_BUFFERED_BODY) {
            return null;
        }
        byte[] body = request.getInputStream().readAllBytes();
        return body.length > MAX_BUFFERED_BODY ? null : body;
    }

    private static boolean requiresProtection(HttpServletRequest request) {
        // A response that contains a credential must never be replayed from the store: the row would
        // hold the plaintext token for as long as the key lives, which is exactly what issuing a token
        // avoids (api_tokens keeps only a hash). The screen does not send a key here; a third-party
        // client that does gets a fresh answer instead of a cached secret.
        if ("POST".equals(request.getMethod()) && "/api/v1/api-tokens".equals(request.getRequestURI())) {
            return false;
        }
        String method = request.getMethod();
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method);
    }

    /** Fingerprint of the operation: method, path and body. A different payload is a different request. */
    private static String fingerprint(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(path.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }

    private void writeError(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiErrorResponse.of(code.name(), message,
                RequestContext.correlationId().orElse(null), null));
    }
}
