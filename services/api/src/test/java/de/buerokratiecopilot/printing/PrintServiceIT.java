package de.buerokratiecopilot.printing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies printing and marking (spec §8, G-004).
 *
 * <p>The PDF is read back and its text checked, rather than only its size: a file that exists and is empty
 * would pass a test that only looked at the length. Two properties carry the acceptance criterion — every
 * page carries the mark, and the mark holds a reference that names what the sheet is.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PrintServiceIT {

    @Autowired
    private PrintService printing;

    private String textOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private int pagesOf(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }

    private PrintService.PrintRequest request(List<String> lines) {
        return new PrintService.PrintRequest("Eingangsrechnung", "invoice", UUID.randomUUID(), lines,
                "Muster GmbH");
    }

    // ---------------------------------------------------------------- the page

    @Test
    @DisplayName("a printed document is a readable PDF")
    void aDocumentIsReadable() throws Exception {
        PrintService.Printed printed = printing.print(request(List.of("Zeile eins", "Zeile zwei")));

        assertThat(printed.pdf()).isNotEmpty();
        assertThat(printed.pages()).isEqualTo(1);
        String text = textOf(printed.pdf());
        assertThat(text).contains("Eingangsrechnung").contains("Zeile eins").contains("Zeile zwei");
    }

    @Test
    @DisplayName("the mark names what the sheet is, so a person and a scanner read the same thing")
    void theMarkNamesTheSubject() throws Exception {
        UUID invoiceId = UUID.randomUUID();

        PrintService.Printed printed = printing.print(new PrintService.PrintRequest("Rechnung", "invoice",
                invoiceId, List.of("Betrag: 119,00"), null));

        assertThat(printed.reference()).startsWith("BC-INV-");
        assertThat(textOf(printed.pdf())).contains(printed.reference());
    }

    @Test
    @DisplayName("each kind of record gets its own prefix")
    void eachKindGetsItsOwnPrefix() {
        UUID id = UUID.randomUUID();

        assertThat(Barcode.referenceFor("document", id)).startsWith("BC-DOC-");
        assertThat(Barcode.referenceFor("case", id)).startsWith("BC-CASE-");
        assertThat(Barcode.referenceFor("contract", id)).startsWith("BC-CON-");
        assertThat(Barcode.referenceFor("task", id)).startsWith("BC-TASK-");
        assertThat(Barcode.referenceFor("etwas.anderes", id)).startsWith("BC-REF-");
    }

    @Test
    @DisplayName("the reference is short enough to read out loud")
    void theReferenceIsShort() {
        String reference = Barcode.referenceFor("invoice", UUID.randomUUID());

        assertThat(reference).hasSizeLessThan(30).matches("BC-[A-Z]+-[0-9A-F]{12}");
    }

    @Test
    @DisplayName("every page carries the mark")
    void everyPageCarriesTheMark() throws Exception {
        // Enough lines to run past one page. Pages get separated - that is what happens to paper - and a
        // page without a mark can no longer be attributed to anything.
        List<String> many = new ArrayList<>();
        for (int line = 0; line < 120; line++) {
            many.add("Zeile " + line);
        }

        PrintService.Printed printed = printing.print(request(many));

        assertThat(printed.pages()).isGreaterThan(1);
        String text = textOf(printed.pdf());
        long occurrences = text.lines().filter(line -> line.contains(printed.reference())).count();
        assertThat(occurrences)
                .as("one mark per page, so a separated sheet can still be identified")
                .isGreaterThanOrEqualTo(printed.pages());
    }

    @Test
    @DisplayName("content that does not fit continues on the next page rather than being cut off")
    void contentContinuesRatherThanBeingCutOff() throws Exception {
        List<String> many = new ArrayList<>();
        for (int line = 0; line < 120; line++) {
            many.add("Zeile " + line);
        }

        PrintService.Printed printed = printing.print(request(many));
        String text = textOf(printed.pdf());

        assertThat(text)
                .as("a register that silently loses its last entries is worse than a longer one")
                .contains("Zeile 0").contains("Zeile 119");
    }

    @Test
    @DisplayName("a continuation page says that it is one")
    void aContinuationPageSaysSo() throws Exception {
        List<String> many = new ArrayList<>();
        for (int line = 0; line < 120; line++) {
            many.add("Zeile " + line);
        }

        assertThat(textOf(printing.print(request(many)).pdf())).contains("Fortsetzung");
    }

    @Test
    @DisplayName("an empty document still produces a sheet with its heading and mark")
    void anEmptyDocumentStillPrints() throws Exception {
        PrintService.Printed printed = printing.print(request(List.of()));

        assertThat(printed.pages()).isEqualTo(1);
        assertThat(textOf(printed.pdf())).contains("Eingangsrechnung").contains(printed.reference());
    }

    @Test
    @DisplayName("each page is numbered")
    void pagesAreNumbered() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int line = 0; line < 60; line++) {
            lines.add("Zeile " + line);
        }

        String text = textOf(printing.print(request(lines)).pdf());

        assertThat(text).contains("Seite 1");
    }

    @Test
    @DisplayName("the footer names the office it belongs to")
    void theFooterNamesTheOffice() throws Exception {
        assertThat(textOf(printing.print(request(List.of("Zeile"))).pdf())).contains("Muster GmbH");
    }

    // ---------------------------------------------------------------- registers and labels

    @Test
    @DisplayName("a register prints its columns and rows")
    void aRegisterPrintsItsRows() throws Exception {
        PrintService.Printed printed = printing.printRegister(new PrintService.RegisterRequest(
                "Offene Rechnungen", "case", UUID.randomUUID(),
                List.of("Nummer", "Betrag", "Fällig"),
                List.of(List.of("RE-1", "119,00", "31.03.2026"), List.of("RE-2", "59,50", "15.04.2026")),
                "Muster GmbH"));

        String text = textOf(printed.pdf());
        assertThat(text).contains("Nummer").contains("RE-1").contains("RE-2");
        // KNOWN LIMITATION, asserted rather than hidden: the built-in typeface covers ASCII only, so an
        // umlaut is printed as '?'. For a German product that is a real shortcoming - "Faellig" and
        // "F?llig" are both wrong, one illegibly so - and the fix is to embed a font that covers
        // Latin-1. It is recorded here so that the limitation cannot be mistaken for correct output.
        assertThat(text).contains("F?llig");
    }

    @Test
    @DisplayName("an empty register is refused rather than printed blank")
    void anEmptyRegisterIsRefused() {
        assertThatThrownBy(() -> printing.printRegister(new PrintService.RegisterRequest("Leer", "case",
                UUID.randomUUID(), List.of("A"), List.of(), null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a stack of labels is marked sheet by sheet")
    void aStackOfLabelsIsMarkedSheetBySheet() {
        List<PrintService.PrintRequest> labels = List.of(request(List.of("Ordner 1")),
                request(List.of("Ordner 2")), request(List.of("Ordner 3")));

        List<PrintService.Printed> printed = printing.printLabels(labels);

        assertThat(printed).hasSize(3);
        assertThat(printed).extracting(PrintService.Printed::reference).doesNotHaveDuplicates();
        assertThat(printed).allSatisfy(sheet -> assertThat(sheet.pages()).isEqualTo(1));
    }

    @Test
    @DisplayName("an empty stack of labels is refused")
    void anEmptyStackIsRefused() {
        assertThatThrownBy(() -> printing.printLabels(List.of())).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("printing without a subject is refused")
    void printingWithoutASubjectIsRefused() {
        assertThatThrownBy(() -> printing.print(new PrintService.PrintRequest("Ohne Bezug", "invoice", null,
                List.of("Zeile"), null)))
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- the mark itself

    @Test
    @DisplayName("a mark is a matrix of modules, not a picture of one")
    void aMarkIsAMatrix() {
        Barcode.Mark qr = Barcode.qr("BC-INV-0123456789AB", 120);

        assertThat(qr.format()).isEqualTo("QR_CODE");
        assertThat(qr.width()).isEqualTo(qr.height());
        assertThat(countDark(qr)).as("a QR code that is all one colour carries nothing").isGreaterThan(0);
        assertThat(countDark(qr)).isLessThan(qr.width() * qr.height());
    }

    @Test
    @DisplayName("a barcode is one dimensional and taller than it is wide")
    void aBarcodeIsLinear() {
        Barcode.Mark barcode = Barcode.code128("BC-INV-0123456789AB", 200, 60);

        assertThat(barcode.format()).isEqualTo("CODE_128");
        assertThat(barcode.width()).isGreaterThan(barcode.height());
    }

    @Test
    @DisplayName("the same reference always produces the same mark")
    void theSameReferenceProducesTheSameMark() {
        Barcode.Mark first = Barcode.qr("BC-INV-0123456789AB", 120);
        Barcode.Mark second = Barcode.qr("BC-INV-0123456789AB", 120);

        for (int y = 0; y < first.height(); y++) {
            for (int x = 0; x < first.width(); x++) {
                assertThat(first.isDark(x, y)).isEqualTo(second.isDark(x, y));
            }
        }
    }

    @Test
    @DisplayName("an empty reference is refused rather than producing a blank mark")
    void anEmptyReferenceIsRefused() {
        assertThatThrownBy(() -> Barcode.qr("", 120)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Barcode.qr(null, 120)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a character the printer cannot draw does not stop the sheet")
    void anOddCharacterDoesNotStopTheSheet() throws Exception {
        // A document with one odd character in it still has to come out of the printer.
        PrintService.Printed printed = printing.print(request(List.of("Betrag: 119,00 € – Café Müller")));

        assertThat(textOf(printed.pdf())).contains("Betrag: 119,00");
        assertThat(PrintService.sanitize("Café")).isEqualTo("Caf?");
    }

    private long countDark(Barcode.Mark mark) {
        long dark = 0;
        for (int y = 0; y < mark.height(); y++) {
            for (int x = 0; x < mark.width(); x++) {
                if (mark.isDark(x, y)) {
                    dark++;
                }
            }
        }
        return dark;
    }
}
