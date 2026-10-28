package de.buerokratiecopilot.documents.ocr;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * Renders PDF pages to images so an OCR engine can read them (spec §9.1 step 6).
 *
 * <p>300 dpi is the usual minimum for reliable recognition of small print such as an invoice number;
 * lower resolutions lose exactly the digits the platform is looking for. Rendering is done one page
 * at a time, so a 400 page scan does not become a 400 image allocation.</p>
 */
@Component
public class PdfPageRenderer {

    /** Recognising a scan reliably needs more than screen resolution. */
    private static final float DPI = 300f;

    /**
     * Renders one page.
     *
     * @param pageNumber 1-based page number as it appears in the document
     * @return PNG bytes of the rendered page
     */
    public byte[] renderPage(byte[] pdf, int pageNumber) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) {
                throw new IOException("The document has no page " + pageNumber);
            }
            PDFRenderer renderer = new PDFRenderer(document);
            // Rendering is not thread safe per document, and this call is per page by design.
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, DPI, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }
    }

    public int pageCount(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }
}
