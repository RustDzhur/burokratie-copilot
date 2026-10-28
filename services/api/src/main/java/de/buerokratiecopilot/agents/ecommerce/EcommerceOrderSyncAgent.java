package de.buerokratiecopilot.agents.ecommerce;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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
 * {@code ecommerce-order-sync} — Bestell-Synchronisation.
 *
 * <p>The pilot has no shop API. The agent therefore imports an order batch that a connector (or a
 * human) supplies as JSON, validates what fulfillment needs — external number, payment state,
 * delivery address, line items — and files one fulfillment task per order. Missing or contradictory
 * order data is not guessed: it becomes a clarification task and an honest non-success.</p>
 *
 * <p>Orders are identified by channel + external order number, so replaying the same batch creates
 * no second task and no second generated document.</p>
 */
@Component
public class EcommerceOrderSyncAgent implements Agent {

    private static final int MAX_ORDERS = 200;
    private static final List<String> KNOWN_PAYMENT_STATES = List.of("PAID", "PENDING", "FAILED",
            "REFUNDED", "AUTHORIZED", "CANCELLED");

    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public EcommerceOrderSyncAgent(ObjectMapper mapper, AgentWorkspace workspace) {
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "ecommerce-order-sync";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String raw = context.input("orders");
        if (raw == null || raw.isBlank()) {
            return AgentResult.skipped("Bestell-Synchronisation: keine Bestelldaten übergeben — "
                    + "ohne Shop-/Marktplatz-Anbindung oder 'orders'-JSON gibt es nichts zu importieren.");
        }
        List<Map<String, Object>> rawOrders;
        try {
            rawOrders = mapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            return AgentResult.failed("Bestell-Synchronisation: 'orders' ist kein gültiges JSON-Array.");
        }
        if (rawOrders.isEmpty()) {
            return AgentResult.skipped("Bestell-Synchronisation: die Bestellliste ist leer.");
        }
        String channel = AgentInputs.blankTo(context.input("channel"), "SHOP").toUpperCase(Locale.ROOT);
        boolean includeCancelled = AgentInputs.bool(context.input("includeCancelled"), false);
        List<UUID> filterIds = AgentInputs.uuidList(context.input("orderIds"));

        List<ImportedOrder> valid = new ArrayList<>();
        List<Map<String, Object>> unclear = new ArrayList<>();
        int cancelled = 0;
        int filtered = 0;
        for (int index = 0; index < rawOrders.size() && index < MAX_ORDERS; index++) {
            Map<String, Object> rawOrder = rawOrders.get(index);
            String number = text(rawOrder, "externalNumber", "orderNumber", "number", "id");
            if (filterIds.isEmpty() || matchesFilter(number, filterIds)) {
                if (number == null) {
                    unclear.add(Map.of("row", index + 1, "problem", "externe Bestellnummer fehlt"));
                    continue;
                }
                boolean isCancelled = bool(rawOrder.get("cancelled"), false)
                        || "CANCELLED".equalsIgnoreCase(text(rawOrder, "status"));
                if (isCancelled && !includeCancelled) {
                    cancelled++;
                    continue;
                }
                List<String> problems = new ArrayList<>();
                String paymentStatus = text(rawOrder, "paymentStatus", "payment", "payment_state");
                if (paymentStatus == null) {
                    problems.add("Zahlungsstatus fehlt");
                }
                else if (!KNOWN_PAYMENT_STATES.contains(paymentStatus.toUpperCase(Locale.ROOT))) {
                    problems.add("unbekannter Zahlungsstatus '" + paymentStatus + "'");
                }
                String address = address(rawOrder.get("deliveryAddress"));
                if (address == null) {
                    problems.add("Lieferadresse fehlt");
                }
                List<Map<String, Object>> items = items(rawOrder.get("items"));
                if (items.isEmpty()) {
                    problems.add("keine Bestellpositionen");
                }
                ImportedOrder order = new ImportedOrder(number.strip(), channel,
                        text(rawOrder, "customerName", "customer", "customerEmail"),
                        paymentStatus == null ? null : paymentStatus.toUpperCase(Locale.ROOT),
                        address, decimal(rawOrder.get("total"), rawOrder.get("totalAmount")),
                        isCancelled, items, problems);
                if (problems.isEmpty()) {
                    valid.add(order);
                }
                else {
                    unclear.add(Map.of("order", order.externalNumber(), "problems", problems));
                }
            }
            else {
                filtered++;
            }
        }

        List<AgentResult.Produced> produced = new ArrayList<>();
        int created = 0;
        int existing = 0;
        for (ImportedOrder order : valid) {
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    "ecommerce-order-sync:" + order.channel() + ":" + order.externalNumber(),
                    "Fulfillment: " + order.externalNumber(),
                    "Bestellung " + order.externalNumber() + " / Kanal " + order.channel()
                            + "\nKunde: " + nvl(order.customer())
                            + "\nZahlungsstatus: " + order.paymentStatus()
                            + "\nLieferadresse: " + order.deliveryAddress()
                            + "\nPositionen: " + order.items().size()
                            + "\n\nVerfügbarkeit ist nicht geprüft (keine Lagerdaten im Pilot); "
                            + "vor dem Versand prüfen.",
                    Task.Priority.NORMAL, context.now().plus(Duration.ofDays(1)),
                    List.of("ecommerce", "fulfillment", order.channel().toLowerCase(Locale.ROOT)),
                    Map.of("externalOrderNumber", order.externalNumber(), "channel", order.channel(),
                            "paymentStatus", order.paymentStatus() == null ? "" : order.paymentStatus()));
            if (task.created()) {
                created++;
                produced.add(new AgentResult.Produced("task", task.id().toString(),
                        "Fulfillment " + order.externalNumber()));
            }
            else {
                existing++;
            }
        }

