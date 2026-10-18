package de.buerokratiecopilot.documents.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Extracts the text layer of a PDF (spec §6.1, §9.1 step 5).
 *
 * <p>A PDF is read page by page on purpose: pages without a text layer are the ones that need OCR,
 * and reporting "the document has text" would hide exactly the pages a human must look at. The whole
 * file is loaded through PDFBox, which is bounded by the upload limit enforced before storage.</p>
 */
@Component
public class PdfTextExtractor implements TextExtractor {

    @Override
    public boolean supports(String contentType) {
        return "application/pdf".equals(contentType);
    }

    @Override
    public String format() {
        return "pdf";
    }

    @Override
    public ExtractedText extract(InputStream content, long sizeBytes) throws IOException {
        byte[] bytes = content.readAllBytes();
        PDDocument opened;
        try {
            opened = Loader.loadPDF(bytes);
        }
        catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException ex) {
            // A protected invoice is a normal thing to receive and an impossible thing to read. Without this
            // the failure surfaced as an unreadable stack trace and the document sat in quarantine for ever
            // with nobody told why; the message now says what to do about it.
            throw new IOException("Das PDF ist passwortgeschützt und kann nicht gelesen werden. "
                    + "Bitte laden Sie es ohne Passwort hoch oder entfernen Sie den Schutz.", ex);
        }
        try (PDDocument document = opened) {
            int pageCount = document.getNumberOfPages();
            List<ExtractedText.Page> pages = new ArrayList<>(pageCount);
            int encrypted = document.isEncrypted() ? 1 : 0;

            for (int page = 1; page <= pageCount; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                pages.add(new ExtractedText.Page(page, text == null ? "" : text.strip(), null));
            }

            ExtractedText result = ExtractedText.of(pages, ExtractedText.Method.TEXT_LAYER, Map.of(
                    "pageCount", pageCount,
                    "sizeBytes", sizeBytes,
                    "encrypted", encrypted == 1));
            return result;
        }
    }
}
