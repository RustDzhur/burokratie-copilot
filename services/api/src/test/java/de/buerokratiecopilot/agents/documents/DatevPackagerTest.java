package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.documents.domain.DocumentExtraction;

/**
 * The DATEV mapping and checksum rules: one document becomes one import line, warnings travel with
 * the package, and the checksum seals the exact bytes.
 */
class DatevPackagerTest {

    private final Map<String, String> accounts = Map.of(
            "invoice", "1600", "default", "1000", "counter-default", "1600");
    private final DatevPackager packager = new DatevPackager(";", accounts, LocalDate.of(2026, 10, 26));

    private DocumentExtraction extraction(String key, String type, String value) {
        DocumentExtraction row = new DocumentExtraction(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), key, type, "RULE", "TEXT");
        row.setFieldValue(value);
        row.setNormalizedValue(value);
        return row;
    }

    @Test
    @DisplayName("an invoice maps to date, number, accounts, amount and booking text")
    void mapsInvoiceFields() {
        DatevPackager.Line line = packager.mapDocument("Rechnung_RE-2026-0042.pdf", "invoice",
                LocalDate.of(2026, 10, 12), "RE-2026-0042",
                List.of(extraction("Brutto", "AMOUNT", "1190.00"),
                        extraction("Rechnungsdatum", "DATE", "2026-10-12")));

        assertThat(line.date()).isEqualTo("2026-10-12");
        assertThat(line.number()).isEqualTo("RE-2026-0042");
        assertThat(line.account()).isEqualTo("1600");
        assertThat(line.counterAccount()).isEqualTo("1600");
        assertThat(line.amount()).isEqualTo("1190.00");
        assertThat(line.bookingText()).isEqualTo("Rechnung_RE-2026-0042.pdf");
        assertThat(line.warnings()).isEmpty();
    }

    @Test
    @DisplayName("missing and implausible values become warnings, not guesses")
    void warnsAboutMissingAmountAndFutureDate() {
        DatevPackager.Line line = packager.mapDocument("scan.pdf", null, LocalDate.of(2027, 1, 2),
                null, List.of());

        assertThat(line.warnings())
                .contains("Betrag fehlt", "Belegnummer fehlt", "Belegdatum liegt in der Zukunft");
        assertThat(line.account()).isEqualTo("1000");
    }

    @Test
    @DisplayName("a negative amount is reported; the preferred amount key wins over a generic one")
    void prefersDeclaredAmountAndWarnsWhenNegative() {
        DatevPackager.Line line = packager.mapDocument("Gutschrift.pdf", "invoice", null, null,
                List.of(extraction("Brutto", "AMOUNT", "-25.00"),
                        extraction("Kosten", "AMOUNT", "100.00")));

        assertThat(line.amount()).isEqualTo("-25.00");
        assertThat(line.warnings()).contains("Betrag ist negativ", "Belegdatum fehlt",
                "Belegnummer fehlt");
    }

    @Test
    @DisplayName("the checksum seals the exact bytes: any change changes it, same input stays stable")
    void checksumSealsTheBytes() {
        DatevPackager.Line one = packager.mapDocument("a.pdf", "invoice", LocalDate.of(2026, 10, 12),
                "R-1", List.of(extraction("Brutto", "AMOUNT", "10.00")));
        DatevPackager.Line changed = packager.mapDocument("a.pdf", "invoice",
                LocalDate.of(2026, 10, 12), "R-1", List.of(extraction("Brutto", "AMOUNT", "11.00")));

        DatevPackager.Package first = packager.packageLines(List.of(one));
        DatevPackager.Package sameAgain = packager.packageLines(List.of(one));
        DatevPackager.Package different = packager.packageLines(List.of(changed));

        assertThat(first.checksum()).matches("[0-9a-f]{64}");
        assertThat(first.checksum()).isEqualTo(sameAgain.checksum());
        assertThat(first.checksum()).isNotEqualTo(different.checksum());
        assertThat(first.csv(";")).contains("2026-10-12;R-1;1600;1600;10.00;a.pdf");
    }
}
