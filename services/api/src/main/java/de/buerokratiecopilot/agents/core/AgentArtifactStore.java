package de.buerokratiecopilot.agents.core;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Stores the artifacts catalogue agents produce.
 *
 * <p>Two output families exist, both written through this component, because the catalogue grew in
 * waves and the two waves persist different things:</p>
 *
 * <ul>
 *   <li>{@link #store} registers a rendered file (feed, price book, route plan, quote) as a
 *       {@code generated_documents} row and puts the bytes into the configured
 *       {@link BlobStore}. The row is the searchable registry; a storage key alone cannot be found
 *       again, and a row without bytes would claim an artifact that does not exist.</li>
 *   <li>{@link #put} stores a prepared JSON artifact (index, dossier, exposé, calculation) in
 *       {@code agent_artifacts}, keyed by a source key and carrying a content hash. This is the
 *       honest output for domains whose master data is not modelled in the platform yet.</li>
 * </ul>
 *
 * <p>Both paths are idempotent, each keyed by the source key the calling agent derives from the
 * inputs that define the artifact. {@code store} returns the first artifact for a repeated source
 * instead of writing a second copy; {@code put} reuses the stored object when the payload hash is
 * unchanged and replaces it in place when the content changed. A scheduled feed must not grow a new
 * object every night, and a second run must never duplicate a dossier.</p>
 */
@Component
public class AgentArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(AgentArtifactStore.class);

    private static final String SUBJECT_TYPE = "AGENT_ARTIFACT";

    static final String CONTENT_TYPE = "application/json";
    private static final int MAX_KIND = 60;
    private static final int MAX_SOURCE_KEY = 300;

    /** Serialises payloads with sorted map keys, so two runs with the same facts hash alike. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final BlobStore blobs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Autowired
    public AgentArtifactStore(JdbcTemplate jdbc, BlobStore blobs, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.mapper = mapper;
    }

    /** First-wave construction order, kept for the call sites that predate the canonical one. */
    public AgentArtifactStore(BlobStore blobs, JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, blobs, mapper);
    }

    /** Minimal construction for the artifact path; uses a private mapper for metadata JSON. */
    public AgentArtifactStore(JdbcTemplate jdbc, BlobStore blobs) {
        this(jdbc, blobs, new ObjectMapper());
    }

    /**
     * A stored artifact.
     *
     * <p>{@code id} identifies the record in the store that produced it ({@code generated_documents}
     * for {@link #store}, {@code agent_artifacts} for {@link #put}); {@code documentId} is the
     * generated-document id when the artifact went through the document registry. {@code created}
     * and {@code reused} are the two idempotency views of the same call: {@code store} reports
     * whether it wrote the row, {@code put} whether the stored object could be reused.</p>
     */
    public record Artifact(UUID id, UUID documentId, String kind, String sourceKey,
                           String storageKey, String contentHash, String filename,
                           String contentType, long sizeBytes, boolean created, boolean reused) {

        /** The rendered-file view: {@code documentId} is the generated_documents id. */
        public Artifact(UUID documentId, String storageKey, String filename, String contentType,
                        long sizeBytes, boolean created) {
            this(documentId, documentId, null, null, storageKey, null, filename, contentType,
                    sizeBytes, created, !created);
        }

        /** The prepared-blob view: {@code id} is the agent_artifacts id. */
        public Artifact(UUID id, String kind, String sourceKey, String storageKey,
                        String contentHash, long sizeBytes, boolean reused) {
            this(id, null, kind, sourceKey, storageKey, contentHash, null, null, sizeBytes,
                    false, reused);
        }
    }

    /**
     * Stores a rendered artifact once per {@code sourceKey}.
     *
     * @param format one of {@code PDF}, {@code DOCX}, {@code HTML}, {@code TXT}; CSV and JSON are
     *               stored as {@code TXT} because that is the closest format the generated-document
     *               schema allows. The exact media type travels in the metadata.
     */
    public Artifact store(UUID tenantId, UUID actorUserId, String sourceKey, String title,
                          String filename, String contentType, String format, String content) {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("An agent artifact needs a sourceKey");
        }
        if (content == null) {
            content = "";
        }
        Artifact existing = findExisting(tenantId, sourceKey);
        if (existing != null) {
            return existing;
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String hash = sha256(bytes);
        String outputFormat = normalizedFormat(format, contentType);
        BlobStore.StoredBlob stored = blobs.store(tenantId, new ByteArrayInputStream(bytes),
                bytes.length, contentType == null ? "application/octet-stream" : contentType);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceKey", sourceKey.strip());
        metadata.put("storageKey", stored.storageKey());
        metadata.put("filename", filename == null ? "artifact.txt" : filename);
        metadata.put("contentType", contentType);
        metadata.put("size", bytes.length);
        metadata.put("sha256", hash);

        try {
            UUID id = jdbc.queryForObject("""
                    insert into generated_documents (id, tenant_id, subject_type, title, output_format,
                                                     status, body_html, field_values, generated_by,
                                                     generated_at)
                    values (gen_random_uuid(), ?, ?, ?, ?, 'GENERATED', ?, ?::jsonb, ?, now())
                    returning id
                    """, UUID.class, tenantId, SUBJECT_TYPE, truncate(title, 300), outputFormat,
                    content, json(metadata), actorUserId);
            return new Artifact(id, id, SUBJECT_TYPE, sourceKey.strip(), stored.storageKey(), hash,
                    filename, contentType, bytes.length, true, false);
        }
        catch (RuntimeException ex) {
            // The row is the registry; without it the stored object would be unreachable.
            blobs.delete(stored.storageKey());
            throw ex;
        }
    }

    private Artifact findExisting(UUID tenantId, String sourceKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, field_values::text as field_values
                from generated_documents
                where tenant_id = ? and subject_type = ? and field_values ->> 'sourceKey' = ?
                  and status <> 'FAILED'
                order by created_at limit 1
                """, tenantId, SUBJECT_TYPE, sourceKey.strip());
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.getFirst();
        Map<String, Object> values = parseMap(row.get("field_values"));
        return new Artifact((UUID) row.get("id"), (UUID) row.get("id"), SUBJECT_TYPE,
                sourceKey.strip(), stringValue(values.get("storageKey")),
                stringValue(values.get("sha256")), stringValue(values.get("filename")),
                stringValue(values.get("contentType")), intValue(values.get("size")), false, true);
    }

    /**
     * Stores a prepared JSON artifact under {@code (tenant, kind, sourceKey)}.
     *
     * <p>When a row with the same source key and the same content hash exists, nothing is written
     * and the existing artifact is returned with {@code reused=true}. When the content changed, the
     * row is updated and a fresh object is stored; the previous object is left in place rather than
     * deleted, because deleting bytes is not reversible and an orphan is the cheaper mistake.</p>
     */
    public Artifact put(UUID tenantId, UUID createdBy, String kind, String sourceKey, Object payload) {
        require(tenantId != null, "tenantId");
        require(kind != null && !kind.isBlank() && kind.strip().length() <= MAX_KIND,
                "artifact kind");
        require(sourceKey != null && !sourceKey.isBlank()
                && sourceKey.strip().length() <= MAX_SOURCE_KEY, "source key");
        String cleanKind = kind.strip();
        String cleanKey = sourceKey.strip();

        byte[] content = serialise(payload);
        String contentHash = AgentInputs.sha256(content);

        List<Artifact> existing = jdbc.query("""
                select id, storage_key, content_hash, size_bytes
                from agent_artifacts
                where tenant_id = ? and artifact_kind = ? and source_key = ?
                """, (rs, rowNum) -> new Artifact(rs.getObject("id", UUID.class), cleanKind,
                cleanKey, rs.getString("storage_key"), rs.getString("content_hash"),
                rs.getLong("size_bytes"), false), tenantId, cleanKind, cleanKey);

        if (!existing.isEmpty() && existing.getFirst().contentHash().equals(contentHash)
                && blobs.exists(existing.getFirst().storageKey())) {
            return new Artifact(existing.getFirst().id(), cleanKind, cleanKey,
                    existing.getFirst().storageKey(), contentHash,
                    existing.getFirst().sizeBytes(), true);
        }

        BlobStore.StoredBlob stored = blobs.store(tenantId, new ByteArrayInputStream(content),
                content.length, CONTENT_TYPE);

        if (!existing.isEmpty()) {
            UUID id = existing.getFirst().id();
            jdbc.update("""
                    update agent_artifacts
                       set storage_key = ?, storage_backend = ?, content_type = ?,
                           content_hash = ?, size_bytes = ?, updated_at = now()
                     where id = ? and tenant_id = ?
                    """, stored.storageKey(), stored.backend(), CONTENT_TYPE, contentHash,
                    (long) content.length, id, tenantId);
            log.info("Agent artifact {} ({}/{}) replaced", id, cleanKind, cleanKey);
            return new Artifact(id, cleanKind, cleanKey, stored.storageKey(), contentHash,
                    content.length, false);
        }

        UUID id = jdbc.queryForObject("""
                insert into agent_artifacts (tenant_id, artifact_kind, source_key, storage_key,
                                             storage_backend, content_type, content_hash, size_bytes,
                                             created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, tenantId, cleanKind, cleanKey, stored.storageKey(),
                stored.backend(), CONTENT_TYPE, contentHash, (long) content.length, createdBy);
        log.info("Agent artifact {} ({}/{}) stored", id, cleanKind, cleanKey);
        return new Artifact(id, cleanKind, cleanKey, stored.storageKey(), contentHash,
                content.length, false);
    }

    /** The artifact stored for a source key, if any; used to answer "already prepared?". */
    public Optional<Artifact> find(UUID tenantId, String kind, String sourceKey) {
        if (tenantId == null || kind == null || sourceKey == null) {
            return Optional.empty();
        }
        String cleanKind = kind.strip();
        String cleanKey = sourceKey.strip();
        return jdbc.query("""
                select id, storage_key, content_hash, size_bytes
                from agent_artifacts
                where tenant_id = ? and artifact_kind = ? and source_key = ?
                """, (rs, rowNum) -> new Artifact(rs.getObject("id", UUID.class), cleanKind,
                cleanKey, rs.getString("storage_key"), rs.getString("content_hash"),
                rs.getLong("size_bytes"), true), tenantId, cleanKind, cleanKey)
                .stream().findFirst();
    }

    static byte[] serialise(Object payload) {
        try {
            return JSON.writeValueAsBytes(payload == null ? Map.of() : payload);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Agent artifact is not serialisable", ex);
        }
    }

    private static void require(boolean condition, String what) {
        if (!condition) {
            throw new IllegalArgumentException("An agent artifact needs a valid " + what);
        }
    }

    private static String normalizedFormat(String format, String contentType) {
        String candidate = format == null ? "" : format.strip().toUpperCase(Locale.ROOT);
        if (List.of("PDF", "DOCX", "HTML", "TXT").contains(candidate)) {
            return candidate;
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return type.contains("html") ? "HTML" : "TXT";
    }

    private Map<String, Object> parseMap(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {
            });
        }
        catch (Exception ex) {
            return Map.of();
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Cannot serialize artifact metadata", ex);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        }
        catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }
}