        if (!unclear.isEmpty()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("unclearOrders", unclear.size());
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    "ecommerce-order-sync:clarify:" + AgentInputs.key(serialize(unclear)),
                    "Bestellimport klären (" + unclear.size() + ")",
                    "Diese Bestellungen konnten nicht importiert werden:\n" + unclear,
                    Task.Priority.HIGH, context.now().plus(Duration.ofDays(1)),
                    List.of("ecommerce", "clarification"), metadata);
            if (task.created()) {
                produced.add(new AgentResult.Produced("task", task.id().toString(),
                        "Bestellklärungen"));
            }
        }

        if (!valid.isEmpty() || !unclear.isEmpty()) {
            String html = reportHtml(channel, valid, unclear, cancelled, filtered);
            AgentWorkspace.DocumentRef document = workspace.ensureDocument(context,
                    "ecommerce-order-sync:" + AgentInputs.key(serialize(valid), serialize(unclear)),
                    null, null, "Bestellimport " + channel, html,
                    Map.of("channel", channel, "valid", valid.size(), "unclear", unclear.size()),
                    unclear.isEmpty() ? List.of() : List.of("unclearOrders"));
            if (document.created()) {
                produced.add(new AgentResult.Produced("generated_document", document.id().toString(),
                        "Bestellimport " + channel));
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("channel", channel);
        meta.put("ordersInBatch", String.valueOf(rawOrders.size()));
        meta.put("fulfillmentTasksCreated", String.valueOf(created));
        meta.put("fulfillmentTasksExisting", String.valueOf(existing));
        meta.put("unclear", String.valueOf(unclear.size()));
        meta.put("cancelledSkipped", String.valueOf(cancelled));
        meta.put("filteredOut", String.valueOf(filtered));
        if (valid.isEmpty() && unclear.isEmpty()) {
            return AgentResult.skipped("Bestell-Synchronisation: keine importierbare Bestellung im "
                    + "Batch (" + cancelled + " storniert, " + filtered + " gefiltert).");
        }
        if (valid.isEmpty()) {
            return new AgentResult(false, "Bestell-Synchronisation: keine Bestellung war "
                    + "vollständig — " + unclear.size() + " zur Klärung zurückgegeben.", produced, meta);
        }
        return AgentResult.done("Bestell-Synchronisation " + channel + ": " + valid.size()
                + " Bestellung(en) geprüft, " + created + " Fulfillment-Aufgabe(n) angelegt, "
                + unclear.size() + " zur Klärung zurückgegeben.", produced, meta);
    }

    private static boolean matchesFilter(String number, List<UUID> ids) {
        if (number == null) {
            return false;
        }
        return ids.stream().anyMatch(id -> id.toString().equalsIgnoreCase(number));
    }

    private static String reportHtml(String channel, List<ImportedOrder> valid,
                                     List<Map<String, Object>> unclear, int cancelled, int filtered) {
        StringBuilder html = new StringBuilder("<h1>Bestellimport ").append(AgentInputs.html(channel))
                .append("</h1><p>Gültig: ").append(valid.size()).append(", zur Klärung: ")
                .append(unclear.size()).append(", storniert übersprungen: ").append(cancelled)
                .append(", gefiltert: ").append(filtered).append("</p>");
        html.append("<table><tr><th>Bestellung</th><th>Kunde</th><th>Zahlung</th>"
                + "<th>Lieferadresse</th><th>Positionen</th></tr>");
        for (ImportedOrder order : valid) {
            html.append("<tr><td>").append(AgentInputs.html(order.externalNumber())).append("</td><td>")
                    .append(AgentInputs.html(nvl(order.customer()))).append("</td><td>")
                    .append(AgentInputs.html(nvl(order.paymentStatus()))).append("</td><td>")
                    .append(AgentInputs.html(order.deliveryAddress())).append("</td><td>")
                    .append(order.items().size()).append("</td></tr>");
        }
        html.append("</table>");
        if (!unclear.isEmpty()) {
            html.append("<h2>Zur Klärung</h2><ul>");
            for (Map<String, Object> item : unclear) {
                html.append("<li>").append(AgentInputs.html(String.valueOf(item))).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<p>Hinweis: Der Import prüft keine Lagerverfügbarkeit und ruft keine "
                + "Shop-Schnittstelle auf; die Bestelldaten stammen aus der übergebenen Liste.</p>");
        return html.toString();
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private static List<Map<String, Object>> items(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> converted = new LinkedHashMap<>();
                map.forEach((key, value) -> converted.put(String.valueOf(key), value));
                items.add(converted);
            }
        }
        return List.copyOf(items);
    }

    private static String address(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            for (String key : List.of("street", "houseNumber", "postalCode", "city", "country")) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    parts.add(String.valueOf(value));
                }
            }
            return parts.isEmpty() ? null : String.join(" ", parts);
        }
        String text = String.valueOf(raw).strip();
        return text.isEmpty() ? null : text;
    }

    private static String text(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
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
                // try the next spelling
            }
        }
        return null;
    }

    private static boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String nvl(String value) {
        return value == null ? "—" : value;
    }

    private record ImportedOrder(String externalNumber, String channel, String customer,
                                 String paymentStatus, String deliveryAddress, BigDecimal total,
                                 boolean cancelled, List<Map<String, Object>> items,
                                 List<String> problems) {
    }
}
