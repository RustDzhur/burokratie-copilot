package de.buerokratiecopilot.documents.integrity;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Whether a stored document still is what it was when it was stored (spec §22).
 *
 * <p><strong>Why this exists.</strong> Every blob carries a {@code content_hash} and every version carries
 * one too, and nothing ever compared them with the bytes. A hash that is written and never checked is a
 * comment, not an integrity guarantee: it proves nothing about the file on disk today, only about the file
 * that was hashed on the day it arrived. For an archive that is supposed to outlive the software, that is the
 * wrong half of the story.</p>
 *
 * <p>The check reads the object through the same store the platform writes it with — so an encrypted blob is
 * decrypted on the way out and hashed in the clear — recomputes SHA-256 and compares. A mismatch is reported
 * as a mismatch with both values, never repaired silently: an archive that fixes itself without telling
 * anybody is worse than one that admits a file changed.</p>
 */
@Service
public class DocumentIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIntegrityService.class);

    private final JdbcTemplate jdbc;
    private final BlobStore blobs;

    public DocumentIntegrityService(JdbcTemplate jdbc, BlobStore blobs) {
        this.jdbc = jdbc;
        this.blobs = blobs;
    }

    /** What the check found. */
    public record Result(UUID documentId, Integer version, String expected, String actual, boolean matches,
                         String message) {
    }

    /**
     * Recomputes the hash of the stored file and compares it with the recorded one.
     *
     * <p>The current version is the one that counts: an older version keeps its own hash and is not what the
     * archive promises to preserve.</p>
     */
    @Transactional(readOnly = true)
    public Result verify(UUID tenantId, UUID documentId) {
        var version = jdbc.query("""
                select v.version, b.storage_key, coalesce(v.content_hash, b.content_hash) as expected
                  from document_versions v
                  join document_blobs b on b.id = v.blob_id
                 where v.document_id = ? and v.tenant_id = ?
                 order by v.version desc
                 limit 1
                """, rs -> {
            if (!rs.next()) {
                return null;
            }
            return new Object[] {rs.getInt("version"), rs.getString("storage_key"),
                    rs.getString("expected")};
        }, documentId, tenantId);

        if (version == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Zu diesem Dokument gibt es keine gespeicherte Datei");
        }

        int number = (Integer) version[0];
        String key = (String) version[1];
        String expected = (String) version[2];

        String actual;
        try (InputStream data = blobs.retrieve(key)) {
            actual = sha256(data);
        }
        catch (java.io.IOException ex) {
            // The file cannot be read at all: that is a finding, not a crash of the check.
            log.warn("Dokument {} konnte nicht gelesen werden: {}", documentId, ex.getMessage());
            return new Result(documentId, number, expected, null, false,
                    "Die Datei konnte nicht gelesen werden: " + ex.getMessage());
        }

        boolean matches = expected != null && expected.equalsIgnoreCase(actual);
        return new Result(documentId, number, expected, actual, matches,
                matches
                        ? "Die gespeicherte Datei stimmt mit ihrem Prüfwert überein."
                        : "Die Datei stimmt NICHT mit ihrem Prüfwert überein. Das ist ein Befund, kein "
                                + "behebbarer Fehler: die Datei wurde verändert oder beschädigt.");
    }

    /** SHA-256 of a stream, lower case hex — the same form the platform stores. */
    static String sha256(InputStream data) throws java.io.IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = data.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            // SHA-256 is part of every Java platform; if it is missing the JVM is broken.
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }
}
