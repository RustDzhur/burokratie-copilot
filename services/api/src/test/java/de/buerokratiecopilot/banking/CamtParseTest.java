package de.buerokratiecopilot.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.banking.CamtParser.Statement;
import de.buerokratiecopilot.banking.CamtParser.Transaction;

/**
 * Verifies reading CAMT.053 statements (spec §16, D-007).
 *
 * <p>The sign convention is what these tests are mostly about. CAMT carries the amount as a positive
 * number and a separate credit/debit indicator, so a reader that ignores the indicator turns every
 * outgoing payment into income — and the resulting reconciliation would be wrong in a way that looks
 * plausible. Each test below states which direction the money went and checks that the reader agrees.</p>
 */
class CamtParseTest {

    private static final String CAMT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">
              <BkToCstmrStmt>
                <Stmt>
                  <Id>STMT-2026-03</Id>
                  <Acct>
                    <Id><IBAN>DE89370400440532013000</IBAN></Id>
                    <Ccy>EUR</Ccy>
                  </Acct>
                  <FrToDt>
                    <FrDtTm>2026-03-01T00:00:00</FrDtTm>
                    <ToDtTm>2026-03-31T23:59:59</ToDtTm>
                  </FrToDt>
                  <Bal>
                    <Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp>
                    <Amt Ccy="EUR">1000.00</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                  </Bal>
                  <Bal>
                    <Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp>
                    <Amt Ccy="EUR">1119.00</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                  </Bal>
                  <Ntry>
                    <Amt Ccy="EUR">119.00</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                    <Sts>BOOK</Sts>
                    <BookgDt><Dt>2026-03-15</Dt></BookgDt>
                    <ValDt><Dt>2026-03-15</Dt></ValDt>
                    <AcctSvcrRef>BANK-REF-1</AcctSvcrRef>
                    <BkTxCd><Domn><Cd>PMNT</Cd></Domn></BkTxCd>
                    <NtryDtls>
                      <TxDtls>
                        <RmtInf><Ustrd>Rechnung RE-2026-00001</Ustrd></RmtInf>
                        <RltdPties>
                          <Dbtr><Nm>Beispiel Kunde AG</Nm></Dbtr>
                          <DbtrAcct><Id><IBAN>DE12500105170648489890</IBAN></Id></DbtrAcct>
                        </RltdPties>
                      </TxDtls>
                    </NtryDtls>
                  </Ntry>
                  <Ntry>
                    <Amt Ccy="EUR">50.00</Amt>
                    <CdtDbtInd>DBIT</CdtDbtInd>
                    <BookgDt><Dt>2026-03-20</Dt></BookgDt>
                    <AcctSvcrRef>BANK-REF-2</AcctSvcrRef>
                    <NtryDtls>
                      <TxDtls>
                        <RmtInf><Ustrd>Büromaterial</Ustrd></RmtInf>
                        <RltdPties><Cdtr><Nm>Schreibwaren GmbH</Nm></Cdtr></RltdPties>
                      </TxDtls>
                    </NtryDtls>
                  </Ntry>
                </Stmt>
              </BkToCstmrStmt>
            </Document>
            """;

    private static List<Statement> parse(String xml) throws Exception {
        return CamtParser.parse(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a statement is read with its account, period and balances")
    void readsTheStatement() throws Exception {
        Statement statement = parse(CAMT).getFirst();

        assertThat(statement.statementId()).isEqualTo("STMT-2026-03");
        assertThat(statement.accountIban()).isEqualTo("DE89370400440532013000");
        assertThat(statement.currency()).isEqualTo("EUR");
        assertThat(statement.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("1119.00");
        assertThat(statement.transactions()).hasSize(2);
    }

    @Test
    @DisplayName("the opening balance is the one the code marks as opening, not the first one found")
    void readsTheRightBalance() throws Exception {
        // Some exports list the closing balance first; taking the first would silently swap them.
        String reordered = CAMT.replace("""
                  <Bal>
                    <Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp>
                    <Amt Ccy="EUR">1000.00</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                  </Bal>
                  <Bal>
                    <Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp>
                    <Amt Ccy="EUR">1119.00</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                  </Bal>""", """
                  <Bal>
                    <Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp>
                    <Amt Ccy="EUR">1119.00</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                  </Bal>
                  <Bal>
                    <Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp>
                    <Amt Ccy="EUR">1000.00</Amt>
                    <CdtDbtInd>CRDT</CdtDbtInd>
                  </Bal>""");

        Statement statement = parse(reordered).getFirst();

        assertThat(statement.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(statement.closingBalance()).isEqualByComparingTo("1119.00");
    }

    @Test
    @DisplayName("an incoming amount is positive and an outgoing one negative")
    void readsTheDirectionOfTheMoney() throws Exception {
        List<Transaction> transactions = parse(CAMT).getFirst().transactions();

        Transaction incoming = transactions.get(0);
        assertThat(incoming.credit()).isTrue();
        assertThat(incoming.amount()).isEqualByComparingTo("119.00");
        assertThat(incoming.signedAmount()).isEqualByComparingTo("119.00");

        Transaction outgoing = transactions.get(1);
        assertThat(outgoing.credit())
                .as("ignoring the credit/debit indicator would turn every payment into income")
                .isFalse();
        assertThat(outgoing.amount())
                .as("CAMT always states the amount as a positive number")
                .isEqualByComparingTo("50.00");
        assertThat(outgoing.signedAmount()).isEqualByComparingTo("-50.00");
    }

    @Test
    @DisplayName("dates, counterparty and purpose are read as the bank wrote them")
    void readsTheDetails() throws Exception {
        Transaction incoming = parse(CAMT).getFirst().transactions().getFirst();

        assertThat(incoming.bookingDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(incoming.valueDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(incoming.counterpartyName()).isEqualTo("Beispiel Kunde AG");
        assertThat(incoming.counterpartyIban()).isEqualTo("DE12500105170648489890");
        assertThat(incoming.purpose()).isEqualTo("Rechnung RE-2026-00001");
        assertThat(incoming.externalId()).isEqualTo("BANK-REF-1");
        assertThat(incoming.currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("an outgoing booking names the creditor as the counterparty")
    void readsTheCreditorForAnOutgoingBooking() throws Exception {
        Transaction outgoing = parse(CAMT).getFirst().transactions().get(1);

        assertThat(outgoing.counterpartyName()).isEqualTo("Schreibwaren GmbH");
    }

    @Test
    @DisplayName("a file that is not a statement is refused as such")
    void refusesNonStatement() {
        assertThatThrownBy(() -> parse("<?xml version=\"1.0\"?><Notiz/>"))
                .isInstanceOf(CamtParser.NotAStatementException.class)
                .extracting(ex -> ((CamtParser.NotAStatementException) ex).reason())
                .isEqualTo("NO_STATEMENTS");
    }

    @Test
    @DisplayName("malformed XML and an empty file are reported for what they are")
    void refusesBrokenInput() {
        assertThatThrownBy(() -> CamtParser.parse(new byte[0]))
                .isInstanceOf(CamtParser.NotAStatementException.class);
        assertThatThrownBy(() -> parse("<Document>"))
                .isInstanceOf(CamtParser.NotAStatementException.class);
    }

    @Test
    @DisplayName("a document type declaration is refused")
    void refusesDoctype() {
        String attack = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt>
                  <Stmt><Id>&xxe;</Id></Stmt></BkToCstmrStmt></Document>
                """;

        assertThatThrownBy(() -> parse(attack)).isInstanceOf(CamtParser.NotAStatementException.class);
    }

    @Test
    @DisplayName("a statement without entries is still a statement")
    void acceptsStatementWithoutEntries() throws Exception {
        String empty = CAMT.replaceAll("(?s)<Ntry>.*</Ntry>", "");

        Statement statement = parse(empty).getFirst();

        assertThat(statement.transactions()).isEmpty();
        assertThat(statement.statementId()).isEqualTo("STMT-2026-03");
    }

    @Test
    @DisplayName("looksLikeCamt recognises a statement and rejects other XML")
    void detectsCamt() {
        assertThat(CamtParser.looksLikeCamt(CAMT.getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(CamtParser.looksLikeCamt("<Invoice/>".getBytes(StandardCharsets.UTF_8))).isFalse();
    }
}
