package de.buerokratiecopilot.ingest.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.ingest.IngestService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The machine interface for delivering documents (spec §7, §20).
 *
 * <p>{@code IngestService} had tokens, scopes, rate limits, idempotency and duplicate detection — and no
 * way in. Nothing referenced it from the web layer, so the Desktop Bridge pointed at
 * {@code /api/v1/documents:ingest} and that address did not exist. A service that cannot be called is a
 * service that does not work, however well it is tested.</p>
 *
 * <p><strong>The content hash is computed here, not accepted from the caller.</strong> It is what the
 * duplicate detection compares and what the stored row is keyed on; taking it from the client would let a
 * caller declare two different documents identical, or the same document new.</p>
 *
 * <p><strong>The token is presented in the {@code Authorization} header</strong>, the same way everywhere
 * else in this platform, and the scope decides what may be delivered. A device token that could only scan
 * must not be able to write through the general import path.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Ingest", description = "Delivery of documents by machines")
public class IngestController {

    private final IngestService ingest;

    public IngestController(IngestService ingest) {
        this.ingest = ingest;
    }

    /**
     * Takes a document.
     *
     * <p>The body is the file itself rather than a base64 field: a scan is megabytes, and wrapping it in
     * JSON costs a third more bytes and a copy of the whole thing in memory.</p>
     */
    @PostMapping(path = "/documents:ingest", consumes = MediaType.ALL_VALUE)
    @Operation(summary = "Delivers a document; needs an API token with documents:write")
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Filename", required = false) String filename,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            @RequestBody(required = false) byte[] content) {

        IngestService.Caller caller = ingest.authenticate(bearer(authorization));
        if (!caller.may("documents:write")) {
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Dieser Token darf keine Dokumente übernehmen");
        }
        if (content == null || content.length == 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Inhalt ist leer");
        }
        if (filename == null || filename.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ohne Dateinamen lässt sich das Dokument später nicht wiederfinden");
        }

        // Computed here: the hash decides what counts as a duplicate, and a caller who could choose it
        // could declare two documents identical or one document new.
        String hash = sha256(content);
        String mimeType = contentType == null || contentType.isBlank() ? "application/octet-stream"
                : contentType.split(";")[0].strip();

        // The caller acts in the tenant the token belongs to; that is what the token means.
        IngestService.Ingested result = RequestContext.runAs(caller.tenantId(), () -> ingest.ingest(caller,
                idempotencyKey, filename, mimeType, hash, content, null));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("documentId", result.documentId().toString());
        body.put("duplicate", result.duplicate());
        body.put("message", result.message());
        body.put("contentHash", hash);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    /** The token from an {@code Authorization: Bearer ...} header, or nothing. */
    static String bearer(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String trimmed = authorization.strip();
        return trimmed.regionMatches(true, 0, "Bearer ", 0, 7) ? trimmed.substring(7).strip() : trimmed;
    }

    /**
     * The hash of the content.
     *
     * <p>SHA-256 in lower-case hexadecimal, which is the shape the column and its constraint expect —
     * a hash in another shape fails at the database rather than at the door.</p>
     */
    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }

    /** A token the caller can be given, for an administrator issuing one to a device. */
    public static String describeToken(UUID tokenId) {
        return tokenId == null ? null : new String(tokenId.toString().getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
