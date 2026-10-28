package de.buerokratiecopilot.agents.banking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.approvals.PaymentApprovalService;
import de.buerokratiecopilot.contacts.identifiers.IdentifierKind;
import de.buerokratiecopilot.contacts.identifiers.IdentifierNormalizer;
import de.buerokratiecopilot.invoices.InvoiceRepository;
import de.buerokratiecopilot.invoices.SupplierRepository;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.invoices.domain.Supplier;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code banking-payment-order} — Zahlungsaufträge.
 *
 * <p>Collects approved incoming invoices, checks payee, IBAN, open amount and purpose, and prepares
 * one payment with the platform's own {@link PaymentApprovalService}. The prepared payment is
 * {@code AWAITING_APPROVAL}: the agent never talks to a bank, never confirms an approval and never
 * marks a payment as executed.</p>
 *
 * <p><strong>A changed account blocks the payment.</strong> When the invoice names an IBAN and the
 * supplier record holds a different one, the invoice is not prepared. The same is true when the
 * invoice already carries the bank-details-changed flag. Both are the classic invoice-fraud vector,
 * and a block plus a review task is the only responsible answer.</p>
 *
 * <p>Idempotency comes from the payment service: it keys a payment by invoice, amount and currency,
 * so a repeated run returns the already prepared payment instead of a second one.</p>
 */
@Component
public class BankingPaymentOrderAgent implements Agent {

    private static final int MAX_REFERENCE = 200;
    private static final int MAX_REASON_IN_TASK = 20;

    private final InvoiceRepository invoices;
    private final SupplierRepository suppliers;
    private final PaymentApprovalService payments;
    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;

    public BankingPaymentOrderAgent(InvoiceRepository invoices, SupplierRepository suppliers,
                                    PaymentApprovalService payments, JdbcTemplate jdbc,
                                    AgentTaskFactory tasks) {
        this.invoices = invoices;
        this.suppliers = suppliers;
        this.payments = payments;
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "banking-payment-order";
    }

    /** One invoice that could not be turned into a payment, with the reason. */
    public record Blocked(String invoiceId, String invoiceNumber, String reason) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<UUID> invoiceIds = parseIds(context.input("invoiceIds"));
        if (invoiceIds.isEmpty()) {
            return AgentResult.skipped("Keine invoiceIds angegeben; es gibt nichts für den "
                    + "Zahlungsauftrag vorzubereiten.");
        }
        LocalDate executionDate;
        try {
            executionDate = LocalDate.parse(require(context.input("executionDate")));
        }
        catch (RuntimeException ex) {
            return AgentResult.failed("Ein gültiges Ausführungsdatum (executionDate) ist "
                    + "erforderlich.");
        }
        UUID bankAccountId;
        try {
            bankAccountId = UUID.fromString(require(context.input("bankAccountId")));
        }
        catch (RuntimeException ex) {
            return AgentResult.failed("Eine gültige bankAccountId ist erforderlich.");
        }
        Integer accountCount = jdbc.queryForObject("""
                select count(*) from financial_accounts
                where id = ? and tenant_id = ? and deleted_at is null and is_active
                """, Integer.class, bankAccountId, context.tenantId());
        if (accountCount == null || accountCount == 0) {
            return AgentResult.failed("Das Bankkonto " + bankAccountId + " wurde nicht gefunden "
                    + "oder ist nicht aktiv.");
        }

        String batchName = blankTo(context.input("batchName"),
                "Zahlungslauf " + executionDate);
        List<AgentResult.Produced> produced = new ArrayList<>();
        List<Blocked> blocked = new ArrayList<>();
        List<String> preparedNumbers = new ArrayList<>();
        int skipped = 0;

