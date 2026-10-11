package de.buerokratiecopilot.documents.extraction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Reads plain text and CSV (spec §6.1).
 *
 * <p>German documents arrive in Windows-1252 as often as in UTF-8, and a wrongly decoded umlaut
 * turns a supplier name into something that will not match any contact. The encoding is therefore
 * detected from the byte order mark first, with a strict UTF-8 attempt as the fallback and
 * Windows-1252 as the last resort, which is the order that keeps the most files readable.</p>
 */
@Component
public class PlainTextExtractor implements TextExtractor {

    /** Lines per section: a text file has no pages, so long files are split for readability. */
    private static final int LINES_PER_SECTION = 500;

    @Override
    public boolean supports(String contentType) {
        return "text/plain".equals(contentType) || "text/csv".equals(contentType);
    }

    @Override
    public String format() {
        return "plain-text";
    }

    @Override
    public ExtractedText extract(InputStream content, long sizeBytes) throws IOException {
        byte[] bytes = content.readAllBytes();
        Charset charset = detectCharset(bytes);
        String text = new String(bytes, charset);

        List<ExtractedText.Page> pages = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new java.io.StringReader(text))) {
            String line;
            int section = 1;
            int lineInSection = 0;
            StringBuilder buffer = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                buffer.append(line).append('\n');
                if (++lineInSection >= LINES_PER_SECTION) {
                    pages.add(new ExtractedText.Page(section++, buffer.toString().strip(), null));
                    buffer.setLength(0);
                    lineInSection = 0;
                }
            }
            if (!buffer.isEmpty()) {
                pages.add(new ExtractedText.Page(section, buffer.toString().strip(), null));
            }
        }

        return ExtractedText.of(pages, ExtractedText.Method.PLAIN_TEXT, Map.of(
                "charset", charset.name(),
                "sizeBytes", sizeBytes,
                "sections", pages.size()));
    }

    /** BOM first, strict UTF-8 second, Windows-1252 last. */
    static Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        }
        catch (java.nio.charset.CharacterCodingException notUtf8) {
            return Charset.forName("windows-1252");
        }
    }
}
