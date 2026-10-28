package de.buerokratiecopilot.agents.production;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code production-warranty} — Garantiefall.
 *
 * <p>Separates statutory warranty (Gewährleistung, measured from purchase) from goodwill, finds the
 * invoice or purchase order when available, and prepares a decision task and a case document. It
 * never orders a repair and does not promise a replacement.</p>
 */
@Component
public class WarrantyAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public WarrantyAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "production-warranty";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String orderId = AgentInputs.clean(context.input("orderId"));
        LocalDate purchaseDate = AgentInputs.date(context.input("purchaseDate"));
        String defect = AgentInputs.clean(context.input("defectDescription"));
        if (orderId == null || purchaseDate == null || defect == null) {
            return AgentResult.skipped("Garantiefall: 'orderId', 'purchaseDate' und "
                    + "'defectDescription' sind erforderlich.");
        }
        UUID productId = AgentInputs.uuid(context.input("productId"));
        int warrantyMonths = AgentInputs.integer(context.input("warrantyMonths"), 24, 1, 120);
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        if (purchaseDate.isAfter(today)) {
            return AgentResult.failed("Garantiefall: das Kaufdatum " + purchaseDate + " liegt in der "
                    + "Zukunft.");
        }
        LocalDate warrantyEnd = purchaseDate.plusMonths(warrantyMonths);
        Status status = status(purchaseDate, today, warrantyMonths);
        Map<String, Object> receipt = findReceipt(context.tenantId(), orderId);
        String receiptNote = receipt == null ? "kein Beleg im Mandanten gefunden"
                : "Beleg " + receipt.get("reference") + " vom " + receipt.get("date");
        String sourceKey = "production-warranty:" + orderId + ":"
                + (productId == null ? "" : productId);
        String html = "<h1>Garantiefall " + AgentInputs.html(orderId) + "</h1>"
                + "<p><strong>Kaufdatum:</strong> " + purchaseDate
                + "<br><strong>Garantiezeit:</strong> " + warrantyMonths + " Monate bis " + warrantyEnd
                + "<br><strong>Status:</strong> " + status
                + "<br><strong>Beleg:</strong> " + AgentInputs.html(receiptNote)
                + "<br><strong>Produkt:</strong> " + (productId == null ? "nicht benannt" : productId)
                + "</p><h2>Mangel</h2><p>" + AgentInputs.html(defect) + "</p>"
                + "<h2>Einordnung</h2><p>" + status.explanation + "</p>"
                + "<p><em>Der Agent veranlasst keine Reparatur und macht keine Zusage. "
                + "Gewährleistung und Kulanz werden getrennt ausgewiesen; die Entscheidung trifft "
                + "eine verantwortliche Person.</em></p>";
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Garantieprüfung " + orderId, html,
                Map.of("orderId", orderId, "purchaseDate", purchaseDate.toString(),
                        "warrantyEnd", warrantyEnd.toString(), "status", status.name()), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Garantieprüfung " + orderId);
        }
        Task.Priority priority = status == Status.IN_WARRANTY ? Task.Priority.HIGH
                : Task.Priority.NORMAL;
        String title = (status == Status.IN_WARRANTY ? "Garantiefall bearbeiten: "
                : "Kulanz entscheiden: ") + orderId;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":decision", title,
                "Auftrag: " + orderId + "\nProdukt: " + (productId == null ? "nicht benannt" : productId)
                        + "\nKaufdatum: " + purchaseDate + "\nGarantie bis: " + warrantyEnd
                        + "\nStatus: " + status + " — " + status.explanation
                        + "\nMangel: " + defect
                        + "\nBeleg: " + receiptNote
                        + "\n\nKeine Reparatur beauftragt; Entscheidung und Zusage durch eine "
                        + "verantwortliche Person.",
                priority, context.now().plus(Duration.ofDays(status == Status.IN_WARRANTY ? 2 : 5)),
                List.of("production", "warranty"),
                Map.of("orderId", orderId, "status", status.name(),
                        "warrantyEnd", warrantyEnd.toString()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("orderId", orderId);
        meta.put("status", status.name());
        meta.put("warrantyEnd", warrantyEnd.toString());
        meta.put("purchaseDate", purchaseDate.toString());
        meta.put("warrantyMonths", String.valueOf(warrantyMonths));
        meta.put("repairOrdered", "false");
        return AgentResult.done("Garantiefall " + orderId + " geprüft: " + status
                + " (Garantie bis " + warrantyEnd + "); Entscheidung als Aufgabe vorbereitet, "
                + "keine Reparatur beauftragt.", context.produced(), meta);
    }

    /** Warranty status by date; pure and testable. */
    public static Status status(LocalDate purchaseDate, LocalDate today, int warrantyMonths) {
        LocalDate end = purchaseDate.plusMonths(warrantyMonths);
        if (!today.isAfter(end)) {
            return Status.IN_WARRANTY;
        }
        long monthsSinceEnd = ChronoUnit.MONTHS.between(YearMonth.from(end), YearMonth.from(today));
        return monthsSinceEnd <= 6 ? Status.GOODWILL : Status.EXPIRED;
    }

    private Map<String, Object> findReceipt(UUID tenantId, String orderId) {
        List<Map<String, Object>> invoices = jdbc.queryForList("""
                select invoice_number as reference, issue_date as date, gross_amount
                from invoices
                where tenant_id = ? and deleted_at is null and invoice_number = ?
                order by issue_date desc limit 1
                """, tenantId, orderId);
        if (!invoices.isEmpty()) {
            return invoices.getFirst();
        }
        List<Map<String, Object>> orders = jdbc.queryForList("""
                select po_number as reference, order_date as date, gross_amount
                from purchase_orders
                where tenant_id = ? and deleted_at is null and po_number = ?
                order by order_date desc limit 1
                """, tenantId, orderId);
        return orders.isEmpty() ? null : orders.getFirst();
    }

    /** How the case reads. */
    public enum Status {
        IN_WARRANTY("innerhalb der Garantie/Gewährleistung"),
        GOODWILL("außerhalb der Garantie, Kulanzzeitraum bis 6 Monate"),
        EXPIRED("Garantie und Kulanzzeitraum abgelaufen");

        private final String explanation;

        Status(String explanation) {
            this.explanation = explanation;
        }
    }
}
