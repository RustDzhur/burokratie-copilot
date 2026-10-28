package de.buerokratiecopilot.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.importer.FileStability.Snapshot;
import de.buerokratiecopilot.importer.FileStability.Verdict;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Verifies importing from a watched folder (spec §21, G-003 acceptance).
 *
 * <p>The acceptance criterion is that a file being appended to at the moment of import does not enter the
 * system partially. A half-imported scan is worse than a missing one: it looks like a document, it gets a
 * hash, it may be classified and filed, and nobody notices the missing page. The tests write into a real
 * folder while importing, which is the only way to check that.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class FolderImportIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BlobStore blobStore;

    @TempDir
    Path folder;

    /** The wait is shortened so the test does not have to sleep for seconds. */
    private FolderImportService service() {
        return new FolderImportService(jdbc, blobStore, Duration.ofMillis(120));
    }

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Ordner Test', 'COMPANY') returning id
                """, UUID.class, "imp-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private Path write(String name, String content) throws IOException {
        Path file = folder.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private FolderImportService.ImportResult importFolder(UUID tenantId) throws IOException {
        return RequestContext.runAs(tenantId, () -> {
            try {
                return service().importFolder(tenantId, folder, "eingang", null);
            }
            catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private int documentCount(UUID tenantId) {
        Long count = jdbc.queryForObject("select count(*) from documents where tenant_id = ?", Long.class,
                tenantId);
        return count == null ? 0 : count.intValue();
    }

    // ---------------------------------------------------------------- the stability rule, on its own

    @Test
    @DisplayName("a file that is still growing is not ready")
    void growingFileIsNotReady() {
        Snapshot first = new Snapshot("scan.pdf", 100, 1000);
        Snapshot second = new Snapshot("scan.pdf", 200, 2000);

        assertThat(FileStability.verdict(first, second)).isEqualTo(Verdict.STILL_WRITING);
        assertThat(FileStability.isReady(Verdict.STILL_WRITING)).isFalse();
    }

    @Test
    @DisplayName("a file unchanged across the wait is ready")
    void unchangedFileIsReady() {
        Snapshot first = new Snapshot("scan.pdf", 100, 1000);
        Snapshot second = new Snapshot("scan.pdf", 100, 1000);

        assertThat(FileStability.verdict(first, second)).isEqualTo(Verdict.READY);
    }

    @Test
    @DisplayName("a first observation is never enough")
    void firstObservationIsNeverReady() {
        Snapshot only = new Snapshot("scan.pdf", 100, 1000);

        assertThat(FileStability.verdict(null, only))
                .as("one look cannot tell a finished file from one that is about to grow")
                .isEqualTo(Verdict.STILL_WRITING);
    }

    @Test
    @DisplayName("an empty file is not ready, because its writer has not started")
    void emptyFileIsNotReady() {
        assertThat(FileStability.verdict(new Snapshot("scan.pdf", 0, 1), new Snapshot("scan.pdf", 0, 1)))
                .as("importing it would create an empty document somebody has to find and delete")
                .isEqualTo(Verdict.EMPTY);
    }

    @Test
    @DisplayName("a file that disappeared is reported as gone")
    void vanishedFileIsGone() {
        assertThat(FileStability.verdict(new Snapshot("scan.pdf", 100, 1), null))
                .isEqualTo(Verdict.GONE);
    }

    @Test
    @DisplayName("a change while reading is detected")
    void changeWhileReadingIsDetected() {
        Snapshot before = new Snapshot("scan.pdf", 100, 1000);
        Snapshot after = new Snapshot("scan.pdf", 150, 1500);

        assertThat(FileStability.changedWhileReading(before, after))
                .as("a writer can pause longer than the wait, and the file then looks finished")
                .isTrue();
        assertThat(FileStability.changedWhileReading(before, new Snapshot("scan.pdf", 100, 1000)))
                .isFalse();
    }

    @Test
    @DisplayName("every verdict has an explanation a user can act on")
    void everyVerdictIsExplained() {
        for (Verdict verdict : Verdict.values()) {
            String explanation = FileStability.explain(verdict, "scan.pdf");
            if (verdict == Verdict.READY) {
                assertThat(explanation).isNull();
            }
            else {
                assertThat(explanation).as("verdict " + verdict + " needs wording").isNotBlank()
                        .contains("scan.pdf");
            }
        }
    }

    // ---------------------------------------------------------------- the import itself

    @Test
    @DisplayName("a settled file in the folder becomes a document")
    void settledFileBecomesADocument() throws Exception {
        UUID tenantId = tenant();
        write("rechnung.txt", "Eine Rechnung über 119,00 EUR.");

        FolderImportService.ImportResult result = importFolder(tenantId);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(documentCount(tenantId)).isEqualTo(1);
        Map<String, Object> document = jdbc.queryForMap("""
                select original_filename, source, content_hash from documents where tenant_id = ?
                """, tenantId);
        assertThat(document).containsEntry("original_filename", "rechnung.txt")
                .containsEntry("source", "FOLDER_IMPORT");
        assertThat(String.valueOf(document.get("content_hash"))).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("a file that is being written is not imported")
    void fileBeingWrittenIsNotImported() throws Exception {
        UUID tenantId = tenant();
        Path file = write("gross.pdf", "Erste Seite");

        // A writer that keeps appending, which is what a scanner does.
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    Files.writeString(file, "\nSeite " + i, StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.APPEND);
                    Thread.sleep(40);
                }
                catch (IOException | InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        writer.start();
        Thread.sleep(60);

        FolderImportService.ImportResult result = importFolder(tenantId);
        writer.join();

        assertThat(result.imported())
                .as("half of a scan looks exactly like a document")
                .isZero();
        assertThat(result.notReady()).isEqualTo(1);
        assertThat(documentCount(tenantId)).isZero();
        assertThat(result.details().getFirst()).containsEntry("outcome", "STILL_WRITING");
    }

    @Test
    @DisplayName("the same content dropped twice is one document")
    void sameContentIsOneDocument() throws Exception {
        UUID tenantId = tenant();
        write("scan.txt", "Derselbe Inhalt");

        FolderImportService.ImportResult first = importFolder(tenantId);
        Path copy = folder.resolve("scan-kopie.txt");
        Files.copy(folder.resolve("scan.txt"), copy);

        FolderImportService.ImportResult second = importFolder(tenantId);

        assertThat(first.imported()).isEqualTo(1);
        assertThat(second.imported()).isZero();
        assertThat(second.skipped())
                .as("the content hash decides, and the unique index is the authority")
                .isEqualTo(2);
        assertThat(documentCount(tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("where a document came from is recorded")
    void provenanceIsRecorded() throws Exception {
        UUID tenantId = tenant();
        write("eingang.txt", "Ein Vorgang");

        importFolder(tenantId);

        Map<String, Object> source = jdbc.queryForMap("""
                select source_type, external_id, external_system from document_sources where tenant_id = ?
                """, tenantId);
        assertThat(source).containsEntry("source_type", "FOLDER_IMPORT")
                .containsEntry("external_id", "eingang.txt")
                .containsEntry("external_system", "eingang");
    }

    @Test
    @DisplayName("an empty file in the folder is not imported")
    void emptyFileIsNotImported() throws Exception {
        UUID tenantId = tenant();
        Files.createFile(folder.resolve("leer.txt"));

        FolderImportService.ImportResult result = importFolder(tenantId);

        assertThat(result.imported()).isZero();
        assertThat(result.details().getFirst()).containsEntry("outcome", "EMPTY");
    }

    @Test
    @DisplayName("a folder that does not exist is reported rather than treated as empty")
    void missingFolderIsReported() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            try {
                return service().importFolder(tenantId, folder.resolve("gibt-es-nicht"), "x", null);
            }
            catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an empty folder is a successful run with nothing in it")
    void emptyFolderIsSuccess() throws Exception {
        UUID tenantId = tenant();

        FolderImportService.ImportResult result = importFolder(tenantId);

        assertThat(result.candidates()).isZero();
        assertThat(result.imported()).isZero();
    }

    @Test
    @DisplayName("the documentation of a document is created with it, because a page belongs to one")
    void versionIsCreatedWithTheDocument() throws Exception {
        UUID tenantId = tenant();
        write("mit-version.txt", "Inhalt");

        importFolder(tenantId);

        Integer versions = jdbc.queryForObject("""
                select count(*) from document_versions v
                join documents d on d.id = v.document_id where d.tenant_id = ?
                """, Integer.class, tenantId);
        assertThat(versions).isEqualTo(1);
    }

    @Test
    @DisplayName("examining a folder reports what is waiting without importing it")
    void examineReportsWithoutImporting() throws Exception {
        UUID tenantId = tenant();
        write("wartet.txt", "Inhalt");

        var examined = RequestContext.runAs(tenantId, () -> {
            try {
                return service().examine(folder);
            }
            catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        });

        assertThat(examined).hasSize(1);
        assertThat(examined.getFirst()).containsEntry("file", "wartet.txt");
        assertThat(documentCount(tenantId))
                .as("looking at a folder is not importing from it")
                .isZero();
    }
}
