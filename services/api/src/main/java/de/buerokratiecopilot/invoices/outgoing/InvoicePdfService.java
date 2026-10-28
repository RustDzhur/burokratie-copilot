package de.buerokratiecopilot.invoices.outgoing;

import java.io.IOException;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.invoices.InvoiceService;
import de.buerokratiecopilot.invoices.domain.Invoice;

/**
 * The invoice as the document a customer receives (spec §14.2).
 *
 * <p><strong>One renderer, two callers.</strong> The download route and the send route both hand the
 * customer the same file; if each assembled it on its own, an amount could be printed one way by the
 * download and another way by the letter, and the difference would be found by the customer. The renderer
 * still computes nothing: everything it prints is read from the stored invoice.</p>
 */
@Service
public class InvoicePdfService {

    private final InvoiceService invoices;
    private final InvoicePdfRenderer renderer;
    private final JdbcTemplate jdbc;

    public InvoicePdfService(InvoiceService invoices, InvoicePdfRenderer renderer, JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.renderer = renderer;
        this.jdbc = jdbc;
    }

    /** The rendered file, with the name it should carry wherever it is stored or sent. */
    public record Rendered(String filename, byte[] content) {
    }

    @Transactional(readOnly = true)
    public Rendered render(UUID invoiceId, UUID tenantId) throws IOException {
        Invoice invoice = invoices.requireInvoice(invoiceId, tenantId);
        var lines = invoices.linesOf(invoiceId).stream()
                .map(line -> new InvoicePdfRenderer.RenderLine(line.getLineNumber(), line.getDescription(),
                        line.getQuantity(), line.getUnit(), line.getUnitPrice(), line.getNetAmount(),
                        line.getTaxRatePercent()))
                .toList();
        String seller = jdbc.query("select name from tenants where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, tenantId);
        String buyer = invoice.getContactId() == null ? null : jdbc.query("""
                select display_name from contacts where id = ? and tenant_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, invoice.getContactId(), tenantId);

        byte[] pdf = renderer.render(new InvoicePdfRenderer.Document(invoice, lines, seller, buyer));
        return new Rendered(filenameOf(invoice), pdf);
    }

    /** The name a downloaded or attached file carries; a file called "document" helps nobody. */
    public static String filenameOf(Invoice invoice) {
        String base = invoice.getInvoiceNumber() == null ? "Rechnung" : invoice.getInvoiceNumber();
        return base.replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";
    }
}