        for (UUID invoiceId : invoiceIds) {
            Invoice invoice = invoices.findByIdAndTenantIdAndDeletedAtIsNull(invoiceId,
                    context.tenantId()).orElse(null);
            if (invoice == null) {
                blocked.add(new Blocked(invoiceId.toString(), null,
                        "Rechnung nicht gefunden oder gehört einem anderen Tenant"));
                continue;
            }
            String number = invoice.getInvoiceNumber() == null ? invoiceId.toString()
                    : invoice.getInvoiceNumber();
            if (invoice.getStatus() == Invoice.Status.PAID
                    || invoice.getStatus() == Invoice.Status.CANCELLED
                    || invoice.getStatus() == Invoice.Status.CREDITED) {
                skipped++;
                continue;
            }
            if (invoice.getDirection() != Invoice.Direction.INCOMING) {
                blocked.add(new Blocked(invoiceId.toString(), number,
                        "keine Eingangsrechnung (direction=" + invoice.getDirection() + ")"));
                continue;
            }
            if (invoice.getStatus() != Invoice.Status.APPROVED || invoice.getApprovedAt() == null) {
                blocked.add(new Blocked(invoiceId.toString(), number,
                        "Rechnung ist nicht freigegeben (Status " + invoice.getStatus() + ")"));
                continue;
            }
            BigDecimal amount = openAmount(invoice);
            if (amount.signum() <= 0) {
                blocked.add(new Blocked(invoiceId.toString(), number,
                        "kein offener positiver Betrag"));
                continue;
            }
            Supplier supplier = invoice.getSupplierId() == null ? null
                    : suppliers.findByIdAndTenantId(invoice.getSupplierId(), context.tenantId())
                            .orElse(null);
            if (supplier == null) {
                blocked.add(new Blocked(invoiceId.toString(), number,
                        "kein Lieferant zur Rechnung gefunden"));
                continue;
            }
            String ibanFromInvoice = normalizeIban(invoice.getIban());
            String knownIban = normalizeIban(supplier.knownIban());
            if (ibanFromInvoice == null) {
                blocked.add(new Blocked(invoiceId.toString(), number,
                        "keine IBAN in Rechnung oder Lieferantenstamm"));
                continue;
            }
            if (!IdentifierNormalizer.isValidIban(ibanFromInvoice)) {
                blocked.add(new Blocked(invoiceId.toString(), number,
                        "IBAN " + ibanFromInvoice + " ist ungültig"));
                continue;
            }
            if (invoice.isBankDetailsChanged()
                    || (knownIban != null && !knownIban.equals(ibanFromInvoice))) {
                blocked.add(new Blocked(invoiceId.toString(), number,
                        "Bankverbindung weicht von der geprüften Verbindung des Lieferanten ab"
                                + " (Freigabe erforderlich)"));
                continue;
            }

            String reference = truncate((batchName + " / " + number).strip(), MAX_REFERENCE);
            PaymentApprovalService.Prepared prepared = payments.prepare(
                    new PaymentApprovalService.PrepareRequest(context.tenantId(), invoice.getId(),
                            invoice.getCaseId(), amount, invoice.getCurrency(), supplier.getName(),
                            ibanFromInvoice, reference, executionDate),
                    context.actorUserId());
            preparedNumbers.add(number);
            produced.add(new AgentResult.Produced("payment", prepared.payment().getId().toString(),
                    "Zahlung " + number + " über " + amount.toPlainString() + " "
                            + invoice.getCurrency() + " vorbereitet (wartet auf Freigabe)"));
        }

