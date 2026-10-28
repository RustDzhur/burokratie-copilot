package de.buerokratiecopilot.agents.logistics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * {@code logistics-order-intake} — Auftragserfassung.
 *
 * <p>Turns an incoming order text/JSON into a checked order record: positions, quantities, delivery
 * address and date are validated. A position without article or a non-positive quantity is not
 * guessed; the order is returned for clarification with a task and a protocol. A valid order
 * becomes a confirmation task and an order document.</p>
 */
@Component
public class OrderIntakeAgent implements Agent {

    private static final Pattern POSITION = Pattern.compile(
            "(?im)^\\s*(?:art(?:ikel)?\\.?\\s*)?([A-Z0-9][A-Z0-9._/-]{1,30})\\s*[x×*]\\s*(\\d+)\\b");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public OrderIntakeAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "logistics-order-intake";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String orderText = AgentInputs.clean(context.input("orderText"));
        String source = AgentInputs.blankTo(context.input("source"), "EMAIL").toUpperCase(Locale.ROOT);
        if (!List.of("EMAIL", "PORTAL", "FILE").contains(source)) {
            return AgentResult.failed("Auftragserfassung: unbekannte Quelle '" + source
                    + "' — erlaubt: EMAIL, PORTAL, FILE.");
        }
        List<Position> positions = positions(context.input("items"), orderText);
        if (positions.isEmpty() && orderText == null) {
            return AgentResult.skipped("Auftragserfassung: weder 'orderText' noch 'items' übergeben.");
        }
        UUID customerId = AgentInputs.uuid(context.input("customerId"));
        if (context.input("customerId") != null && !context.input("customerId").isBlank()
                && customerId == null) {
            return AgentResult.failed("Auftragserfassung: 'customerId' ist keine gültige UUID.");
        }
        String customerName = customerId == null ? null : customerName(context.tenantId(), customerId);
        if (customerId != null && customerName == null) {
            return AgentResult.skipped("Auftragserfassung: Kunde " + customerId
                    + " ist im Mandanten nicht vorhanden.");
        }
        LocalDate deliveryDate = AgentInputs.date(context.input("deliveryDate"));
        if (context.input("deliveryDate") != null && !context.input("deliveryDate").isBlank()
                && deliveryDate == null) {
            return AgentResult.failed("Auftragserfassung: 'deliveryDate' ist kein gültiges Datum.");
        }
        String orderNumber = AgentInputs.clean(context.input("orderNumber"));
        if (orderNumber == null) {
            orderNumber = extractOrderNumber(orderText);
        }
        if (orderNumber == null) {
            orderNumber = "INT-" + AgentInputs.key(orderText, context.input("items"))
                    .substring(0, 10).toUpperCase(Locale.ROOT);
        }
        List<String> problems = new ArrayList<>();
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        if (deliveryDate != null && deliveryDate.isBefore(today)) {
            problems.add("Liefertermin liegt in der Vergangenheit (" + deliveryDate + ")");
        }
        if (positions.isEmpty()) {
            problems.add("keine Bestellposition erkannt (Artikel + Menge fehlen)");
        }
        for (Position position : positions) {
            if (position.article() == null || position.article().isBlank()) {
                problems.add("Position ohne Artikelnummer");
            }
            if (position.quantity() <= 0) {
                problems.add("Position " + position.article() + ": Menge nicht positiv");
            }
        }
        String sourceKey = "logistics-order-intake:" + source + ":" + orderNumber;
        String html = html(orderNumber, source, customerName, deliveryDate, positions, problems);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Auftragserfassung " + orderNumber, html,
                Map.of("orderNumber", orderNumber, "source", source,
                        "positions", positions.size(), "problems", problems.size()),
                problems.isEmpty() ? List.of() : List.of("orderPositions"));
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Auftragserfassung " + orderNumber);
        }
        if (!problems.isEmpty()) {
            String title = "Bestellung klären: " + orderNumber;
            AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":clarify", title,
                    "Quelle: " + source + "\nKunde: " + (customerName == null ? "unbekannt" : customerName)
                            + "\nProbleme:\n- " + String.join("\n- ", problems)
                            + "\n\nDer Agent schätzt keine Positionen. Bestellung mit dem Kunden "
                            + "klären und erneut erfassen.",
                    Task.Priority.HIGH, context.now().plus(Duration.ofDays(1)),
                    List.of("logistics", "order", "clarification"),
                    Map.of("orderNumber", orderNumber, "problems", problems.size()));
            if (task.created()) {
                context.addProduced("task", task.id().toString(), title);
            }
            return new AgentResult(false, "Auftragserfassung " + orderNumber
                    + ": Bestellung unvollständig — " + String.join("; ", problems)
                    + ". Zur Klärung zurückgegeben.", context.produced(),
                    Map.of("reason", "ORDER_INCOMPLETE", "orderNumber", orderNumber,
                            "problems", String.valueOf(problems.size())));
        }
        BigDecimal total = positions.stream().map(Position::lineTotal)
                .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        boolean totalComplete = positions.stream().allMatch(p -> p.unitPrice() != null);
        String title = "Auftrag bestätigen: " + orderNumber;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":confirm", title,
                "Bestellung " + orderNumber + " (" + source + ", Kunde "
                        + (customerName == null ? customerId == null ? "unbekannt" : customerId.toString()
                                : customerName) + ")"
                        + "\nPositionen: " + positions.size()
                        + "\nWarenwert: " + (totalComplete ? total.toPlainString() : "unvollständig")
                        + "\nLiefertermin: " + (deliveryDate == null ? "offen" : deliveryDate)
                        + "\n\nAuftrag im ERP bestätigen und Lieferung planen. Der Agent legt keinen "
                        + "Auftrag im ERP an.",
                Task.Priority.NORMAL,
                deliveryDate == null ? context.now().plus(Duration.ofDays(1))
                        : deliveryDate.minusDays(2).atStartOfDay(AgentInputs.BERLIN).toInstant(),
                List.of("logistics", "order"), Map.of("orderNumber", orderNumber,
                        "source", source, "positions", positions.size()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("orderNumber", orderNumber);
        meta.put("source", source);
        meta.put("positions", String.valueOf(positions.size()));
        meta.put("total", totalComplete ? total.toPlainString() : "incomplete");
        meta.put("deliveryDate", deliveryDate == null ? "" : deliveryDate.toString());
        return AgentResult.done("Auftrag " + orderNumber + " erfasst: " + positions.size()
                + " Position(en), Liefertermin " + (deliveryDate == null ? "offen" : deliveryDate)
                + "; Bestätigung als Aufgabe angelegt.", context.produced(), meta);
    }

    private List<Position> positions(String rawItems, String orderText) {
        List<Position> positions = new ArrayList<>();
        if (rawItems != null && !rawItems.isBlank()) {
            try {
                for (Map<String, Object> row : mapper.readValue(rawItems,
                        new TypeReference<List<Map<String, Object>>>() {
                        })) {
                    String article = text(row.get("article"), row.get("sku"), row.get("description"));
                    Integer quantity = row.get("quantity") instanceof Number number
                            ? number.intValue() : AgentInputs.integer(text(row.get("quantity")));
                    BigDecimal unitPrice = decimal(row.get("unitPrice"), row.get("price"));
                    positions.add(new Position(article, quantity == null ? 0 : quantity, unitPrice,
                            text(row.get("description"))));
                }
            }
            catch (Exception ex) {
                return List.of();
            }
            return List.copyOf(positions);
        }
        if (orderText != null) {
            Matcher matcher = POSITION.matcher(orderText);
            while (matcher.find()) {
                positions.add(new Position(matcher.group(1), Integer.parseInt(matcher.group(2)),
                        null, null));
            }
        }
        return List.copyOf(positions);
    }

    static String extractOrderNumber(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?i)(?:bestell(?:ung|nummer)|order|auftrag)"
                + "(?:\\s*(?:nr\\.?|nummer))?[:\\s#-]*([A-Z0-9][A-Z0-9-]{2,30})").matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String customerName(UUID tenantId, UUID customerId) {
        List<String> rows = jdbc.query("""
                select display_name from contacts
                where tenant_id = ? and id = ? and deleted_at is null
                """, (rs, row) -> rs.getString(1), tenantId, customerId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static String html(String orderNumber, String source, String customer,
                               LocalDate deliveryDate, List<Position> positions,
                               List<String> problems) {
        StringBuilder html = new StringBuilder("<h1>Auftragserfassung ").append(AgentInputs.html(orderNumber))
                .append("</h1><p>Quelle: ").append(source).append("<br>Kunde: ")
                .append(AgentInputs.html(customer == null ? "unbekannt" : customer))
                .append("<br>Liefertermin: ").append(deliveryDate == null ? "offen" : deliveryDate)
                .append("</p><table><tr><th>Artikel</th><th>Beschreibung</th><th>Menge</th>"
                        + "<th>Einzelpreis</th><th>Summe</th></tr>");
        for (Position position : positions) {
            html.append("<tr><td>").append(AgentInputs.html(position.article())).append("</td><td>")
                    .append(AgentInputs.html(position.description())).append("</td><td>")
                    .append(position.quantity()).append("</td><td>")
                    .append(position.unitPrice() == null ? "offen" : position.unitPrice()).append("</td><td>")
                    .append(position.lineTotal() == null ? "offen" : position.lineTotal()).append("</td></tr>");
        }
        html.append("</table>");
        if (!problems.isEmpty()) {
            html.append("<h2>Zur Klärung</h2><ul>");
            for (String problem : problems) {
                html.append("<li>").append(AgentInputs.html(problem)).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<p><em>Unklare Positionen werden nicht geschätzt. Ein ERP-Auftrag wird durch "
                + "dieses Dokument nicht angelegt.</em></p>");
        return html.toString();
    }

    private static String text(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).strip();
            }
        }
        return null;
    }

    private static BigDecimal decimal(Object... values) {
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            try {
                return new BigDecimal(String.valueOf(value).replace(",", "."));
            }
            catch (NumberFormatException ignored) {
                // next
            }
        }
        return null;
    }

    /** One order position. */
    public record Position(String article, int quantity, BigDecimal unitPrice, String description) {

        public BigDecimal lineTotal() {
            return unitPrice == null ? null : unitPrice.multiply(BigDecimal.valueOf(quantity));
        }
    }
}
