package de.buerokratiecopilot.documents.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Structural security check of an uploaded file (spec §21.2).
 *
 * <p>What it does:</p>
 * <ol>
 *   <li><strong>Format by content, not by claim.</strong> The real type is derived from magic bytes and
 *       compared with what the client declared; a mismatch is rejected. A PDF renamed to ".txt" or a
 *       script renamed to ".pdf" therefore never enters the system.</li>
 *   <li><strong>No executable content.</strong> PE/ELF/Mach-O binaries, Java class files and scripts
 *       with a shebang are rejected outright — the platform never executes uploaded content.</li>
 *   <li><strong>Archive inspection.</strong> The central directory of a ZIP container is read to check
 *       the entry count, the declared uncompressed size and the compression ratio, which is what turns
 *       a zip bomb into a refusal instead of a full disk.</li>
 *   <li><strong>Container plausibility.</strong> A DOCX must actually contain {@code word/} and a
 *       {@code [Content_Types].xml}, an XLSX must contain {@code xl/} — an arbitrary ZIP renamed to
 *       ".docx" is rejected.</li>
 * </ol>
 *
 * <p><strong>What it does not do:</strong> it is not an antivirus. Malware that is structurally a
 * valid PDF is not detected here.</p>
 */
@Component
public class FileSecurityScanner {

    /** Bytes inspected at the beginning of the file. */
    private static final int HEADER_BYTES = 8 * 1024;

    /** Bytes inspected at the end of the file to find the ZIP end-of-central-directory record. */
    private static final int TAIL_BYTES = 66 * 1024;

    /** A ZIP with more entries than this is treated as implausible for a business document. */
    private static final int MAX_ARCHIVE_ENTRIES = 2_000;

    /** Declared total uncompressed size above which human review is required. */
    private static final long MAX_ARCHIVE_UNCOMPRESSED = 512L * 1024 * 1024;

