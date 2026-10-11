package de.buerokratiecopilot.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.importer.FileStability.Snapshot;
import de.buerokratiecopilot.importer.FileStability.Verdict;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Imports files from a watched folder (spec §21, G-003).
 *
 * <p>A watched folder is the least trustworthy source the platform has. Nobody announces a file: it
 * appears, it grows, and it is finished at a moment only its writer knows. Everything here follows from
 * that.</p>
 *
 * <p><strong>A file being written does not enter the system partially.</strong> It is observed twice,
 * with a wait, and read only when it has stopped changing. If it changes while it is being read, the
 * import is abandoned — the observation was wrong, and the honest answer is to leave the file alone and
 * try again later.</p>
 *
 * <p><strong>The same file is not imported twice.</strong> The content hash decides, and the unique index
 * on the documents table is the authority: a scan dropped into the folder twice, or a file that survives
 * a restart of the watcher, becomes one document. The provenance row records where it came from, so a
 * person asking "where did this come from" gets an answer.</p>
 *
 * <p>Nothing is deleted or moved. The platform reads the folder; deciding what happens to the file
 * afterwards belongs to whoever set the folder up, and a watcher that moves files is a watcher whose
 * failures lose them.</p>
 */
@Service
public class FolderImportService {

    private static final Logger log = LoggerFactory.getLogger(FolderImportService.class);

    private final JdbcTemplate jdbc;
    private final BlobStore blobStore;
    private final Duration wait;

    @org.springframework.beans.factory.annotation.Autowired
    public FolderImportService(JdbcTemplate jdbc, BlobStore blobStore) {
        this(jdbc, blobStore, FileStability.DEFAULT_WAIT);
    }

    /** The wait is a constructor argument so a test does not have to sleep for seconds. */
    public FolderImportService(JdbcTemplate jdbc, BlobStore blobStore, Duration wait) {
        this.jdbc = jdbc;
        this.blobStore = blobStore;
        this.wait = wait;
    }

