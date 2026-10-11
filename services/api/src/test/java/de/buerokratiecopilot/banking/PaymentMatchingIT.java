package de.buerokratiecopilot.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * Verifies reconciliation against the bank statement (spec §16, D-007 acceptance).
 *
 * <p>Two criteria are implemented and tested here.</p>
 *
 * <p><strong>An ambiguous match is not made automatically.</strong> A wrongly reconciled payment is
 * worse than an unreconciled one: it closes an invoice that is still open and hides the one that was
 * actually paid. So the test builds the situation where two invoices could be meant, and checks that the
 * platform proposes and changes nothing.</p>
 *
 * <p><strong>Bank credentials are not stored in the open.</strong> The test tries to save something that
 * is a secret rather than a reference to one, and expects to be refused at the door — a rule in a
 * document does not stop anybody pasting a password.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentMatchingIT {

    @Autowired
    private PaymentMatchingService matching;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Sperr Test', 'COMPANY') returning id
                """, UUID.class, "bank-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID account(UUID tenantId, String iban) {
        return matching.registerAccount(tenantId, "Geschäftskonto", iban, "COBADEFFXXX",
                "Commerzbank", "Beispiel GmbH", "EUR", "vault://bank/commerzbank");
    }

    /** An outgoing invoice with a given number and open amount. */
    private Invoice invoice(UUID tenantId, String number, String gross) {
        Invoice invoice = new Invoice(UUID.randomUUID(), tenantId, number);
        invoice.setDirection(Invoice.Direction.OUTGOING);
        invoice.setCurrency("EUR");
        invoice.setGrossAmount(new BigDecimal(gross));
        invoice.setNetAmount(new BigDecimal(gross));
        invoice.setStatus(Invoice.Status.APPROVED);
        invoice.setIssueDate(LocalDate.of(2026, 3, 1));
        return RequestContext.runAs(tenantId, () -> invoices.save(invoice));
    }

    private static String camt(String iban, String... entries) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
                  <BkToCstmrStmt><Stmt>
                    <Id>STMT-%s</Id>
                    <Acct><Id><IBAN>%s</IBAN></Id><Ccy>EUR</Ccy></Acct>
                    %s
                  </Stmt></BkToCstmrStmt>
                </Document>
                """.formatted(UUID.randomUUID().toString().substring(0, 8), iban, String.join("\n", entries));
    }

    private static String credit(String amount, String reference, String name, String bankRef) {
        return """
                <Ntry>
                  <Amt Ccy="EUR">%s</Amt><CdtDbtInd>CRDT</CdtDbtInd>
                  <BookgDt><Dt>2026-03-15</Dt></BookgDt>
                  <AcctSvcrRef>%s</AcctSvcrRef>
                  <NtryDtls><TxDtls>
                    <RmtInf><Ustrd>%s</Ustrd></RmtInf>
                    <RltdPties><Dbtr><Nm>%s</Nm></Dbtr></RltdPties>
                  </TxDtls></NtryDtls>
                </Ntry>
                """.formatted(amount, bankRef, reference, name);
    }

    private PaymentMatchingService.ImportResult importCamt(UUID tenantId, UUID accountId, String xml)
            throws Exception {
        UUID user = person(tenantId);
        return RequestContext.runAs(tenantId, () -> {
            try {
                return matching.importCamt(tenantId, accountId, xml.getBytes(StandardCharsets.UTF_8), user);
            }
            catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    private UUID person(UUID tenantId) {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Buchhalter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "bk-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    @Test
    @DisplayName("a payment quoting exactly one invoice number is matched automatically")
    void uniqueNumberIsMatchedAutomatically() throws Exception {
        UUID tenantId = tenant();
        UUID accountId = account(tenantId, "DE89370400440532013000");
        Invoice invoice = invoice(tenantId, "RE-2026-00001", "119.00");

        PaymentMatchingService.ImportResult result = importCamt(tenantId, accountId,
                camt("DE89370400440532013000",
                        credit("119.00", "Rechnung RE-2026-00001", "Beispiel Kunde AG", "BR-1")));

        assertThat(result.transactions()).isEqualTo(1);
        assertThat(result.matchedAutomatically()).isEqualTo(1);
        assertThat(result.proposed()).isZero();

        String status = jdbc.queryForObject("select status from bank_transactions where tenant_id = ?",
                String.class, tenantId);
        assertThat(status).isEqualTo("MATCHED");
        assertThat(jdbc.queryForObject("""
                select count(*) from payment_matches where tenant_id = ? and invoice_id = ? and
                status = 'CONFIRMED'
                """, Integer.class, tenantId, invoice.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("two invoices that could both be meant are only proposed, never matched")
    void ambiguousMatchIsNotMadeAutomatically() throws Exception {
        UUID tenantId = tenant();
        UUID accountId = account(tenantId, "DE89370400440532013000");
        // Neither number is quoted, and both invoices have the same open amount: the amount alone
        // cannot say which one was paid.
        invoice(tenantId, "RE-2026-00002", "119.00");
        invoice(tenantId, "RE-2026-00003", "119.00");

        PaymentMatchingService.ImportResult result = importCamt(tenantId, accountId,
                camt("DE89370400440532013000",
                        credit("119.00", "Überweisung", "Beispiel Kunde AG", "BR-2")));

        assertThat(result.matchedAutomatically())
                .as("choosing one of two invoices would close the wrong one")
                .isZero();
        assertThat(result.proposed()).isEqualTo(1);

        String status = jdbc.queryForObject("select status from bank_transactions where tenant_id = ?",
                String.class, tenantId);
        assertThat(status).isEqualTo("SUGGESTED");
        assertThat(jdbc.queryForObject("""
                select count(*) from payment_matches where tenant_id = ? and status = 'PROPOSED'
                """, Integer.class, tenantId))
                .as("both candidates are recorded, so a person can choose")
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from payment_matches where tenant_id = ? and status = 'CONFIRMED'
                """, Integer.class, tenantId)).isZero();
    }

    @Test
    @DisplayName("a person confirms one of the proposals and the others fall away")
    void personDecidesAmbiguousMatch() throws Exception {
        UUID tenantId = tenant();
        UUID accountId = account(tenantId, "DE89370400440532013000");
        Invoice first = invoice(tenantId, "RE-2026-00004", "119.00");
        Invoice second = invoice(tenantId, "RE-2026-00005", "119.00");
        UUID user = person(tenantId);

        RequestContext.runAs(tenantId, () -> {
            try {
                matching.importCamt(tenantId, accountId,
                        camt("DE89370400440532013000",
                                credit("119.00", "Überweisung", "Beispiel Kunde AG", "BR-3"))
                                .getBytes(StandardCharsets.UTF_8), user);
            }
            catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            return null;
        });

        UUID transactionId = jdbc.queryForObject("select id from bank_transactions where tenant_id = ?",
                UUID.class, tenantId);
        jdbc.queryForObject("select id from payment_matches where bank_transaction_id = ? and invoice_id = ?",
                UUID.class, transactionId, second.getId());

        RequestContext.runAs(tenantId, () -> {
            matching.decideProposal(tenantId, transactionId, second.getId(), true, user,
                    "Laut Kontoauszug die zweite Rechnung");
            return null;
        });

        // One confirmed match, and the transaction is no longer waiting.
        assertThat(jdbc.queryForObject("""
                select count(*) from payment_matches
                where bank_transaction_id = ? and invoice_id = ? and status = 'CONFIRMED'
                """, Integer.class, transactionId, second.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from payment_matches
                where bank_transaction_id = ? and invoice_id = ? and status = 'CONFIRMED'
                """, Integer.class, transactionId, first.getId())).isZero();
        assertThat(jdbc.queryForObject("select status from bank_transactions where id = ?", String.class,
                transactionId)).isEqualTo("MATCHED");
    }

    @Test
    @DisplayName("a rejected proposal leaves the transaction open, so it can be matched elsewhere")
    void rejectedProposalLeavesTheTransactionOpen() throws Exception {
        UUID tenantId = tenant();
        UUID accountId = account(tenantId, "DE89370400440532013000");
        Invoice invoice = invoice(tenantId, "RE-2026-00006", "119.00");
        // A second invoice with the same open amount, so the platform has to ask instead of deciding.
        invoice(tenantId, "RE-2026-00066", "119.00");
        UUID user = person(tenantId);

        RequestContext.runAs(tenantId, () -> {
            try {
                matching.importCamt(tenantId, accountId,
                        camt("DE89370400440532013000",
                                credit("119.00", "Überweisung", "Anderer Kunde", "BR-4"))
                                .getBytes(StandardCharsets.UTF_8), user);
            }
            catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            return null;
        });

        UUID transactionId = jdbc.queryForObject("select id from bank_transactions where tenant_id = ?",
                UUID.class, tenantId);
        RequestContext.runAs(tenantId, () -> {
            matching.decideProposal(tenantId, transactionId, invoice.getId(), false, user,
                    "Falscher Kunde");
            return null;
        });

        assertThat(jdbc.queryForObject("select status from bank_transactions where id = ?", String.class,
                transactionId)).isEqualTo("UNMATCHED");
        assertThat(matching.openTransactions(tenantId, accountId)).hasSize(1);
    }

    @Test
    @DisplayName("the same booking imported twice is skipped instead of counted twice")
    void duplicateBookingIsSkipped() throws Exception {
        UUID tenantId = tenant();
        UUID accountId = account(tenantId, "DE89370400440532013000");
        invoice(tenantId, "RE-2026-00007", "119.00");
        String xml = camt("DE89370400440532013000",
                credit("119.00", "Rechnung RE-2026-00007", "Beispiel Kunde AG", "BR-5"));

        PaymentMatchingService.ImportResult first = importCamt(tenantId, accountId, xml);
        PaymentMatchingService.ImportResult second = importCamt(tenantId, accountId, xml);

        assertThat(first.transactions()).isEqualTo(1);
        assertThat(second.transactions())
                .as("overlapping statement exports are normal; counting the same booking twice is not")
                .isZero();
        assertThat(second.duplicatesSkipped()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from bank_transactions where tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a statement for another account is refused")
    void statementForAnotherAccountIsRefused() {
        UUID tenantId = tenant();
        UUID accountId = account(tenantId, "DE89370400440532013000");

        assertThatThrownBy(() -> importCamt(tenantId, accountId,
                camt("DE12500105170648489890", credit("10.00", "Test", "Jemand", "BR-6"))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("anderen Konto");
    }

    @Test
    @DisplayName("a credential that is a secret rather than a reference is refused")
    void credentialsAreNotStoredInTheOpen() {
        UUID tenantId = tenant();

        // The canonical reference format has to be accepted, or users paste the password instead.
        assertThat(matching.registerAccount(tenantId, "Konto", "DE89370400440532013000", null, null, null,
                "EUR", "vault://bank/main")).isNotNull();

        assertThatThrownBy(() -> matching.registerAccount(tenantId, "Konto2", "DE89370400440532013000",
                null, null, null, "EUR", "MeinPasswort123"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Zugangsdaten");
        assertThatThrownBy(() -> matching.registerAccount(tenantId, "Konto3", "DE89370400440532013000",
                null, null, null, "EUR", "{\"apiKey\":\"abc123\"}"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> matching.registerAccount(tenantId, "Konto4", "DE89370400440532013000",
                null, null, null, "EUR", "access_token"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> matching.registerAccount(tenantId, "Konto5", "DE89370400440532013000",
                null, null, null, "EUR", "apikey=abc123"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a reference to a secret is accepted, and the secret itself is nowhere in the database")
    void referenceIsAccepted() {
        UUID tenantId = tenant();

        UUID accountId = matching.registerAccount(tenantId, "Konto", "DE89370400440532013000",
                "COBADEFFXXX", "Commerzbank", "Beispiel GmbH", "EUR", "op://Personal/Bank/credential");

        assertThat(matching.hasCredentials(tenantId, accountId)).isTrue();
        Map<String, Object> row = jdbc.queryForMap("select * from financial_accounts where id = ?",
                accountId);
        assertThat(row.values().stream().map(String::valueOf).toList())
                .as("only the pointer is stored, never the secret it points at")
                .allSatisfy(value -> assertThat(value).doesNotContain("MeinPasswort"));
    }

    @Test
    @DisplayName("the imported statement file is kept as a source document")
    void statementFileIsKept() throws Exception {
        UUID tenantId = tenant();
        UUID accountId = account(tenantId, "DE89370400440532013000");

        importCamt(tenantId, accountId,
                camt("DE89370400440532013000", credit("10.00", "Test", "Jemand", "BR-7")));

        assertThat(jdbc.queryForObject("""
                select count(*) from bank_statements where tenant_id = ? and format = 'CAMT053'
                """, Integer.class, tenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from document_blobs where tenant_id = ? and scan_status = 'CLEAN'
                """, Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("an outgoing booking on the account does not match a customer invoice")
    void outgoingBookingDoesNotMatchACustomerInvoice() throws Exception {
        UUID tenantId = tenant();
        UUID accountId = account(tenantId, "DE89370400440532013000");
        invoice(tenantId, "RE-2026-00008", "119.00");
        String xml = camt("DE89370400440532013000",
                credit("119.00", "Rechnung RE-2026-00008", "Beispiel Kunde AG", "BR-8")
                        .replace("<CdtDbtInd>CRDT</CdtDbtInd>", "<CdtDbtInd>DBIT</CdtDbtInd>"));

        PaymentMatchingService.ImportResult result = importCamt(tenantId, accountId, xml);

        assertThat(result.matchedAutomatically())
                .as("money leaving the account does not settle a customer invoice")
                .isZero();
        assertThat(result.proposed()).isZero();
    }
}