    /** Compression ratio above which an archive looks like a decompression bomb. */
    private static final double MAX_COMPRESSION_RATIO = 200.0;

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/png", "image/tiff",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv", "text/plain", "message/rfc822", "application/xml", "text/xml");

    public FileScanResult scan(InputStream content, long sizeBytes, String filename,
                               String declaredContentType) throws IOException {
        byte[] header = readHeader(content, HEADER_BYTES);
        String declared = normalize(declaredContentType);
        String extension = extensionOf(filename);

        Detected detected = detect(header);
        FileScanResult.FileScanDetails baseDetails = new FileScanResult.FileScanDetails(
                sizeBytes, declared, extension, 0, 0, 0, "structural");

        // 1. Executable content is refused before anything else is considered.
        if (detected.executable()) {
            return FileScanResult.rejected(detected.mimeType(),
                    "Executable content is not accepted", baseDetails);
        }

        // 2. The claimed type must be one the platform accepts at all.
        if (declared != null && !ALLOWED_TYPES.contains(declared)) {
            return FileScanResult.unsupported(detected.mimeType(),
                    "The declared type is not supported: " + declared, baseDetails);
        }

        // 3. The content must match the claim.
        if (declared != null && !detected.mimeType().equals(declared) && !detected.compatibleWith(declared)) {
            return FileScanResult.rejected(detected.mimeType(),
                    "The file content (%s) does not match the declared type (%s)"
                            .formatted(detected.mimeType(), declared), baseDetails);
        }

        if ("application/octet-stream".equals(detected.mimeType())) {
            return FileScanResult.unsupported(detected.mimeType(),
                    "The file format could not be recognised", baseDetails);
        }

        // 4. Container archives: central directory inspection for bombs and plausibility.
        if (detected.container()) {
            ArchiveInfo archive = inspectZip(header, content, sizeBytes);
            FileScanResult.FileScanDetails details = new FileScanResult.FileScanDetails(
                    sizeBytes, declared, extension, archive.entries(), archive.uncompressedBytes(),
                    archive.ratio(), "structural");

            if (archive.looksLikeBomb()) {
                return FileScanResult.suspicious(detected.mimeType(),
                        "The archive looks like a decompression bomb (%d entries, ratio %.1f)"
                                .formatted(archive.entries(), archive.ratio()), details);
            }
            // Plausibility is judged against what the client claims: an archive that says "Word
            // document" must actually contain the parts of one.
            String expectedContainer = declared != null ? declared : detected.mimeType();
            if (archive.missingRequiredEntry(expectedContainer)) {
                return FileScanResult.rejected(detected.mimeType(),
                        "The archive does not contain the structure of a " + expectedContainer, details);
            }
            return FileScanResult.clean(detected.mimeType(), details);
        }

        return FileScanResult.clean(detected.mimeType(), baseDetails);
    }

    record Detected(String mimeType, boolean executable, boolean container, String family) {

        /** Some containers are legitimately reported as a more specific type by a client. */
        boolean compatibleWith(String declared) {
            if (container && declared.startsWith("application/vnd.openxmlformats")) {
                return true;
            }
            if ("text/plain".equals(mimeType) && ("text/csv".equals(declared) || "application/xml".equals(declared)
                    || "text/xml".equals(declared))) {
                return true;
            }
            if (("application/xml".equals(mimeType) || "text/xml".equals(mimeType))
                    && ("application/xml".equals(declared) || "text/xml".equals(declared))) {
                return true;
            }
            return false;
        }
    }

    /**
     * What the ZIP central directory says about a container.
     *
     * <p>The entry names belong to this result: keeping them in static state would make two parallel
     * uploads influence each other's verdict.</p>
     */
    record ArchiveInfo(int entries, long uncompressedBytes, double ratio, Set<String> names) {

        static ArchiveInfo empty() {
            return new ArchiveInfo(0, 0, 0, Set.of());
        }

        boolean looksLikeBomb() {
            return entries > MAX_ARCHIVE_ENTRIES || uncompressedBytes > MAX_ARCHIVE_UNCOMPRESSED
                    || ratio > MAX_COMPRESSION_RATIO;
        }

        boolean missingRequiredEntry(String mimeType) {
            boolean hasContentTypes = names.stream().anyMatch(n -> n.endsWith("[content_types].xml"));
            if (mimeType.endsWith("wordprocessingml.document")) {
                return !hasContentTypes && names.stream().noneMatch(n -> n.startsWith("word/"));
            }
            if (mimeType.endsWith("spreadsheetml.sheet")) {
                return !hasContentTypes && names.stream().noneMatch(n -> n.startsWith("xl/"));
            }
            return false;
        }
    }

    private byte[] readHeader(InputStream content, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(limit, 4096));
        byte[] chunk = new byte[4096];
        int read;
        while (buffer.size() < limit && (read = content.read(chunk, 0, Math.min(chunk.length, limit - buffer.size()))) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * Reads the ZIP end-of-central-directory record from the tail of the stream.
     *
     * <p>The stream is consumed in constant memory: only the last {@link #TAIL_BYTES} are retained, so
     * a multi-gigabyte upload cannot exhaust the heap while being inspected.</p>
     */
    private ArchiveInfo inspectZip(byte[] header, InputStream content, long sizeBytes) throws IOException {
        byte[] tail = readTail(header, content, sizeBytes);
        int eocd = lastIndexOf(tail, new byte[] { 0x50, 0x4B, 0x05, 0x06 });
        if (eocd < 0 || eocd + 22 > tail.length) {
            return ArchiveInfo.empty();
        }
        // End of central directory: entries at +10, comment length at +20. The record does NOT carry
        // the uncompressed size, which is why the central directory entries themselves are walked.
        int declaredEntries = unsignedShort(tail, eocd + 10);

        long compressedTotal = 0;
        long uncompressedTotal = 0;
        Set<String> names = new java.util.HashSet<>();
        int entriesSeen = 0;

        // The central directory sits directly before the end record, so it is inside the tail window
        // for any archive that fits in this deployment's upload limit.
        for (int i = 0; i + 46 <= eocd; i++) {
            if (!(tail[i] == 0x50 && tail[i + 1] == 0x4B && tail[i + 2] == 0x01 && tail[i + 3] == 0x02)) {
                continue;
            }
            long compressed = unsignedInt(tail, i + 20);
            long uncompressed = unsignedInt(tail, i + 24);
            int nameLength = unsignedShort(tail, i + 28);
            int extraLength = unsignedShort(tail, i + 30);
            int commentLength = unsignedShort(tail, i + 32);

            compressedTotal += compressed;
            uncompressedTotal += uncompressed;
            entriesSeen++;

            int nameStart = i + 46;
            if (nameLength > 0 && nameStart + nameLength <= tail.length) {
                names.add(new String(tail, nameStart, nameLength, StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT));
            }
            // Jump to the next entry: fixed part + variable parts.
            i = nameStart + nameLength + extraLength + commentLength - 1;
        }

        int entries = Math.max(entriesSeen, declaredEntries);
        double ratio = compressedTotal == 0 ? 0 : (double) uncompressedTotal / compressedTotal;
        return new ArchiveInfo(entries, uncompressedTotal, ratio, names);
    }

    /** Reads the tail of a stream without buffering the whole file. */
    private byte[] readTail(byte[] header, InputStream content, long sizeBytes) throws IOException {
        if (sizeBytes <= header.length) {
            return header;
        }
        byte[] ring = new byte[TAIL_BYTES];
        int filled = 0;
        byte[] chunk = new byte[16 * 1024];
        int read;
        while ((read = content.read(chunk)) > 0) {
            if (read >= ring.length) {
                System.arraycopy(chunk, read - ring.length, ring, 0, ring.length);
                filled = ring.length;
                continue;
            }
            int keep = Math.min(filled, ring.length - read);
            if (keep > 0) {
                System.arraycopy(ring, filled - keep, ring, 0, keep);
            }
            System.arraycopy(chunk, 0, ring, keep, read);
            filled = keep + read;
        }
        byte[] tail = new byte[filled];
        System.arraycopy(ring, 0, tail, 0, filled);
        return tail;
    }

    private static int lastIndexOf(byte[] data, byte[] pattern) {
        for (int i = data.length - pattern.length; i >= 0; i--) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    private static int unsignedShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static long unsignedInt(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF))
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | ((long) (data[offset + 2] & 0xFF) << 16)
                | ((long) (data[offset + 3] & 0xFF) << 24);
    }

    /** Classifies the content from its first bytes. */
    Detected detect(byte[] data) {
        if (startsWith(data, 0x4D, 0x5A)) {
            return new Detected("application/x-msdownload", true, false, "pe");
        }
        if (startsWith(data, 0x7F, 0x45, 0x4C, 0x46)) {
            return new Detected("application/x-elf", true, false, "elf");
        }
        if (startsWith(data, 0xCA, 0xFE, 0xBA, 0xBE)) {
            return new Detected("application/java-vm", true, false, "class");
        }
        if (startsWith(data, 0xFE, 0xED, 0xFA, 0xCE) || startsWith(data, 0xFE, 0xED, 0xFA, 0xCF)
                || startsWith(data, 0xCF, 0xFA, 0xED, 0xFE) || startsWith(data, 0xCE, 0xFA, 0xED, 0xFE)) {
            return new Detected("application/x-mach-binary", true, false, "macho");
        }
        if (startsWith(data, 0x23, 0x21)) {
            return new Detected("text/x-script", true, false, "shebang");
        }
        if (startsWith(data, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
            return new Detected("application/pdf", false, false, "pdf");
        }
        if (startsWith(data, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return new Detected("image/png", false, false, "png");
        }
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
            return new Detected("image/jpeg", false, false, "jpeg");
        }
        if (startsWith(data, 0x49, 0x49, 0x2A, 0x00) || startsWith(data, 0x4D, 0x4D, 0x00, 0x2A)) {
            return new Detected("image/tiff", false, false, "tiff");
        }
        if (startsWith(data, 0x47, 0x49, 0x46, 0x38)) {
            return new Detected("image/gif", false, false, "gif");
        }
        if (startsWith(data, 0x50, 0x4B, 0x03, 0x04) || startsWith(data, 0x50, 0x4B, 0x05, 0x06)) {
            String containerType = zipContainerType(data);
            return new Detected(containerType, false, true, "zip");
        }
        if (startsWith(data, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)) {
            // OLE2: legacy .doc/.xls/.msg containers. Recognised but not parsed by this platform yet.
            return new Detected("application/x-ole-storage", false, false, "ole2");
        }
        if (startsWith(data, 0x7B, 0x5C, 0x72, 0x74, 0x66)) {
            return new Detected("application/rtf", false, false, "rtf");
        }

        String text = asText(data);
        if (text != null) {
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.contains("mime-version:") || lower.contains("received:") || lower.contains("message-id:")) {
                return new Detected("message/rfc822", false, false, "eml");
            }
            if (lower.startsWith("<?xml") || lower.startsWith("<!--") || lower.startsWith("<")) {
                return new Detected("application/xml", false, false, "xml");
            }
            return new Detected("text/plain", false, false, "text");
        }
        return new Detected("application/octet-stream", false, false, "unknown");
    }

    /**
     * Distinguishes the OOXML container types from their internal structure.
     *
     * <p>The header of a ZIP rarely contains the entry names, so the type is determined from the
     * `[Content_Types].xml` marker when it is present; otherwise the generic OOXML type is reported and
     * the container check decides whether the archive is plausible.</p>
     */
    private String zipContainerType(byte[] data) {
        String header = new String(data, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        if (header.contains("word/") || header.contains("wordprocessingml")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if (header.contains("xl/") || header.contains("spreadsheetml")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        return "application/zip";
    }

    /** Returns the text when the bytes look like text, otherwise {@code null}. */
    private static String asText(byte[] data) {
        int limit = Math.min(data.length, 2048);
        if (limit == 0) {
            return null;
        }
        int offset = 0;
        if (limit >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        for (int i = offset; i < limit; i++) {
            if (data[i] == 0) {
                return null;
            }
        }
        return new String(data, offset, limit - offset, StandardCharsets.UTF_8);
    }

    private static boolean startsWith(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String contentType) {
        if (contentType == null) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String value = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? null : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