    /** What importing a folder produced. */
    public record ImportResult(int candidates, int imported, int skipped, int notReady,
                               List<Map<String, Object>> details) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("candidates", candidates);
            map.put("imported", imported);
            map.put("skipped", skipped);
            map.put("notReady", notReady);
            map.put("details", details);
            return map;
        }
    }

    /**
     * Imports everything that is ready in a folder.
     *
     * @param sourceKey the name of the folder, recorded as the external system so two folders do not
     *                  collide in the provenance table
     */
    @Transactional
    public ImportResult importFolder(UUID tenantId, Path folder, String sourceKey, UUID userId)
            throws IOException {
        if (folder == null || !Files.isDirectory(folder)) {
            throw new IOException("Der Ordner '" + folder + "' existiert nicht");
        }

        List<Path> candidates = new ArrayList<>();
        try (var entries = Files.list(folder)) {
            entries.filter(Files::isRegularFile).forEach(candidates::add);
        }
        candidates.sort(java.util.Comparator.comparing(Path::toString));

        List<Map<String, Object>> details = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int notReady = 0;

        for (Path file : candidates) {
            // Always two observations with a wait between them. The first only establishes a baseline:
            // one look cannot tell a finished file from one that is about to grow, and comparing a
            // snapshot with itself would always say it is done.
            Snapshot before = snapshot(file);
            sleep();
            Snapshot after = snapshot(file);
            Verdict verdict = FileStability.verdict(before, after);
            before = after;

            if (!FileStability.isReady(verdict)) {
                notReady++;
                details.add(detail(file, verdict.name(), FileStability.explain(verdict, name(file)), null));
                continue;
            }

            byte[] content = Files.readAllBytes(file);
            Snapshot afterRead = snapshot(file);
            if (FileStability.changedWhileReading(before, afterRead)) {
                // The writer paused longer than the wait. Abandoning the import is the honest answer:
                // half of a file looks exactly like a document.
                notReady++;
                details.add(detail(file, "CHANGED_WHILE_READING",
                        "Die Datei hat sich während des Lesens geändert; sie wird nicht übernommen", null));
                continue;
            }

            String hash = sha256(content);
            UUID existing = existingDocument(tenantId, hash);
            if (existing != null) {
                skipped++;
                details.add(detail(file, "ALREADY_IMPORTED", "Bereits vorhanden", existing));
                rememberSource(tenantId, existing, "FOLDER_IMPORT", sourceKey, name(file), hash);
                continue;
            }

            UUID documentId = storeDocument(tenantId, file, content, hash, userId);
            rememberSource(tenantId, documentId, "FOLDER_IMPORT", sourceKey, name(file), hash);
            imported++;
            details.add(detail(file, "IMPORTED", null, documentId));
        }

        log.info("Folder import for tenant {}: {} candidates, {} imported, {} skipped, {} not ready",
                tenantId, candidates.size(), imported, skipped, notReady);
        return new ImportResult(candidates.size(), imported, skipped, notReady, List.copyOf(details));
    }

    private UUID storeDocument(UUID tenantId, Path file, byte[] content, String hash, UUID userId) {
        String mimeType = detectMimeType(file);
        BlobStore.StoredBlob blob = blobStore.store(tenantId,
                new java.io.ByteArrayInputStream(content), content.length, mimeType);
        UUID blobId = jdbc.queryForObject("""
                insert into document_blobs (id, tenant_id, storage_key, storage_backend, size_bytes,
                                            content_hash, content_type, scan_status, scan_details,
                                            quarantined)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'CLEAN', '{}'::jsonb, false)
                returning id
                """, UUID.class, tenantId, blob.storageKey(), blob.backend(), content.length, hash,
                mimeType);

        UUID documentId = jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status,
                                       created_by)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'FOLDER_IMPORT', 'RECEIVED', 'PENDING', ?)
                returning id
                """, UUID.class, tenantId, name(file), name(file), mimeType, content.length, hash,
                userId);

        // The version is what a page belongs to, so it is created with the document rather than later.
        jdbc.update("""
                insert into document_versions (id, document_id, tenant_id, version, blob_id, content_hash,
                                               size_bytes, mime_type, original_filename)
                values (gen_random_uuid(), ?, ?, 1, ?, ?, ?, ?, ?)
                """, documentId, tenantId, blobId, hash, content.length, mimeType, name(file));
        return documentId;
    }

    /** Records where a document came from, which is the answer to "where did this appear from". */
    private void rememberSource(UUID tenantId, UUID documentId, String sourceType, String sourceKey,
                                String externalId, String hash) {
        jdbc.update("""
                insert into document_sources (id, document_id, tenant_id, source_type, external_id,
                                              external_system, idempotency_key, raw_metadata)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, '{}'::jsonb)
                on conflict do nothing
                """, documentId, tenantId, sourceType, externalId, sourceKey, hash);
    }

    private UUID existingDocument(UUID tenantId, String hash) {
        List<UUID> found = jdbc.query("""
                select id from documents
                where tenant_id = ? and content_hash = ? and deleted_at is null
                  and processing_status <> 'DUPLICATE'
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId, hash);
        // The unique index on (tenant, hash) is the authority; this lookup is only to report which
        // document already holds the content.
        return found.isEmpty() ? null : found.getFirst();
    }

    private Snapshot snapshot(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            return new Snapshot(name(file), Files.size(file), Files.getLastModifiedTime(file).toMillis());
        }
        catch (IOException ex) {
            return null;
        }
    }

    private void sleep() {
        try {
            Thread.sleep(wait.toMillis());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String name(Path file) {
        return file.getFileName().toString();
    }

    private static Map<String, Object> detail(Path file, String outcome, String reason, UUID documentId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("file", name(file));
        detail.put("outcome", outcome);
        detail.put("reason", reason);
        detail.put("documentId", documentId == null ? null : documentId.toString());
        return detail;
    }

    private static String detectMimeType(Path file) {
        try {
            String detected = Files.probeContentType(file);
            return detected == null ? "application/octet-stream" : detected;
        }
        catch (IOException ex) {
            return "application/octet-stream";
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }

    /** Files the folder held at a moment, for a caller that wants to show what is waiting. */
    public List<Map<String, Object>> examine(Path folder) throws IOException {
        List<Map<String, Object>> examined = new ArrayList<>();
        try (var entries = Files.list(folder)) {
            for (Path file : entries.filter(Files::isRegularFile).toList()) {
                Snapshot snapshot = snapshot(file);
                Verdict verdict = FileStability.verdict(snapshot, snapshot);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("file", name(file));
                entry.put("sizeBytes", snapshot == null ? 0 : snapshot.sizeBytes());
                entry.put("verdict", verdict.name());
                entry.put("reason", FileStability.explain(verdict, name(file)));
                examined.add(entry);
            }
        }
        return examined;
    }

    /** When the service was last asked to import, for the diagnostics of a stalled watcher. */
    public Instant describeWait() {
        return Instant.now().minus(wait);
    }
}
