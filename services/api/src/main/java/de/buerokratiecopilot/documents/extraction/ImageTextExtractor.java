package de.buerokratiecopilot.documents.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * A photograph or a scan saved as a picture has no text layer: it is one page without text, and the next step is OCR.
 *
 * <p>Before this extractor existed a picture was reported as "no extractor for image/jpeg", the document went straight to
 * review with no text, and nothing could be classified, read or searched. The picture is not parsed here (the OCR engine reads
 * it); this class only says what the file is, so the pipeline takes the route "page without text".</p>
 */
@Component
public class ImageTextExtractor implements TextExtractor {

    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "image/tiff", "image/webp", "image/bmp", "image/gif");

    @Override
    public boolean supports(String contentType) {
        return contentType != null && TYPES.contains(contentType.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public String format() {
        return "image";
    }

    @Override
    public ExtractedText extract(InputStream content, long sizeBytes) throws IOException {
        return ExtractedText.of(List.of(new ExtractedText.Page(1, "", null)), ExtractedText.Method.TEXT_LAYER,
                Map.of("image", true));
    }
}
