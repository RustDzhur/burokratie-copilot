package de.buerokratiecopilot.invoices.outgoing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.invoices.domain.Invoice;

/**
 * Renders an invoice as a PDF (spec §14, D-006).
 *
 * <p>The renderer prints what the record says and computes nothing. Amounts, dates and the number come
 * from the invoice; a renderer that recalculated a total would be a second implementation of arithmetic
 * that already exists, and the two would eventually disagree — on the document a customer receives.</p>
 *
 * <p>The wording is deliberately plain: the platform knows what is on the invoice and does not know
 * which tax notices a given business must carry, so it does not invent any. What it does print is the
 * invoice number, the date, the parties, the lines and the totals, and it marks a cancelled invoice as
 * cancelled rather than omitting it.</p>
 */
@Component
public class InvoicePdfRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);
    private static final float MARGIN = 50f;
    private static final float LEADING = 16f;

    /** One line to print, taken from the stored invoice line. */
    public record RenderLine(int position, String description, BigDecimal quantity, String unit,
                             BigDecimal unitPrice, BigDecimal netAmount, BigDecimal taxRatePercent) {
    }

    /** Everything the document needs, all of it read from the record. */
    public record Document(Invoice invoice, List<RenderLine> lines, String sellerName,
                           String buyerName) {
    }

    public byte[] render(Document document) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);

            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                float y = page.getMediaBox().getHeight() - MARGIN;
                Invoice invoice = document.invoice();

                y = write(content, bold, 16, MARGIN, y, "Rechnung " + invoice.getInvoiceNumber());
                y -= LEADING;

                if (invoice.getStatus() == Invoice.Status.CANCELLED) {
                    // A cancelled invoice is marked, not hidden: a customer holding both versions has to
                    // be able to tell which one counts.
                    y = write(content, bold, 12, MARGIN, y, "STORNOVermerk: Diese Rechnung ist storniert");
                    y -= LEADING;
                }

                if (document.sellerName() != null) {
                    y = write(content, regular, 10, MARGIN, y, "Von: " + document.sellerName());
                    y -= LEADING;
                }
                if (document.buyerName() != null) {
                    y = write(content, regular, 10, MARGIN, y, "An: " + document.buyerName());
                    y -= LEADING;
                }

                StringBuilder header = new StringBuilder();
                if (invoice.getIssueDate() != null) {
                    header.append("Datum: ").append(DATE.format(invoice.getIssueDate())).append("   ");
                }
                if (invoice.getDueDate() != null) {
                    header.append("Fällig: ").append(DATE.format(invoice.getDueDate())).append("   ");
                }
                header.append("Währung: ").append(invoice.getCurrency());
                y = write(content, regular, 10, MARGIN, y, header.toString());
                y -= LEADING * 1.5f;

                y = write(content, bold, 10, MARGIN, y, "Pos  Beschreibung");
                content.moveTo(MARGIN + 300, y + 4);
                content.lineTo(MARGIN + 300, y - 2);
                content.stroke();
                write(content, bold, 10, MARGIN + 310, y, "Menge");
                write(content, bold, 10, MARGIN + 380, y, "Netto");
                y -= LEADING;

                for (RenderLine line : document.lines()) {
                    if (y < MARGIN + 120) {
                        break;
                    }
                    write(content, regular, 10, MARGIN, y, truncate(line.description(), 42));
                    write(content, regular, 10, MARGIN + 310, y, quantity(line.quantity(), line.unit()));
                    write(content, regular, 10, MARGIN + 380, y, money(line.netAmount(),
                            invoice.getCurrency()));
                    y -= LEADING;
                }

                y -= LEADING;
                y = write(content, regular, 10, MARGIN + 300, y, "Netto: "
                        + money(invoice.getNetAmount(), invoice.getCurrency()));
                y -= LEADING;
                if (invoice.getTaxAmount() != null) {
                    y = write(content, regular, 10, MARGIN + 300, y, "Umsatzsteuer: "
                            + money(invoice.getTaxAmount(), invoice.getCurrency()));
                    y -= LEADING;
                }
                y = write(content, bold, 11, MARGIN + 300, y, "Gesamt: "
                        + money(invoice.getGrossAmount(), invoice.getCurrency()));
                y -= LEADING * 1.5f;

                if (invoice.getPaidAmount() != null && invoice.getPaidAmount().signum() > 0) {
                    y = write(content, regular, 10, MARGIN, y, "Bereits gezahlt: "
                            + money(invoice.getPaidAmount(), invoice.getCurrency()));
                    y -= LEADING;
                    BigDecimal outstanding = (invoice.getGrossAmount() == null ? BigDecimal.ZERO
                            : invoice.getGrossAmount()).subtract(invoice.getPaidAmount());
                    y = write(content, bold, 10, MARGIN, y, "Offener Betrag: "
                            + money(outstanding, invoice.getCurrency()));
                    y -= LEADING;
                }

                if (invoice.getIban() != null) {
                    y = write(content, regular, 9, MARGIN, y, "Bankverbindung: " + invoice.getIban());
                    y -= LEADING;
                }
                if (invoice.getPaymentReference() != null) {
                    write(content, regular, 9, MARGIN, y,
                            "Verwendungszweck: " + invoice.getPaymentReference());
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    /** Convenience for a caller that has only an invoice and its lines. */
    public byte[] render(Invoice invoice, List<RenderLine> lines) throws IOException {
        return render(new Document(invoice, lines, null, null));
    }

    private static float write(PDPageContentStream content, PDFont font, float size, float x, float y,
                               String text) throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(sanitise(text));
        content.endText();
        return y;
    }

    /**
     * Removes what the standard font cannot print.
     *
     * <p>The built-in Helvetica is WinAnsi encoded, so a character outside it would throw while writing
     * the document a customer is waiting for. Replacing such a character is visible in the output, which
     * is the honest outcome; an embedded Unicode font is a later improvement.</p>
     */
    private static String sanitise(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(text.length());
        for (char character : text.toCharArray()) {
            if (character == '\n' || character == '\r') {
                cleaned.append(' ');
            }
            else if (character < 256) {
                cleaned.append(character);
            }
            else {
                cleaned.append('?');
            }
        }
        return cleaned.toString();
    }

    private static String truncate(String text, int length) {
        if (text == null) {
            return "";
        }
        return text.length() <= length ? text : text.substring(0, length - 1) + "…";
    }

    private static String money(BigDecimal amount, String currency) {
        if (amount == null) {
            return "—";
        }
        NumberFormat format = NumberFormat.getNumberInstance(Locale.GERMANY);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(amount) + " " + currency;
    }

    private static String quantity(BigDecimal quantity, String unit) {
        if (quantity == null) {
            return "";
        }
        String rendered = quantity.stripTrailingZeros().toPlainString();
        return unit == null || unit.isBlank() ? rendered : rendered + " " + unit;
    }

    /** The text the renderer would print for an invoice, for tests and previews. */
    public List<String> textLines(Document document) {
        List<String> text = new ArrayList<>();
        text.add("Rechnung " + document.invoice().getInvoiceNumber());
        if (document.invoice().getStatus() == Invoice.Status.CANCELLED) {
            text.add("STORNOVermerk");
        }
        for (RenderLine line : document.lines()) {
            text.add(line.description() + " " + money(line.netAmount(), document.invoice().getCurrency()));
        }
        text.add("Gesamt: " + money(document.invoice().getGrossAmount(), document.invoice().getCurrency()));
        return text;
    }
}
