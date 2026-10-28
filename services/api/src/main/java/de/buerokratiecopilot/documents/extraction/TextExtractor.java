package de.buerokratiecopilot.documents.extraction;

import java.io.IOException;
import java.io.InputStream;

/**
 * Extracts text from one file format (spec §6.1, §9.1).
 *
 * <p>One implementation per format keeps the parsers replaceable and lets a failing format be fixed
 * without touching the extraction service. Implementations must never execute or resolve anything
 * referenced by the file: XML parsers run without DTDs and external entities, and archive members
 * are never unpacked to disk (spec §21.2).</p>
 */
public interface TextExtractor {

    /** Whether this extractor handles the given content type. */
    boolean supports(String contentType);

    /** Formats this extractor handles, for diagnostics and documentation. */
    String format();

    /**
     * Extracts the text.
     *
     * @throws IOException when the file cannot be parsed; the caller records the step as failed
     *                     rather than storing a partial result
     */
    ExtractedText extract(InputStream content, long sizeBytes) throws IOException;
}
