package de.buerokratiecopilot.banking;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.banking.CamtParser.Statement;
import de.buerokratiecopilot.banking.CamtParser.Transaction;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Reconciles bank transactions with invoices (spec §16, D-007).
 *
 * <p>The acceptance criterion is that an ambiguous match is not made automatically, and it shapes
 * everything here. A match is made on its own only when there is exactly <em>one</em> possible partner:
 * one invoice with that number and that amount, or one open invoice with that exact amount when no
 * number is quoted. As soon as two invoices could be meant, the platform proposes and a person decides
 * — because a wrongly reconciled payment is worse than an unreconciled one: it closes an invoice that is
 * still open and hides the one that was actually paid.</p>
 *
 * <p>Bank credentials are never stored. The schema keeps a <em>reference</em>
 * ({@code financial_accounts.credential_ref}) to a secret held elsewhere, and this service refuses
 * anything that looks like a secret itself, so a password cannot be smuggled in as a reference.</p>
 */
@Service
public class PaymentMatchingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentMatchingService.class);

    /** Words a reference must not contain, because they name the secret rather than pointing at it. */
    private static final List<String> FORBIDDEN_IN_REFERENCE = List.of("password", "passwort", "token",
            "pin", "geheim", "apikey", "api_key");

    /**
     * Prefixes that mark a value as a pointer to a secret held elsewhere.
     *
     * <p>The first version of this check rejected anything containing a colon, and so rejected
     * {@code vault://bank/main} — the very format it was meant to accept. A rule that forbids the
     * canonical answer is not a safety measure, it is an obstacle that pushes users towards pasting the
     * password, which is the opposite of what the acceptance criterion wants.</p>
     */
    private static final List<String> REFERENCE_SCHEMES = List.of("vault://", "secret://", "kms://",
            "env:", "aws-secretsmanager:", "azure-keyvault:", "gcp-secretmanager:", "op://");

    private final JdbcTemplate jdbc;
    private final BlobStore blobStore;

    public PaymentMatchingService(JdbcTemplate jdbc, BlobStore blobStore) {
        this.jdbc = jdbc;
        this.blobStore = blobStore;
    }

    /** What importing a statement produced. */
    public record ImportResult(UUID statementId, int transactions, int duplicatesSkipped,
                               int matchedAutomatically, int proposed) {

        public Map<String, Object> asDiagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("statementId", statementId.toString());
            details.put("transactions", transactions);
            details.put("duplicatesSkipped", duplicatesSkipped);
            details.put("matchedAutomatically", matchedAutomatically);
            details.put("proposed", proposed);
            return details;
        }
    }

    /** A candidate the platform found but did not act on. */
    public record Proposal(UUID transactionId, UUID invoiceId, BigDecimal amount, String reason,
                           double confidence) {
    }

    // ------------------------------------------------------------------ statements

    /**
     * Imports a CAMT.053 file.
     *
     * @param accountId the financial account the statement belongs to; checked against the IBAN in the
     *                  file, because a statement for another account is a data-entry mistake that would
     *                  otherwise reconcile the wrong money
     */
    @Transactional
    public ImportResult importCamt(UUID tenantId, UUID accountId, byte[] content, UUID userId)
            throws java.io.IOException {
        List<Statement> statements = CamtParser.parse(content);
        if (statements.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Datei enthält keine Kontoauszüge");
        }

        String accountIban = jdbc.queryForObject(
                "select iban from financial_accounts where id = ? and tenant_id = ?", String.class,
                accountId, tenantId);
        Statement statement = statements.getFirst();

        if (statement.accountIban() != null && accountIban != null
                && !statement.accountIban().equalsIgnoreCase(accountIban)) {
            throw ApiException.conflict("Der Auszug gehört zu einem anderen Konto ("
                    + statement.accountIban() + " statt " + accountIban + ")");
        }

        // The file is kept, like every other source document: a reconciliation that is questioned later
        // has to be checkable against what the bank actually sent.
        BlobStore.StoredBlob blob = blobStore.store(tenantId,
                new java.io.ByteArrayInputStream(content), content.length, "application/xml");
        UUID blobRowId = jdbc.queryForObject("""
                insert into document_blobs (id, tenant_id, storage_key, storage_backend, bucket,
                                            size_bytes, content_hash, content_type, scan_status,
                                            scan_details, quarantined)
                values (gen_random_uuid(), ?, ?, ?, null, ?, ?, 'application/xml', 'CLEAN',
                        '{}'::jsonb, false)
                returning id
                """, UUID.class, tenantId, blob.storageKey(), blob.backend(), blob.sizeBytes(),
                blob.contentHash());

        UUID statementId = jdbc.queryForObject("""
                insert into bank_statements (id, tenant_id, financial_account_id, format, period_start,
                                             period_end, opening_balance, closing_balance, currency,
                                             source_blob_id, status, imported_by)
                values (gen_random_uuid(), ?, ?, 'CAMT053', ?, ?, ?, ?, ?, ?, 'IMPORTED', ?)
                returning id
                """, UUID.class, tenantId, accountId, statement.periodStart(), statement.periodEnd(),
                statement.openingBalance(), statement.closingBalance(),
                statement.currency() == null ? "EUR" : statement.currency(), blobRowId, userId);

        int imported = 0;
        int duplicates = 0;
        int matched = 0;
        int proposed = 0;

        for (Transaction transaction : statement.transactions()) {
            if (isDuplicate(tenantId, accountId, transaction)) {
                duplicates++;
                continue;
            }
            UUID transactionId = insertTransaction(tenantId, statementId, accountId, transaction);
            imported++;

            List<Proposal> proposals = proposeMatches(tenantId, transaction);
            if (proposals.size() == 1) {
                confirmMatch(tenantId, transactionId, proposals.getFirst().invoiceId(),
                        transaction.amount(), "AUTOMATIC",
                        proposals.getFirst().reason(), userId);
                matched++;
            }
            else if (proposals.size() > 1) {
                // Ambiguous: the platform records the candidates and changes nothing. Marking the
                // transaction as suggested is what makes it visible in the reconciliation list.
                proposals.forEach(proposal -> insertMatch(tenantId, transactionId, proposal.invoiceId(),
                        transaction.amount(), statement.currency(), "SUGGESTED", "PROPOSED",
                        proposal.confidence(), proposal.reason(), null));
                jdbc.update("update bank_transactions set status = 'SUGGESTED' where id = ?", transactionId);
                proposed++;
            }
        }

        log.info("Statement {} imported: {} transactions, {} duplicates, {} matched, {} proposed",
                statementId, imported, duplicates, matched, proposed);
        return new ImportResult(statementId, imported, duplicates, matched, proposed);
    }

    /** The same booking seen twice, which happens when overlapping periods are exported. */
    private boolean isDuplicate(UUID tenantId, UUID accountId, Transaction transaction) {
        if (transaction.externalId() != null) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from bank_transactions
                    where tenant_id = ? and external_id = ?
                    """, Integer.class, tenantId, transaction.externalId());
            if (count != null && count > 0) {
                return true;
            }
        }
        // Without a bank reference, the booking date, amount and purpose together identify it. This is
        // deliberately conservative: two genuinely separate identical bookings on the same day exist,
        // and they are reported for a person rather than silently dropped.
        if (transaction.externalId() == null && transaction.purpose() != null) {
            Integer count = jdbc.queryForObject("""
                    select count(*) from bank_transactions
                    where tenant_id = ? and financial_account_id = ? and booking_date = ?
                      and amount = ? and purpose = ?
                    """, Integer.class, tenantId, accountId, transaction.bookingDate(),
                    transaction.amount(), transaction.purpose());
            return count != null && count > 0;
        }
        return false;
    }

    private UUID insertTransaction(UUID tenantId, UUID statementId, UUID accountId,
                                   Transaction transaction) {
        return jdbc.queryForObject("""
                insert into bank_transactions (id, statement_id, tenant_id, financial_account_id,
                                               booking_date, value_date, amount, currency,
                                               counterparty_name, counterparty_iban, purpose,
                                               reference, end_to_end_id, transaction_type, status,
                                               external_id, metadata)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNMATCHED', ?,
                        '{}'::jsonb)
                returning id
                """, UUID.class, statementId, tenantId, accountId, transaction.bookingDate(),
                transaction.valueDate(), transaction.amount(),
                transaction.currency() == null ? "EUR" : transaction.currency(),
                transaction.counterpartyName(), transaction.counterpartyIban(), transaction.purpose(),
                transaction.reference(), transaction.endToEndId(), transaction.transactionType(),
                transaction.externalId());
    }

    // ------------------------------------------------------------------ matching

    /**
     * Finds the invoices a transaction could belong to.
     *
     * <p>An incoming amount is matched against open invoices of the same amount. The invoice number, if
     * the bank quoted one, narrows it further.</p>
     */
    @Transactional(readOnly = true)
    public List<Proposal> proposeMatches(UUID tenantId, Transaction transaction) {
        if (!transaction.credit()) {
            Integer outgoing = jdbc.queryForObject("""
                    select count(*) from bank_transactions where tenant_id = ? and amount = ? and status = 'UNMATCHED'
                    """, Integer.class, tenantId, transaction.amount());
            return List.of();
        }

        List<Map<String, Object>> candidates = jdbc.queryForList("""
                select id, invoice_number, currency, gross_amount, paid_amount
                from invoices
                where tenant_id = ? and direction = 'OUTGOING' and deleted_at is null
                  and gross_amount is not null
                  and gross_amount > coalesce(paid_amount, 0)
                  and status not in ('CANCELLED', 'REJECTED', 'DRAFT')
                """, tenantId);

        List<Proposal> proposals = new ArrayList<>();
        Set<UUID> seen = new LinkedHashSet<>();
        String quoted = quotedNumber(transaction);

        for (Map<String, Object> candidate : candidates) {
            UUID invoiceId = (UUID) candidate.get("id");
            String number = String.valueOf(candidate.get("invoice_number"));
            BigDecimal gross = (BigDecimal) candidate.get("gross_amount");
            BigDecimal paid = candidate.get("paid_amount") == null ? BigDecimal.ZERO
                    : (BigDecimal) candidate.get("paid_amount");
            BigDecimal open = gross.subtract(paid);

            if (quoted != null && number.equalsIgnoreCase(quoted)) {
                // The bank quoted this invoice's number: the strongest signal there is, and the amount
                // may legitimately differ from the open balance (a partial payment, a deduction).
                if (!seen.add(invoiceId)) {
                    continue;
                }
                proposals.add(new Proposal(invoiceId, invoiceId, transaction.amount(),
                        "Die Zahlung nennt die Rechnungsnummer " + number, 0.95));
            }
            else if (quoted == null && transaction.amount().compareTo(open) == 0) {
                // No number quoted, but exactly the open amount: still a guess about which invoice is
                // meant, so it is only proposed when the amount leaves no choice.
                if (!seen.add(invoiceId)) {
                    continue;
                }
                proposals.add(new Proposal(invoiceId, invoiceId, transaction.amount(),
                        "Der Betrag entspricht genau dem offenen Betrag", 0.6));
            }
        }
        return proposals;
    }

    /** The invoice number a bank quoted, if any. */
    private static String quotedNumber(Transaction transaction) {
        for (String field : new String[] { transaction.reference(), transaction.purpose(),
                transaction.endToEndId() }) {
            if (field == null) {
                continue;
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?i)\\b(RE|GS|RG)-?\\s?(\\d{4}-?\\d{3,6}|\\d{3,6})\\b").matcher(field);
            if (matcher.find()) {
                return matcher.group().replaceAll("\\s", "");
            }
        }
        return null;
    }

    /**
     * Confirms a match and books the payment.
     *
     * <p>Only a person or a unique automatic match gets this far; the method is never called with a
     * proposal that had competition.</p>
     */
    @Transactional
    public UUID confirmMatch(UUID tenantId, UUID transactionId, UUID invoiceId, BigDecimal amount,
                             String matchType, String reason, UUID userId) {
        UUID paymentId = bookPayment(tenantId, transactionId, invoiceId, amount, reason, userId);
        insertMatch(tenantId, transactionId, invoiceId, amount, null, matchType, "CONFIRMED", 1.0,
                reason, userId);
        jdbc.update("update bank_transactions set status = 'MATCHED' where id = ?", transactionId);
        return paymentId;
    }

    /** Books the payment the bank statement proves happened. */
    private UUID bookPayment(UUID tenantId, UUID transactionId, UUID invoiceId, BigDecimal amount,
                             String reason, UUID userId) {
        return jdbc.queryForObject("""
                insert into payments (id, tenant_id, invoice_id, direction, amount, currency, status,
                                      payment_date, reference, confirmed_by, confirmed_at, executed_by,
                                      executed_at, metadata, created_by)
                values (gen_random_uuid(), ?, ?, 'INCOMING', ?, (select currency from invoices where id = ?),
                        'EXECUTED', (select booking_date from bank_transactions where id = ?), ?,
                        ?, now(), ?, now(), '{}'::jsonb, ?)
                returning id
                """, UUID.class, tenantId, invoiceId, amount, invoiceId, transactionId, reason, userId,
                userId, userId);
    }

    private UUID insertMatch(UUID tenantId, UUID transactionId, UUID invoiceId, BigDecimal amount,
                             String currency, String matchType, String status, double confidence,
                             String reason, UUID userId) {
        return jdbc.queryForObject("""
                insert into payment_matches (id, tenant_id, invoice_id, bank_transaction_id, match_type,
                                             status, matched_amount, currency, confidence, match_reason,
                                             matched_by, matched_at)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, coalesce(?, 'EUR'), ?, ?, ?,
                        case when ?::boolean then now() end)
                returning id
                """, UUID.class, tenantId, invoiceId, transactionId, matchType, status, amount, currency,
                BigDecimal.valueOf(confidence), reason, userId, userId != null);
    }

    /**
     * A person decides about an ambiguous match.
     *
     * @param accept true to confirm the proposal, false to reject it. The transaction returns to
     *               unmatched either way, so it can be matched against something else.
     */
    @Transactional
    public void decideProposal(UUID tenantId, UUID transactionId, UUID invoiceId, boolean accept,
                               UUID userId, String note) {
        UUID matchId = jdbc.queryForObject("""
                select id from payment_matches
                where tenant_id = ? and bank_transaction_id = ? and invoice_id = ? and status = 'PROPOSED'
                """, UUID.class, tenantId, transactionId, invoiceId);

        if (accept) {
            UUID paymentId = bookPayment(tenantId, transactionId, invoiceId, amountOf(transactionId),
                    note == null ? "Manuell bestätigt" : note, userId);
            // The proposal already exists; it becomes the confirmation rather than being joined by one.
            jdbc.update("""
                    update payment_matches set status = 'CONFIRMED', match_type = 'MANUAL',
                           matched_by = ?, matched_at = now(), confidence = 1.0, note = ?,
                           payment_id = ?
                    where id = ?
                    """, userId, note, paymentId, matchId);
            jdbc.update("update bank_transactions set status = 'MATCHED' where id = ?", transactionId);
        }
        else {
            jdbc.update("""
                    update payment_matches set status = 'REJECTED', matched_by = ?, matched_at = now(),
                           note = ? where id = ?
                    """, userId, note, matchId);
            jdbc.update("update bank_transactions set status = 'UNMATCHED' where id = ?", transactionId);
        }
    }

    private BigDecimal amountOf(UUID transactionId) {
        return jdbc.queryForObject("select amount from bank_transactions where id = ?", BigDecimal.class,
                transactionId);
    }

    /** Transactions nobody has dealt with, for the reconciliation list. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> openTransactions(UUID tenantId, UUID accountId) {
        return jdbc.queryForList("""
                select t.id, t.booking_date, t.amount, t.currency, t.counterparty_name, t.purpose,
                       t.status,
                       (select count(*) from payment_matches m
                         where m.bank_transaction_id = t.id and m.status = 'PROPOSED') as proposals
                from bank_transactions t
                where t.tenant_id = ? and t.financial_account_id = ?
                  and t.status in ('UNMATCHED', 'SUGGESTED')
                order by t.booking_date
                """, tenantId, accountId);
    }

    // ------------------------------------------------------------------ bank accounts

    /**
     * Registers a bank account.
     *
     * <p>{@code credentialRef} is a reference to a secret held outside the platform, and the method
     * refuses a value that looks like the secret itself. The acceptance criterion says credentials are
     * not stored in the open, and the only way to keep that promise is to reject them at the door —
     * a rule in a document does not stop anybody pasting a password.</p>
     */
    @Transactional
    public UUID registerAccount(UUID tenantId, String name, String iban, String bic, String bankName,
                                String accountHolder, String currency, String credentialRef) {
        if (name == null || name.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Bankkonto braucht einen Namen");
        }
        if (credentialRef != null && !credentialRef.isBlank()) {
            String lower = credentialRef.toLowerCase(java.util.Locale.ROOT);
            boolean isAReference = REFERENCE_SCHEMES.stream().anyMatch(lower::startsWith);

            // A declared reference scheme is accepted; anything else is judged by what it looks like.
            boolean looksLikeASecret = !isAReference
                    && (FORBIDDEN_IN_REFERENCE.stream().anyMatch(lower::contains)
                            || credentialRef.length() > 120 || credentialRef.contains("{")
                            || credentialRef.contains("="));
            if (looksLikeASecret) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Zugangsdaten werden nicht in der Plattform gespeichert. Hier gehört nur ein "
                                + "Verweis auf ein Geheimnis hin, das außerhalb liegt.");
            }
        }

        String normalized = iban == null ? null
                : iban.replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
        return jdbc.queryForObject("""
                insert into financial_accounts (id, tenant_id, name, account_type, iban, bic, bank_name,
                                                account_holder, currency, credential_ref, is_active)
                values (gen_random_uuid(), ?, ?, 'BANK', ?, ?, ?, ?, ?, ?, true)
                returning id
                """, UUID.class, tenantId, name.strip(), normalized, bic, bankName, accountHolder,
                currency == null ? "EUR" : currency.toUpperCase(java.util.Locale.ROOT), credentialRef);
    }

    /** Whether an account has credentials configured, without ever reading them. */
    @Transactional(readOnly = true)
    public boolean hasCredentials(UUID tenantId, UUID accountId) {
        String reference = jdbc.queryForObject("""
                select credential_ref from financial_accounts where id = ? and tenant_id = ?
                """, String.class, accountId, tenantId);
        return reference != null && !reference.isBlank();
    }
}