        UUID reviewTaskId = null;
        boolean taskCreated = false;
        if (!blocked.isEmpty()) {
            String description = reviewDescription(blocked);
            AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                    context.actorUserId(), "Zahlungsaufträge prüfen: " + batchName, description,
                    Task.Priority.HIGH,
                    "Mindestens eine Rechnung konnte nicht automatisch vorbereitet werden; "
                            + "die Zahlung ist gesperrt, bis ein Mensch entschieden hat.",
                    context.now().plus(2, ChronoUnit.DAYS),
                    List.of("banking", "payment", "review"));
            reviewTaskId = task.taskId();
            taskCreated = task.created();
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("requested", String.valueOf(invoiceIds.size()));
        meta.put("prepared", String.valueOf(preparedNumbers.size()));
        meta.put("blocked", String.valueOf(blocked.size()));
        meta.put("alreadyPaid", String.valueOf(skipped));
        meta.put("batchName", batchName);
        meta.put("executionDate", executionDate.toString());
        meta.put("bankAccountId", bankAccountId.toString());
        meta.put("reviewTaskId", reviewTaskId == null ? "" : reviewTaskId.toString());
        meta.put("reviewTaskCreated", String.valueOf(taskCreated));
        meta.put("note", "Es wurde kein Zahlungsauftrag an eine Bank übermittelt; "
                + "vorbereitete Zahlungen warten auf die Freigabe im Plattform-Workflow.");
        meta.put("limitation", "Je Rechnung wird eine Zahlung vorbereitet; ein "
                + "Bank-Sammelauftrag wird nicht erzeugt und nichts an eine Bank gesendet.");

        if (!blocked.isEmpty()) {
            for (Blocked item : blocked) {
                produced.add(new AgentResult.Produced("payment-blocked", item.invoiceId(),
                        (item.invoiceNumber() == null ? item.invoiceId() : item.invoiceNumber())
                                + ": " + item.reason()));
            }
        }

        if (preparedNumbers.isEmpty()) {
            return new AgentResult(false,
                    "Kein Zahlungsauftrag vorbereitet: " + blocked.size() + " Rechnung(en) "
                            + "gesperrt oder nicht verwendbar"
                            + (skipped > 0 ? ", " + skipped + " bereits erledigt" : "") + ".",
                    produced, meta);
        }
        String summary = "Zahlungsauftrag '" + batchName + "': " + preparedNumbers.size()
                + " Zahlung(en) vorbereitet, " + blocked.size() + " gesperrt"
                + (skipped > 0 ? ", " + skipped + " bereits erledigt" : "")
                + ". Nichts an eine Bank übermittelt; Freigabe steht aus.";
        return AgentResult.done(summary, produced, meta);
    }

    private static BigDecimal openAmount(Invoice invoice) {
        BigDecimal gross = invoice.getGrossAmount();
        if (gross == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal paid = invoice.getPaidAmount() == null ? BigDecimal.ZERO : invoice.getPaidAmount();
        return gross.subtract(paid);
    }

    private static String normalizeIban(String raw) {
        String normalized = IdentifierNormalizer.normalize(IdentifierKind.IBAN, raw);
        return normalized == null || normalized.isBlank() ? null : normalized;
    }

    private static List<UUID> parseIds(String raw) {
        List<UUID> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            try {
                ids.add(UUID.fromString(part.strip()));
            }
            catch (IllegalArgumentException ex) {
                // An unusable id is reported by the caller as missing; it does not abort the batch.
            }
        }
        return ids;
    }

    private static String reviewDescription(List<Blocked> blocked) {
        StringBuilder text = new StringBuilder(
                "Der Agent konnte folgende Rechnungen nicht als Zahlungsauftrag vorbereiten. "
                        + "Die Zahlungen sind gesperrt, bis die Punkte geprüft sind.\n\n");
        for (Blocked item : blocked.stream().limit(MAX_REASON_IN_TASK).toList()) {
            text.append("- ").append(item.invoiceNumber() == null ? item.invoiceId()
                    : item.invoiceNumber()).append(": ").append(item.reason()).append('\n');
        }
        if (blocked.size() > MAX_REASON_IN_TASK) {
            text.append("- … und ").append(blocked.size() - MAX_REASON_IN_TASK).append(" weitere\n");
        }
        text.append("\nEs wurde nichts an eine Bank gesendet.");
        return text.toString();
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing");
        }
        return value.strip();
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value
                : value.substring(0, max - 3) + "...";
    }
}
