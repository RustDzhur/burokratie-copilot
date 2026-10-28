package de.buerokratiecopilot.billing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Everything a customer put into the platform, as one ZIP: the documents with their original files, and the cases,
 * tasks and contacts as CSV files that open in any spreadsheet. This is what the right to data portability asks for
 * (Art. 20 GDPR), and what the customer is offered before the data is deleted.
 *
 * <p>The ZIP is written while it is read from the database and the file store, so a large archive is never held in
 * memory. A file that cannot be read is listed in {@code FEHLER.txt} instead of stopping the export.</p>
 */
@Service
public class TenantExportService {

    private static final Logger log = LoggerFactory.getLogger(TenantExportService.class);

    private final JdbcTemplate jdbc;
    private final BlobStore blobStore;

    public TenantExportService(JdbcTemplate jdbc, BlobStore blobStore) {
        this.jdbc = jdbc;
        this.blobStore = blobStore;
    }

    public void write(UUID tenantId, OutputStream out) throws IOException {
        StringBuilder problems = new StringBuilder();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, "LIESMICH.txt", ("Export aus dem Bürokratie-Copilot\n\n"
                    + "dokumente.csv  Liste aller Dokumente\n"
                    + "dateien/       die Originaldateien (Name: Dokument-ID und Dateiname)\n"
                    + "vorgaenge.csv  Vorgänge und Akten\n"
                    + "aufgaben.csv   Aufgaben\n"
                    + "kontakte.csv   Kontakte\n").getBytes(StandardCharsets.UTF_8));

            List<Map<String, Object>> documents = jdbc.queryForList("""
                    select d.id, d.original_filename, d.mime_type, d.document_date, d.sender, d.recipient,
                           d.document_number, d.processing_status, d.received_at, d.summary,
                           b.storage_key
                    from documents d
                    left join document_versions v on v.document_id = d.id and v.version = d.current_version
                    left join document_blobs b on b.id = v.blob_id
                    where d.tenant_id = ?
                    order by d.received_at
                    """, tenantId);
            put(zip, "dokumente.csv", csv(documents, List.of("id", "original_filename", "mime_type", "document_date",
                    "sender", "recipient", "document_number", "processing_status", "received_at", "summary")));
            for (Map<String, Object> document : documents) {
                String key = (String) document.get("storage_key");
                if (key == null) {
                    continue;
                }
                String name = "dateien/" + document.get("id") + "-" + safeName((String) document.get("original_filename"));
                try (InputStream in = blobStore.retrieve(key)) {
                    zip.putNextEntry(new ZipEntry(name));
                    in.transferTo(zip);
                    zip.closeEntry();
                }
                catch (RuntimeException | IOException ex) {
                    log.warn("Export of tenant {}: file {} could not be read", tenantId, key, ex);
                    problems.append(name).append('\n');
                }
            }

            put(zip, "vorgaenge.csv", csv(jdbc.queryForList(
                    "select reference, title, status, created_at from cases where tenant_id = ? order by created_at",
                    tenantId), List.of("reference", "title", "status", "created_at")));
            put(zip, "aufgaben.csv", csv(jdbc.queryForList(
                    "select title, description, status, due_at, completed_at, created_at from tasks "
                            + "where tenant_id = ? order by created_at", tenantId),
                    List.of("title", "description", "status", "due_at", "completed_at", "created_at")));
            put(zip, "kontakte.csv", csv(jdbc.queryForList(
                    "select display_name, email::text as email, phone, created_at from contacts "
                            + "where tenant_id = ? order by display_name", tenantId),
                    List.of("display_name", "email", "phone", "created_at")));
            if (!problems.isEmpty()) {
                put(zip, "FEHLER.txt", ("Diese Dateien konnten nicht gelesen werden:\n" + problems)
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    /** A file name without path parts or characters a file system or a ZIP reader would object to. */
    static String safeName(String name) {
        String clean = (name == null ? "datei" : name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        return clean.isEmpty() || ".".equals(clean) || "..".equals(clean) ? "datei" : clean;
    }

    /** CSV with a byte order mark so a spreadsheet opens umlauts correctly; every cell is quoted. */
    static byte[] csv(List<Map<String, Object>> rows, List<String> columns) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        bytes.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        try (Writer writer = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            writer.write(String.join(";", columns) + "\r\n");
            for (Map<String, Object> row : rows) {
                StringBuilder line = new StringBuilder();
                for (String column : columns) {
                    if (!line.isEmpty()) {
                        line.append(';');
                    }
                    Object value = row.get(column);
                    String text = value == null ? "" : value.toString();
                    // A cell that starts like a formula would be run by a spreadsheet: it is marked as text.
                    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
                        text = "'" + text;
                    }
                    line.append('"').append(text.replace("\"", "\"\"")).append('"');
                }
                writer.write(line + "\r\n");
            }
        }
        return bytes.toByteArray();
    }
}
