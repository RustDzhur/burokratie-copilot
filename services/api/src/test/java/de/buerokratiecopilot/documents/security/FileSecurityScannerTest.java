package de.buerokratiecopilot.documents.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the structural file security check (spec §21.2).
 *
 * <p>The cases are the ones that actually occur in practice: a file whose content contradicts its
 * name, an executable disguised as a document, a decompression bomb and a container that claims to be
 * a Word document but is not. Each is asserted to end in a specific verdict, because "rejected",
 * "unsupported" and "needs review" lead to different follow-up actions.</p>
 */
class FileSecurityScannerTest {

    private final FileSecurityScanner scanner = new FileSecurityScanner();

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R' };
    private static final byte[] JPEG = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16 };
    private static final byte[] ELF = new byte[] { 0x7F, 'E', 'L', 'F', 2, 1, 1, 0 };
    private static final byte[] PE = new byte[] { 'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0 };

    private FileScanResult scan(byte[] content, String filename, String declaredType) throws IOException {
        return scanner.scan(new ByteArrayInputStream(content), content.length, filename, declaredType);
    }

    private static byte[] zipWith(String... entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("x".repeat(32).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** An archive whose declared uncompressed size is enormous compared with its real size. */
    private static byte[] zipBomb() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            // Highly compressible payload: 200 MiB of zeros compresses to a few hundred kilobytes.
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 200; i++) {
                zip.putNextEntry(new ZipEntry("bomb-" + i + ".bin"));
                zip.write(zeros);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    @DisplayName("a genuine PDF is accepted")
    void acceptsRealPdf() throws IOException {
        FileScanResult result = scan(PDF, "Rechnung.pdf", "application/pdf");

        assertThat(result.verdict()).isEqualTo(FileScanResult.Verdict.CLEAN);
        assertThat(result.detectedContentType()).isEqualTo("application/pdf");
        assertThat(result.allowsProcessing()).isTrue();
    }

    @Test
    @DisplayName("images are recognised by their content, not by their extension")
    void recognisesImages() throws IOException {
        assertThat(scan(PNG, "scan.png", "image/png").detectedContentType()).isEqualTo("image/png");
        assertThat(scan(JPEG, "foto.jpg", "image/jpeg").detectedContentType()).isEqualTo("image/jpeg");
        assertThat(scan(PNG, "scan.png", "image/png").verdict()).isEqualTo(FileScanResult.Verdict.CLEAN);
    }

    @Test
    @DisplayName("a script renamed to .pdf is rejected")
    void rejectsContentTypeMismatch() throws IOException {
        byte[] script = "#!/bin/sh\nrm -rf /\n".getBytes(StandardCharsets.UTF_8);

        FileScanResult result = scan(script, "harmlos.pdf", "application/pdf");

        assertThat(result.verdict()).isEqualTo(FileScanResult.Verdict.REJECTED);
        assertThat(result.reason()).contains("Executable");
    }

    @Test
    @DisplayName("native binaries are rejected whatever they are called")
    void rejectsNativeBinaries() throws IOException {
        assertThat(scan(ELF, "dokument.pdf", "application/pdf").verdict())
                .isEqualTo(FileScanResult.Verdict.REJECTED);
        assertThat(scan(PE, "rechnung.pdf", "application/pdf").verdict())
                .isEqualTo(FileScanResult.Verdict.REJECTED);
    }

    @Test
    @DisplayName("a PNG that claims to be a PDF is rejected")
    void rejectsImageDeclaredAsPdf() throws IOException {
        FileScanResult result = scan(PNG, "rechnung.pdf", "application/pdf");

        assertThat(result.verdict()).isEqualTo(FileScanResult.Verdict.REJECTED);
        assertThat(result.reason()).contains("does not match the declared type");
    }

    @Test
    @DisplayName("a decompression bomb is not processed automatically")
    void detectsZipBomb() throws IOException {
        FileScanResult result = scan(zipBomb(), "unterlagen.zip", "application/zip");

        // The generic ZIP type is not on the allow list of the platform, so it is unsupported first;
        // what matters is that it never reaches processing as a normal document.
        assertThat(result.allowsProcessing()).isFalse();

        // Declared as a Word document, the bomb is caught by the archive inspection itself.
        FileScanResult asDocx = scan(zipBomb(), "unterlagen.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(asDocx.verdict()).isEqualTo(FileScanResult.Verdict.SUSPICIOUS);
        assertThat(asDocx.requiresReview()).isTrue();
        assertThat(asDocx.reason()).contains("decompression bomb");
    }

    @Test
    @DisplayName("a ZIP that is not a Word document is rejected when it claims to be one")
    void rejectsFakeDocx() throws IOException {
        byte[] archive = zipWith("random.bin", "other/file.txt");

        FileScanResult result = scan(archive, "vertrag.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        assertThat(result.verdict()).isEqualTo(FileScanResult.Verdict.REJECTED);
        assertThat(result.reason()).contains("does not contain the structure");
    }

    @Test
    @DisplayName("a real Word document is accepted")
    void acceptsRealDocx() throws IOException {
        byte[] docx = zipWith("[Content_Types].xml", "word/document.xml", "_rels/.rels");

        FileScanResult result = scan(docx, "vertrag.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        assertThat(result.verdict()).isEqualTo(FileScanResult.Verdict.CLEAN);
    }

    @Test
    @DisplayName("plain text and e-mail files are recognised")
    void recognisesTextAndMail() throws IOException {
        byte[] text = "Sehr geehrte Damen und Herren,\n\nanbei die Unterlagen.\n".getBytes(StandardCharsets.UTF_8);
        assertThat(scan(text, "notiz.txt", "text/plain").verdict()).isEqualTo(FileScanResult.Verdict.CLEAN);

        byte[] eml = """
                From: behoerde@example.de
                To: ich@example.de
                Subject: Anforderung
                MIME-Version: 1.0

                Bitte reichen Sie die Unterlagen nach.
                """.getBytes(StandardCharsets.UTF_8);
        assertThat(scan(eml, "mail.eml", "message/rfc822").detectedContentType()).isEqualTo("message/rfc822");
    }

    @Test
    @DisplayName("an unrecognised binary format is reported as unsupported, not as clean")
    void unknownFormatIsUnsupported() throws IOException {
        byte[] random = new byte[] { 0x13, 0x37, (byte) 0xAB, (byte) 0xCD, 0, 1, 2, 3, 4, 5 };

        FileScanResult result = scan(random, "datei.bin", "application/octet-stream");

        assertThat(result.verdict()).isEqualTo(FileScanResult.Verdict.UNSUPPORTED);
        assertThat(result.allowsProcessing()).isFalse();
    }

    @Test
    @DisplayName("a declared type outside the allow list is refused")
    void unsupportedDeclaredTypeIsRefused() throws IOException {
        FileScanResult result = scan(PDF, "rechnung.pdf", "application/x-msdownload");

        assertThat(result.verdict()).isEqualTo(FileScanResult.Verdict.UNSUPPORTED);
        assertThat(result.reason()).contains("not supported");
    }

    @Test
    @DisplayName("the verdict never claims a malware check that did not happen")
    void doesNotClaimMalwareScanning() throws IOException {
        FileScanResult result = scan(PDF, "rechnung.pdf", "application/pdf");

        assertThat(result.verdict()).isEqualTo(FileScanResult.Verdict.CLEAN);
        // CLEAN describes the structural check only; the caller records "not malware checked".
        assertThat(result.details().scannerName()).isEqualTo("structural");
    }
}
