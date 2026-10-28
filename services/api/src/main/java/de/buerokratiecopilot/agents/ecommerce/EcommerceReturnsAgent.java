package de.buerokratiecopilot.agents.ecommerce;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code ecommerce-returns} — Retourenbearbeitung.
 *
 * <p>Checks a return announcement against the actual outgoing invoice (the platform's only durable
 * order record), the return window, the stated reason and the item values. If the window has passed
 * or an item value is missing, the agent returns an honest non-success and files a review task
 * instead of inventing a refund amount. A refund is prepared as a task, never executed.</p>
 */
@Component
public class EcommerceReturnsAgent implements Agent {

    static final int DEFAULT_RETURN_WINDOW_DAYS = 14;
    private static final List<String> REFUND_METHODS = List.of("ORIGINAL", "CREDIT", "EXCHANGE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public EcommerceReturnsAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "ecommerce-returns";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String orderId = AgentInputs.clean(context.input("orderId"));
        if (orderId == null) {
            return AgentResult.skipped("Retourenbearbeitung: 'orderId' fehlt.");
        }
        String rawItems = context.input("returnItems");
        if (rawItems == null || rawItems.isBlank()) {
            return AgentResult.skipped("Retourenbearbeitung: 'returnItems' fehlt — ohne "
                    + "Retourpositionen gibt es nichts zu prüfen.");
        }
        List<Map<String, Object>> items;
        try {
            items = mapper.readValue(rawItems, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            return AgentResult.failed("Retourenbearbeitung: 'returnItems' ist kein gültiges JSON-Array.");
        }
        if (items.isEmpty()) {
            return AgentResult.skipped("Retourenbearbeitung: die Retourliste ist leer.");
        }
        String refundMethod = AgentInputs.blankTo(context.input("refundMethod"), "ORIGINAL")
                .toUpperCase(Locale.ROOT);
        if (!REFUND_METHODS.contains(refundMethod)) {
            return AgentResult.failed("Retourenbearbeitung: unbekannte Erstattungsart '"
                    + refundMethod + "'.");
        }
        int windowDays = AgentInputs.integer(context.input("returnWindowDays"),
                DEFAULT_RETURN_WINDOW_DAYS, 1, 365);

        Map<String, Object> invoice = findInvoice(context.tenantId(), orderId);
        if (invoice == null) {
            return AgentResult.skipped("Retourenbearbeitung: zur Bestellung/Rechnung '" + orderId
                    + "' gibt es keine Ausgangsrechnung im Mandanten — eine Fristprüfung ohne "
                    + "Beleg wäre geraten.");
        }
        UUID invoiceId = (UUID) invoice.get("id");
        String invoiceNumber = String.valueOf(invoice.get("invoice_number"));
        LocalDate issueDate = AgentInputs.date(invoice.get("issue_date"));
        BigDecimal invoiceGross = invoice.get("gross_amount") == null ? BigDecimal.ZERO
                : (BigDecimal) invoice.get("gross_amount");
        String returnKey = "ecommerce-returns:" + invoiceId + ":"
                + AgentInputs.key(orderId, rawItems, String.valueOf(context.input("reason")));

        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        long daysSinceIssue = issueDate == null ? -1 : ChronoUnit.DAYS.between(issueDate, today);
        boolean windowExceeded = issueDate == null || daysSinceIssue > windowDays;

        BigDecimal refund = BigDecimal.ZERO;
        List<String> openPoints = new ArrayList<>();
        List<Map<String, Object>> positions = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String description = item.get("description") == null
                    ? (item.get("article") == null ? null : String.valueOf(item.get("article")))
                    : String.valueOf(item.get("description"));
            Integer quantity = intValue(item.get("quantity"));
            BigDecimal unitPrice = decimal(item.get("unitPrice"));
            if (description == null || description.isBlank()) {
                openPoints.add("Position ohne Beschreibung");
                continue;
            }
            if (quantity == null || quantity <= 0) {
                openPoints.add(description + ": Menge fehlt oder ist nicht positiv");
                continue;
            }
            if (unitPrice == null) {
                openPoints.add(description + ": Einzelpreis fehlt — Erstattungsbetrag nicht berechenbar");
            }
            else {
                refund = refund.add(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            }
            positions.add(Map.of("description", description, "quantity", quantity,
                    "unitPrice", unitPrice == null ? "offen" : unitPrice.toPlainString()));
        }
        refund = refund.setScale(2, java.math.RoundingMode.HALF_UP);
        if (positions.isEmpty()) {
            return new AgentResult(false, "Retourenbearbeitung: keine prüfbare Retourposition — "
                    + String.join("; ", openPoints), List.of(),
                    Map.of("reason", "NO_VALID_POSITIONS"));
        }
        if (refund.compareTo(invoiceGross) > 0) {
            return AgentResult.failed("Retourenbearbeitung: die Erstattungssumme " + refund
                    + " übersteigt den Rechnungsbetrag " + invoiceGross + ".");
        }
        if (windowExceeded) {
            String title = "Retoure prüfen (Frist): " + invoiceNumber;
            AgentWorkspace.TaskRef task = workspace.ensureTask(context, returnKey + ":window", title,
                    "Retoure zur Rechnung " + invoiceNumber + ". Rechnungsdatum: " + issueDate
                            + ", Rückgabefenster: " + windowDays + " Tage, eingegangen nach "
                            + (daysSinceIssue < 0 ? "unbekannt" : daysSinceIssue + " Tagen")
                            + ".\nGrund: " + AgentInputs.blankTo(context.input("reason"), "ohne Angabe")
                            + "\n\nDie Frist ist überschritten; Kulanz oder Ablehnung entscheidet "
                            + "eine Person. Der Agent erstattet nichts.",
                    Task.Priority.HIGH, context.now().plus(Duration.ofDays(2)),
                    List.of("ecommerce", "returns", "window"), Map.of("invoiceId", invoiceId.toString()));
            if (task.created()) {
                context.addProduced("task", task.id().toString(), title);
            }
            return new AgentResult(false, "Retourenbearbeitung: Rückgabefenster von " + windowDays
                    + " Tagen überschritten (Rechnungsdatum " + issueDate
                    + ") — Kulanzentscheidung erforderlich, keine Erstattung vorbereitet.",
                    context.produced(), Map.of("reason", "RETURN_WINDOW_EXCEEDED",
                            "invoiceId", invoiceId.toString()));
        }
        if (!openPoints.isEmpty()) {
            String title = "Retoure klären: " + invoiceNumber;
            AgentWorkspace.TaskRef task = workspace.ensureTask(context, returnKey + ":clarify", title,
                    "Retoure zur Rechnung " + invoiceNumber + " ist nicht vollständig prüfbar:\n- "
                            + String.join("\n- ", openPoints)
                            + "\n\nDer Agent erstattet nichts, solange der Betrag offen ist.",
                    Task.Priority.HIGH, context.now().plus(Duration.ofDays(1)),
                    List.of("ecommerce", "returns", "clarification"),
                    Map.of("invoiceId", invoiceId.toString()));
            if (task.created()) {
                context.addProduced("task", task.id().toString(), title);
            }
            return new AgentResult(false, "Retourenbearbeitung: Erstattungsbetrag nicht berechenbar — "
                    + String.join("; ", openPoints), context.produced(),
                    Map.of("reason", "REFUND_AMOUNT_OPEN", "invoiceId", invoiceId.toString()));
        }

        String html = "<h1>Retourenbearbeitung " + AgentInputs.html(invoiceNumber) + "</h1>"
                + "<p>Bestellung/Rechnung: " + AgentInputs.html(orderId) + "<br>Rechnungsdatum: "
                + issueDate + "<br>Grund: "
                + AgentInputs.html(AgentInputs.blankTo(context.input("reason"), "ohne Angabe"))
                + "<br>Erstattungsart: " + refundMethod + "</p>"
                + "<table><tr><th>Position</th><th>Menge</th><th>Einzelpreis</th></tr>"
                + positions.stream().map(p -> "<tr><td>" + AgentInputs.html(String.valueOf(p.get("description")))
                        + "</td><td>" + p.get("quantity") + "</td><td>" + p.get("unitPrice")
                        + "</td></tr>").reduce("", String::concat)
                + "</table><p><strong>Vorbereiteter Erstattungsbetrag: " + refund + " "
                + (invoice.get("currency") == null ? "EUR" : invoice.get("currency"))
                + "</strong> — nicht ausgeführt. Erstattung erst nach Freigabe; der Lagerstatus "
                + "wird erst nach Wareneingang fortgeschrieben.</p>";
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, returnKey, null, null,
                "Retoure " + invoiceNumber, html,
                Map.of("invoiceId", invoiceId.toString(), "refund", refund.toPlainString(),
                        "refundMethod", refundMethod), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Retourenprotokoll " + invoiceNumber);
        }
        String title = "Erstattung freigeben: " + invoiceNumber + " / " + refund;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, returnKey + ":refund", title,
                "Retoure zur Rechnung " + invoiceNumber + " geprüft. Vorbereitete Erstattung: "
                        + refund + " " + (invoice.get("currency") == null ? "EUR" : invoice.get("currency"))
                        + " (" + refundMethod + ").\nDer Agent hat nichts erstattet und nichts "
                        + "umgetauscht; Freigabe durch eine Person.",
                Task.Priority.NORMAL, context.now().plus(Duration.ofDays(2)),
                List.of("ecommerce", "returns", "refund"),
                Map.of("invoiceId", invoiceId.toString(), "refund", refund.toPlainString()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("invoiceId", invoiceId.toString());
        meta.put("refund", refund.toPlainString());
        meta.put("refundMethod", refundMethod);
        meta.put("positions", String.valueOf(positions.size()));
        meta.put("refundExecuted", "false");
        return AgentResult.done("Retoure zur Rechnung " + invoiceNumber + " geprüft: " + refund
                + " zur Erstattung vorbereitet (" + refundMethod
                + "); keine Erstattung ausgeführt.", context.produced(), meta);
    }

    private Map<String, Object> findInvoice(UUID tenantId, String orderId) {
        List<Map<String, Object>> byNumber = jdbc.queryForList("""
                select id, invoice_number, issue_date, gross_amount, currency
                from invoices
                where tenant_id = ? and direction = 'OUTGOING' and deleted_at is null
                  and invoice_number = ?
                order by issue_date desc limit 1
                """, tenantId, orderId);
        if (!byNumber.isEmpty()) {
            return byNumber.getFirst();
        }
        UUID id = AgentInputs.uuid(orderId);
        if (id == null) {
            return null;
        }
        List<Map<String, Object>> byId = jdbc.queryForList("""
                select id, invoice_number, issue_date, gross_amount, currency
                from invoices
                where tenant_id = ? and direction = 'OUTGOING' and deleted_at is null and id = ?
                """, tenantId, id);
        return byId.isEmpty() ? null : byId.getFirst();
    }

    private static Integer intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return AgentInputs.integer(value == null ? null : String.valueOf(value));
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return AgentInputs.decimal(value == null ? null : String.valueOf(value));
    }
}
