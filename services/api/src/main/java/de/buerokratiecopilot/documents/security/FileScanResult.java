package de.buerokratiecopilot.documents.security;

/**
 * Verdict of the file security check (spec §21.2).
 *
 * <p>The distinction matters: "unsupported" means the platform cannot handle the format, "rejected"
 * means the content is dangerous or does not match what it claims to be, and "suspicious" means the
 * file is structurally implausible (for example an archive whose expansion ratio suggests a zip bomb).
 * Only {@link #CLEAN} and {@link #SUSPICIOUS} may reach OCR or an AI provider, and a suspicious file
 * always goes to human review.</p>
 */
public record FileScanResult(Verdict verdict, String detectedContentType, String reason,
                             FileScanDetails details) {

    public enum Verdict {
        /** Structure matches the declared type and nothing dangerous was found. */
        CLEAN,
        /** No scanner component is configured, so the file was not checked. Never treated as clean. */
        SKIPPED,
        /** The bytes do not match the declared or allowed type, or contain executable content. */
        REJECTED,
        /** Structurally implausible (archive expansion ratio, entry count), needs human review. */
        SUSPICIOUS,
        /** Format is recognised but not supported by the platform. */
        UNSUPPORTED
    }

    /** Details kept for diagnostics; never contains the file content itself. */
    public record FileScanDetails(long sizeBytes, String declaredContentType, String filenameExtension,
                                  int archiveEntries, long archiveUncompressedBytes,
                                  double compressionRatio, String scannerName) {
    }

    public boolean isRejected() {
        return verdict == Verdict.REJECTED || verdict == Verdict.UNSUPPORTED;
    }

    public boolean allowsProcessing() {
        return verdict == Verdict.CLEAN || verdict == Verdict.SKIPPED || verdict == Verdict.SUSPICIOUS;
    }

    public boolean requiresReview() {
        return verdict == Verdict.SUSPICIOUS;
    }

    public static FileScanResult clean(String detectedType, FileScanDetails details) {
        return new FileScanResult(Verdict.CLEAN, detectedType, null, details);
    }

    public static FileScanResult skipped(String detectedType, FileScanDetails details) {
        return new FileScanResult(Verdict.SKIPPED, detectedType,
                "No malware scanner is configured in this deployment", details);
    }

    public static FileScanResult rejected(String detectedType, String reason, FileScanDetails details) {
        return new FileScanResult(Verdict.REJECTED, detectedType, reason, details);
    }

    public static FileScanResult unsupported(String detectedType, String reason, FileScanDetails details) {
        return new FileScanResult(Verdict.UNSUPPORTED, detectedType, reason, details);
    }

    public static FileScanResult suspicious(String detectedType, String reason, FileScanDetails details) {
        return new FileScanResult(Verdict.SUSPICIOUS, detectedType, reason, details);
    }
}
