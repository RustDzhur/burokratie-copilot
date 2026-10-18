package de.buerokratiecopilot.invoices.outgoing;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.invoices.InvoiceService;
import de.buerokratiecopilot.invoices.domain.Invoice;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Sends an invoice to the customer as a letter with the PDF attached (spec §14.2, §15.3).
 *
 * <p><strong>What this route does.</strong> Renders the invoice, keeps the file as a document of the account,
 * writes the draft with that document attached, releases it in the name of the person who clicked, and hands
 * it to the provider. Each of those is a step that existed separately and was reachable from nothing; the
 * invoice could be rendered, downloaded and e-mailed, and the customer never received it.</p>
 *
 * <p><strong>The click is the release.</strong> The product does not send a letter on its own, and this route
 * does not either: a person pressed "send", and the same person is recorded as the one who released it —
 * {@code approved_by} names them. There is no background path that reaches this code, and no parameter lets a
 * caller name somebody else as the approver.</p>
 *
 * <p><strong>No transaction around all of it.</strong> Archiving the file and writing the draft commit before
 * the handover is attempted, so a provider that is not configured or refuses the message leaves the invoice
 * file and the released draft in place instead of undoing the user's work. The handover itself records exactly
 * what happened — accepted by the provider, which is not the same as delivered.</p>
 */
@Service
public class InvoiceSendService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceSendService.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

    private final InvoiceService invoices;
    private final InvoicePdfService pdfs;
    private final InvoiceDocumentStore documents;
    private final OutboundMessageService outbound;
    private final JdbcTemplate jdbc;
    private final String fallbackSender;

    public InvoiceSendService(InvoiceService invoices, InvoicePdfService pdfs,
                              InvoiceDocumentStore documents, OutboundMessageService outbound,
                              JdbcTemplate jdbc,
                              @Value("${spring.mail.username:}") String fallbackSender) {
        this.invoices = invoices;
        this.pdfs = pdfs;
        this.documents = documents;
        this.outbound = outbound;
        this.jdbc = jdbc;
        this.fallbackSender = fallbackSender;
    }

    /** What the caller may decide; everything else is derived from the invoice. */
    public record Request(String to, String subject, String body, String idempotencyKey) {
    }

    /** What happened to the letter. The status is the provider's answer, not a delivery claim. */
    public record Sent(UUID invoiceId, UUID documentId, UUID messageId, String status,
                       String providerMessageId, String description, List<String> to, String subject,
                       String documentFilename) {
    }

    public Sent send(UUID invoiceId, UUID tenantId, UUID actor, Request request) {
        Invoice invoice = invoices.requireInvoice(invoiceId, tenantId);

        String to = firstNonBlank(request.to(), defaultRecipient(tenantId, invoice));
        if (to == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Diese Rechnung hat keinen Empfänger: für den Kontakt ist keine E-Mail-Adresse "
                            + "hinterlegt. Bitte eine Adresse angeben.");
        }
        String subject = firstNonBlank(request.subject(), defaultSubject(invoice));
        String body = firstNonBlank(request.body(), defaultBody(invoice));

        InvoicePdfService.Rendered rendered;
        try {
            rendered = pdfs.render(invoiceId, tenantId);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Die Rechnung konnte nicht als PDF erzeugt werden", ex);
        }

        // The customer's copy is a document of the account before it is an attachment: the same file can be
        // downloaded later, and the attachment is the id of something that exists.
        InvoiceDocumentStore.Stored document = documents.store(new InvoiceDocumentStore.Request(
                tenantId, actor, rendered.content(), rendered.filename(), "application/pdf", invoiceId,
                invoice.getInvoiceNumber(), "Erzeugt beim Versand der Rechnung"));

        UUID messageId = outbound.draft(new OutboundMessageService.DraftRequest(tenantId,
                invoice.getCaseId(), "EMAIL", subject, body, null, senderAddress(tenantId), List.of(to),
                List.of(), null, blankToNull(request.idempotencyKey()), actor),
                List.of(document.documentId()));

        outbound.approve(tenantId, messageId, actor, subject, body, null);
        OutboundMessageService.Handover handover = outbound.send(tenantId, messageId, actor);

        log.info("Invoice {} sent to {} as message {} ({}), document {}", invoice.getInvoiceNumber(), to,
                messageId, handover.status(), document.documentId());
        return new Sent(invoiceId, document.documentId(), messageId, handover.status().name(),
                handover.providerMessageId(), handover.description(), List.of(to), subject,
                document.filename());
    }

    /**
     * The address the customer writes to, when the caller did not name one: the contact of the invoice, else
     * the counterparty the invoice was recorded against.
     */
    private String defaultRecipient(UUID tenantId, Invoice invoice) {
        List<String> rows = jdbc.query("""
                select coalesce(c.email::text, s.email::text) as email
                from invoices i
                left join contacts c on c.id = i.contact_id and c.tenant_id = i.tenant_id
                                    and c.deleted_at is null
                left join suppliers s on s.id = i.supplier_id and s.tenant_id = i.tenant_id
                                     and s.deleted_at is null
                where i.id = ? and i.tenant_id = ?
                """, (rs, row) -> rs.getString("email"), invoice.getId(), tenantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The mailbox the account sends from, when one is connected; otherwise the configured sender. */
    private String senderAddress(UUID tenantId) {
        List<String> addresses = jdbc.query("""
                select email_address::text from mail_accounts
                where tenant_id = ? and deleted_at is null
                order by created_at
                limit 1
                """, (rs, row) -> rs.getString(1), tenantId);
        if (!addresses.isEmpty() && addresses.getFirst() != null) {
            return addresses.getFirst();
        }
        return blankToNull(fallbackSender);
    }

    private static String defaultSubject(Invoice invoice) {
        return invoice.getInvoiceNumber() == null ? "Ihre Rechnung"
                : "Rechnung " + invoice.getInvoiceNumber();
    }

    /**
     * The cover letter. Every value in it is read from the invoice; the text invents no tax notice, no bank
     * details and no payment term that the record does not carry. Amounts are written the way the recipient
     * of a German invoice reads them.
     */
    static String defaultBody(Invoice invoice) {
        StringBuilder body = new StringBuilder("Sehr geehrte Damen und Herren,\n\n");
        body.append("anbei erhalten Sie ");
        body.append(invoice.getInvoiceNumber() == null ? "unsere Rechnung"
                : "die Rechnung " + invoice.getInvoiceNumber());
        if (invoice.getGrossAmount() != null) {
            body.append(" über ").append(amount(invoice.getGrossAmount()));
            if (invoice.getCurrency() != null) {
                body.append(' ').append(invoice.getCurrency());
            }
        }
        if (invoice.getIssueDate() != null) {
            body.append(" vom ").append(DATE.format(invoice.getIssueDate()));
        }
        body.append('.');
        if (invoice.getDueDate() != null) {
            body.append(" Bitte zahlen Sie bis zum ").append(DATE.format(invoice.getDueDate()))
                    .append('.');
        }
        body.append("\n\nMit freundlichen Grüßen");
        return body.toString();
    }

    private static String amount(java.math.BigDecimal value) {
        java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(Locale.GERMANY);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        String value = blankToNull(preferred);
        return value == null ? blankToNull(fallback) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
