package de.buerokratiecopilot.invoices.outgoing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.invoices.outgoing.InvoicePdfRenderer.RenderLine;

/** HD-TEST-02: the PDF of an outgoing invoice prints what the record says, marks a cancelled invoice, and never fails on a character. */
class InvoicePdfRendererTest {

    private final InvoicePdfRenderer renderer = new InvoicePdfRenderer();

    private static Invoice invoice(String number) {
        Invoice invoice = new Invoice(UUID.randomUUID(), UUID.randomUUID(), number);
        invoice.setCurrency("EUR");
        invoice.setIssueDate(LocalDate.of(2026, 10, 1));
        invoice.setDueDate(LocalDate.of(2026, 10, 31));
        invoice.setNetAmount(new BigDecimal("1000.00"));
        invoice.setTaxAmount(new BigDecimal("190.00"));
        invoice.setGrossAmount(new BigDecimal("1190.00"));
        return invoice;
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static RenderLine line(String description, String net) {
        return new RenderLine(1, description, new BigDecimal("2.0"), "Std", new BigDecimal("500.00"), new BigDecimal(net), new BigDecimal("19"));
    }

    @Test
    @DisplayName("number, parties, dates, lines and totals are printed exactly as the record has them, in German number format")
    void printsTheRecord() throws IOException {
        Invoice invoice = invoice("RE-2026-0042");
        invoice.setIban("DE89370400440532013000");
        invoice.setPaymentReference("RE-2026-0042");

        byte[] pdf = renderer.render(new InvoicePdfRenderer.Document(invoice, List.of(line("Beratung Müller", "1000.00")), "Muster GmbH", "Kunde AG"));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        String text = text(pdf);
        assertThat(text).contains("Rechnung RE-2026-0042").contains("Von: Muster GmbH").contains("An: Kunde AG")
                .contains("Datum: 01.10.2026").contains("Fällig: 31.10.2026").contains("Beratung Müller")
                .contains("Netto: 1.000,00 EUR").contains("Umsatzsteuer: 190,00 EUR").contains("Gesamt: 1.190,00 EUR")
                .contains("Bankverbindung: DE89370400440532013000").contains("Verwendungszweck: RE-2026-0042");
        assertThat(text).doesNotContain("Bereits gezahlt").doesNotContain("storniert");
    }

    @Test
    @DisplayName("a cancelled invoice is marked as cancelled, not left out; a part payment shows what is still open")
    void cancelledAndPartPaid() throws IOException {
        Invoice cancelled = invoice("RE-1");
        cancelled.setStatus(Invoice.Status.CANCELLED);
        assertThat(text(renderer.render(cancelled, List.of()))).contains("storniert");

        Invoice partPaid = invoice("RE-2");
        partPaid.setPaidAmount(new BigDecimal("400.00"));
        assertThat(text(renderer.render(partPaid, List.of()))).contains("Bereits gezahlt: 400,00 EUR").contains("Offener Betrag: 790,00 EUR");
    }

    @Test
    @DisplayName("the renderer computes nothing: a total that does not add up is printed as stored")
    void doesNotRecalculate() throws IOException {
        Invoice odd = invoice("RE-3");
        odd.setGrossAmount(new BigDecimal("999.99"));
        assertThat(text(renderer.render(odd, List.of(line("Position", "1000.00"))))).contains("Gesamt: 999,99 EUR");
    }

    @Test
    @DisplayName("characters the standard font cannot print do not break the document; an unknown amount is not printed as a question mark")
    void unprintableCharacters() throws IOException {
        Invoice unknownTotal = invoice("RE-4");
        unknownTotal.setGrossAmount(null);
        byte[] pdf = renderer.render(unknownTotal, List.of(line("Preis 5 € für 日本語\nzweite Zeile – Ende…", "10.00")));

        String text = text(pdf);
        assertThat(text).contains("Gesamt: -");
        assertThat(text).doesNotContain("Gesamt: ?");
        assertThat(text).contains("zweite Zeile - Ende");
        assertThat(text).contains("€");
    }

    @Test
    @DisplayName("an invoice with more lines than fit on the page still renders")
    void manyLines() throws IOException {
        List<RenderLine> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) {
            lines.add(line("Position " + i, "10.00"));
        }
        assertThat(text(renderer.render(invoice("RE-5"), lines))).contains("Position 0").contains("Gesamt: 1.190,00 EUR");
    }
}
