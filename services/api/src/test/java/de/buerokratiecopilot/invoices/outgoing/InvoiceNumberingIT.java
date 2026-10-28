package de.buerokratiecopilot.invoices.outgoing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.invoices.InvoiceRepository;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies invoice numbering (spec §14, D-006 acceptance) and the document that carries the number.
 *
 * <p>The acceptance criterion is numbering without gaps and without duplicates. Both halves are tested
 * against the way they actually fail: duplicates appear when two invoices are created at the same
 * moment, and gaps appear when an invoice creation fails after the number was taken. A test that only
 * calls the counter in a loop would pass for an implementation that does neither correctly.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class InvoiceNumberingIT {

    @Autowired
    private InvoiceNumberService numbering;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private InvoicePdfRenderer renderer;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Nummernkreis Test', 'COMPANY') returning id
                """, UUID.class, "num-" + UUID.randomUUID().toString().substring(0, 12));
    }

    /**
     * Takes a number the way the real caller does: inside a transaction, together with the invoice.
     *
     * <p>The service refuses to hand out a number outside a transaction, and the first version of this
     * test failed because of it. That is the service being right: a number taken without a transaction
     * to roll back with is exactly the gap the design prevents.</p>
     */
    private String takeNumberInTransaction(UUID tenantId, String code) {
        return inTransaction(() -> RequestContext.runAs(tenantId,
                () -> numbering.nextNumber(tenantId, code, LocalDate.of(2026, 3, 1))));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> action) {
        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        return template.execute(status -> action.get());
    }

    @Test
    @DisplayName("numbers are handed out in order, without a gap")
    void numbersAreConsecutive() {
        UUID tenantId = tenant();
        numbering.createSequence(tenantId, "RE", "Rechnungen", "RE-2026-{number:5}",
                InvoiceNumberSequence.ResetPolicy.YEARLY);

        String first = takeNumberInTransaction(tenantId, "RE");
        String second = takeNumberInTransaction(tenantId, "RE");
        String third = takeNumberInTransaction(tenantId, "RE");

        assertThat(first).isEqualTo("RE-2026-00001");
        assertThat(second).isEqualTo("RE-2026-00002");
        assertThat(third).isEqualTo("RE-2026-00003");
    }

    @Test
    @DisplayName("a failed invoice does not consume a number, so the series has no hole")
    void failedInvoiceLeavesNoGap() {
        UUID tenantId = tenant();
        numbering.createSequence(tenantId, "RE", "Rechnungen", "RE-2026-{number:5}",
                InvoiceNumberSequence.ResetPolicy.YEARLY);

        takeNumberInTransaction(tenantId, "RE");

        // A transaction that takes a number and then fails must leave nothing behind. This is the whole
        // reason the counter is a row and not a database sequence: a sequence does not roll back.
        assertThatThrownBy(() -> inTransaction(() -> {
            RequestContext.runAs(tenantId, () -> numbering.nextNumber(tenantId, "RE",
                    LocalDate.of(2026, 3, 1)));
            throw new IllegalStateException("Die Rechnung konnte nicht gespeichert werden");
        })).isInstanceOf(IllegalStateException.class);

        String afterFailure = takeNumberInTransaction(tenantId, "RE");
        assertThat(afterFailure)
                .as("the number the failed invoice would have used is used by the next one")
                .isEqualTo("RE-2026-00002");
    }

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("two invoices created at the same moment get different numbers")
    void simultaneousInvoicesDoNotCollide() throws Exception {
        UUID tenantId = tenant();
        numbering.createSequence(tenantId, "RE", "Rechnungen", "RE-2026-{number:5}",
                InvoiceNumberSequence.ResetPolicy.YEARLY);

        int attempts = 6;
        List<Callable<String>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            tasks.add(() -> takeNumberInTransaction(tenantId, "RE"));
        }

        List<String> taken = new java.util.ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Future<String>> results = pool.invokeAll(tasks);
            for (Future<String> result : results) {
                taken.add(result.get());
            }
        }

        assertThat(taken)
                .as("the same number twice is the duplicate the acceptance criterion forbids")
                .doesNotHaveDuplicates()
                .hasSize(attempts);
        assertThat(taken).containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.rangeClosed(1, attempts)
                        .mapToObj(i -> "RE-2026-%05d".formatted(i)).toList());
    }

    @Test
    @DisplayName("a yearly sequence starts again in the new year")
    void yearlyResetStartsAtOne() {
        UUID tenantId = tenant();
        numbering.createSequence(tenantId, "RE", "Rechnungen", "RE-{year}-{number:4}",
                InvoiceNumberSequence.ResetPolicy.YEARLY);
        jdbc.update("update invoice_number_sequences set next_value = 57, year = 2025 "
                + "where tenant_id = ? and code = 'RE'", tenantId);

        String next = inTransaction(() -> RequestContext.runAs(tenantId,
                () -> numbering.nextNumber(tenantId, "RE", LocalDate.of(2026, 1, 2))));

        assertThat(next).isEqualTo("RE-2026-0001");
    }

    @Test
    @DisplayName("a sequence that never resets keeps counting")
    void neverResetKeepsCounting() {
        UUID tenantId = tenant();
        numbering.createSequence(tenantId, "GS", "Gutschriften", "GS-{number:5}",
                InvoiceNumberSequence.ResetPolicy.NEVER);

        String first = takeNumberInTransaction(tenantId, "GS");
        String second = takeNumberInTransaction(tenantId, "GS");

        assertThat(first).isEqualTo("GS-00001");
        assertThat(second).isEqualTo("GS-00002");
    }

    @Test
    @DisplayName("a pattern without the number placeholder is refused, because it would repeat one number")
    void patternWithoutPlaceholderIsRefused() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> numbering.createSequence(tenantId, "BAD", "Kaputt", "RE-2026",
                InvoiceNumberSequence.ResetPolicy.YEARLY))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("{number}");
    }

    @Test
    @DisplayName("the same code cannot be created twice")
    void duplicateCodeIsRefused() {
        UUID tenantId = tenant();
        numbering.createSequence(tenantId, "RE", "Rechnungen", "RE-{number}",
                InvoiceNumberSequence.ResetPolicy.YEARLY);

        assertThatThrownBy(() -> numbering.createSequence(tenantId, "RE", "Nochmal", "RE-{number}",
                InvoiceNumberSequence.ResetPolicy.YEARLY))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("existiert bereits");
    }

    @Test
    @DisplayName("an unknown sequence is not found rather than silently invented")
    void unknownSequenceIsNotFound() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> takeNumberInTransaction(tenantId, "GIBT-ES-NICHT"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("peeking shows the next number without spending it")
    void peekDoesNotSpend() {
        UUID tenantId = tenant();
        numbering.createSequence(tenantId, "RE", "Rechnungen", "RE-{number:3}",
                InvoiceNumberSequence.ResetPolicy.YEARLY);

        String peeked = RequestContext.runAs(tenantId, () -> numbering.peek(tenantId, "RE",
                LocalDate.of(2026, 3, 1)));
        String taken = takeNumberInTransaction(tenantId, "RE");

        assertThat(peeked).isEqualTo(taken).isEqualTo("RE-001");
    }

    @Test
    @DisplayName("a PDF is generated, and it carries the number, the parties and the amounts")
    void pdfIsGenerated() throws Exception {
        UUID tenantId = tenant();
        numbering.createSequence(tenantId, "RE", "Rechnungen", "RE-2026-{number:5}",
                InvoiceNumberSequence.ResetPolicy.YEARLY);
        String number = takeNumberInTransaction(tenantId, "RE");

        Invoice invoice = new Invoice(UUID.randomUUID(), tenantId, number);
        invoice.setIssueDate(LocalDate.of(2026, 3, 1));
        invoice.setDueDate(LocalDate.of(2026, 3, 31));
        invoice.setCurrency("EUR");
        invoice.setNetAmount(new BigDecimal("100.00"));
        invoice.setTaxAmount(new BigDecimal("19.00"));
        invoice.setGrossAmount(new BigDecimal("119.00"));
        invoice.setIban("DE89370400440532013000");

        byte[] pdf = renderer.render(new InvoicePdfRenderer.Document(invoice, List.of(
                new InvoicePdfRenderer.RenderLine(1, "Wartungspauschale März", BigDecimal.ONE, "Stk",
                        new BigDecimal("100.00"), new BigDecimal("100.00"), new BigDecimal("19.00"))),
                "Muster Lieferant GmbH", "Beispiel Kunde AG"));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .as("the file really is a PDF")
                .isEqualTo("%PDF-");

        String text = extractText(pdf);
        assertThat(text).contains(number);
        assertThat(text).contains("Muster Lieferant GmbH");
        assertThat(text).contains("Beispiel Kunde AG");
        assertThat(text).contains("Gesamt");
        // The amount as a German reader expects it, which is the audience of the document.
        assertThat(text).contains("119,00 EUR");
    }

    @Test
    @DisplayName("a cancelled invoice is marked as cancelled in the document")
    void cancelledInvoiceIsMarked() throws Exception {
        UUID tenantId = tenant();
        Invoice invoice = new Invoice(UUID.randomUUID(), tenantId, "RE-2026-00009");
        invoice.setCurrency("EUR");
        invoice.setGrossAmount(new BigDecimal("119.00"));
        invoice.setStatus(Invoice.Status.CANCELLED);

        String text = extractText(renderer.render(invoice, List.of()));

        assertThat(text)
                .as("a customer holding both versions has to be able to tell which one counts")
                .contains("storniert");
    }

    @Test
    @DisplayName("a partly paid invoice shows what is still open")
    void partlyPaidInvoiceShowsTheOpenAmount() throws Exception {
        UUID tenantId = tenant();
        Invoice invoice = new Invoice(UUID.randomUUID(), tenantId, "RE-2026-00010");
        invoice.setCurrency("EUR");
        invoice.setGrossAmount(new BigDecimal("119.00"));
        invoice.setPaidAmount(new BigDecimal("50.00"));
        invoice.setPaymentStatus(Invoice.PaymentStatus.PARTIALLY_PAID);

        String text = extractText(renderer.render(invoice, List.of()));

        assertThat(text).contains("Bereits gezahlt");
        assertThat(text).contains("Offener Betrag");
        assertThat(text).contains("69,00 EUR");
    }

    @Test
    @DisplayName("the invoice is stored with the number, so the series and the records agree")
    void numberIsStoredWithTheInvoice() {
        UUID tenantId = tenant();
        numbering.createSequence(tenantId, "RE", "Rechnungen", "RE-2026-{number:5}",
                InvoiceNumberSequence.ResetPolicy.YEARLY);

        String number = inTransaction(() -> RequestContext.runAs(tenantId, () -> {
            String taken = numbering.nextNumber(tenantId, "RE", LocalDate.of(2026, 3, 1));
            Invoice invoice = new Invoice(UUID.randomUUID(), tenantId, taken);
            invoice.setCurrency("EUR");
            invoice.setGrossAmount(new BigDecimal("119.00"));
            return invoices.save(invoice).getInvoiceNumber();
        }));

        assertThat(number).isEqualTo("RE-2026-00001");
        assertThat(jdbc.queryForObject("select count(*) from invoices where tenant_id = ? and "
                + "invoice_number = ?", Integer.class, tenantId, number)).isEqualTo(1);
    }

    private static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
