package de.buerokratiecopilot.agents.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
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
 * {@code sales-offer-draft} — Angebotserstellung.
 *
 * <p>Builds an offer from the contact, the supplied positions, discount and validity: net, discount
 * (capped at the configured maximum) and gross where a tax rate is supplied. Missing data is listed
 * as an open point, never filled with a guessed price. The offer is a generated document and an
 * approval task; the agent sends nothing.</p>
 */
@Component
public class OfferDraftAgent implements Agent {

    static final BigDecimal MAX_DISCOUNT = new BigDecimal("15");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public OfferDraftAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "sales-offer-draft";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID contactId = AgentInputs.uuid(context.input("contactId"));
        if (contactId == null) {
            return AgentResult.skipped("Angebotserstellung: 'contactId' fehlt oder ist keine gültige UUID.");
        }
        List<String> contacts = jdbc.query("""
                select display_name from contacts
                where tenant_id = ? and id = ? and deleted_at is null
                """, (rs, row) -> rs.getString(1), context.tenantId(), contactId);
        if (contacts.isEmpty()) {
            return AgentResult.skipped("Angebotserstellung: Kontakt " + contactId
                    + " ist im Mandanten nicht vorhanden.");
        }
        String customer = contacts.getFirst();
        String rawItems = context.input("items");
        if (rawItems == null || rawItems.isBlank()) {
            return AgentResult.skipped("Angebotserstellung: 'items' fehlt — ohne Positionen und "
                    + "Preise wäre das Angebot erfunden.");
        }
        List<Map<String, Object>> itemRows;
        try {
            itemRows = mapper.readValue(rawItems, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            return AgentResult.failed("Angebotserstellung: 'items' ist kein gültiges JSON-Array.");
        }
        List<Item> items = new ArrayList<>();
        List<String> openPoints = new ArrayList<>();
        for (Map<String, Object> row : itemRows) {
            String description = text(row.get("description"), row.get("name"), row.get("article"));
            BigDecimal quantity = decimal(row.get("quantity"));
            BigDecimal unitPrice = decimal(row.get("unitPrice"), row.get("price"));
            if (description == null) {
                openPoints.add("Position ohne Beschreibung");
                continue;
            }
            if (quantity == null || quantity.signum() <= 0) {
                openPoints.add(description + ": Menge fehlt oder ist nicht positiv");
                continue;
            }
            if (unitPrice == null || unitPrice.signum() < 0) {
                openPoints.add(description + ": Preis fehlt");
                continue;
            }
            items.add(new Item(description, quantity, unitPrice));
        }
        if (items.isEmpty()) {
            return new AgentResult(false, "Angebotserstellung: keine kalkulierbare Position — "
                    + String.join("; ", openPoints), List.of(),
                    Map.of("reason", "NO_PRICED_POSITIONS"));
        }
        BigDecimal discount = AgentInputs.decimal(context.input("discountPercent"));
        if (discount == null) {
            discount = BigDecimal.ZERO;
        }
        if (discount.signum() < 0 || discount.compareTo(MAX_DISCOUNT) > 0) {
            return AgentResult.failed("Angebotserstellung: Rabatt " + discount
                    + " % liegt außerhalb von 0–" + MAX_DISCOUNT + " %.");
        }
        BigDecimal taxRate = AgentInputs.decimal(context.input("taxRatePercent"));
        if (taxRate != null && (taxRate.signum() < 0 || taxRate.compareTo(new BigDecimal("30")) > 0)) {
            return AgentResult.failed("Angebotserstellung: 'taxRatePercent' ist unplausibel.");
        }
        LocalDate validUntil = AgentInputs.date(context.input("validUntil"));
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        if (validUntil == null) {
            validUntil = today.plusDays(14);
        }
        if (validUntil.isBefore(today)) {
            return AgentResult.failed("Angebotserstellung: 'validUntil' liegt in der Vergangenheit.");
        }
        String paymentTerms = AgentInputs.blankTo(context.input("paymentTerms"), "14 Tage netto");
        UUID dealId = AgentInputs.uuid(context.input("dealId"));
        if (dealId != null && !caseExists(context.tenantId(), dealId)) {
            dealId = null;
        }
        Offer offer = calculate(items, discount, taxRate);
        String sourceKey = "sales-offer-draft:" + contactId + ":" + (dealId == null ? "" : dealId)
                + ":" + AgentInputs.key(items.toString(), discount.toPlainString(),
                        validUntil.toString());
        String html = html(customer, items, offer, discount, taxRate, validUntil, paymentTerms,
                openPoints);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, dealId, contactId,
                "Angebot " + customer, html,
                Map.of("contactId", contactId.toString(), "net", offer.net().toPlainString(),
                        "discount", discount.toPlainString(), "validUntil", validUntil.toString()),
                openPoints.isEmpty() ? List.of() : List.of("openPoints"));
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Angebotsentwurf " + customer);
        }
        String title = "Angebot freigeben: " + customer;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":approval", title,
                "Kunde: " + customer + " (" + contactId + ")"
                        + (dealId == null ? "" : "\nDeal: " + dealId)
                        + "\nPositionen: " + items.size()
                        + "\nNetto: " + offer.net() + " EUR"
                        + "\nRabatt: " + discount + " %"
                        + "\nGesamt: " + (offer.gross() == null ? "offen (Steuersatz fehlt)"
                                : offer.gross() + " EUR")
                        + "\nGültig bis: " + validUntil
                        + "\nZahlungsbedingungen: " + paymentTerms
                        + "\n\nDer Agent versendet nichts; Angebot prüfen und freigeben.",
                Task.Priority.NORMAL,
                validUntil.minusDays(2).atTime(9, 0).atZone(AgentInputs.BERLIN).toInstant(),
                List.of("sales", "offer"),
                Map.of("contactId", contactId.toString(), "net", offer.net().toPlainString(),
                        "validUntil", validUntil.toString()));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("contactId", contactId.toString());
        meta.put("positions", String.valueOf(items.size()));
        meta.put("net", offer.net().toPlainString());
        meta.put("discountPercent", discount.toPlainString());
        meta.put("gross", offer.gross() == null ? "" : offer.gross().toPlainString());
        meta.put("validUntil", validUntil.toString());
        meta.put("sent", "false");
        return AgentResult.done("Angebotsentwurf für " + customer + " erstellt: " + items.size()
                + " Position(en), netto " + offer.net() + " EUR, Rabatt " + discount
                + " %; nichts versendet.", context.produced(), meta);
    }

    private boolean caseExists(UUID tenantId, UUID caseId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from cases
                where tenant_id = ? and id = ? and deleted_at is null
                """, Integer.class, tenantId, caseId);
        return count != null && count > 0;
    }

    /** Calculates the offer; pure and testable. */
    public static Offer calculate(List<Item> items, BigDecimal discount, BigDecimal taxRate) {
        BigDecimal net = items.stream().map(item -> item.unitPrice()
                        .multiply(item.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal discounted = net.multiply(BigDecimal.ONE.subtract(
                discount.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross = taxRate == null ? null
                : discounted.multiply(BigDecimal.ONE.add(
                        taxRate.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP)))
                        .setScale(2, RoundingMode.HALF_UP);
        return new Offer(net, discount, discounted, gross, taxRate);
    }

    private static String html(String customer, List<Item> items, Offer offer, BigDecimal discount,
                               BigDecimal taxRate, LocalDate validUntil, String paymentTerms,
                               List<String> openPoints) {
        StringBuilder html = new StringBuilder("<h1>Angebotsentwurf für ")
                .append(AgentInputs.html(customer)).append("</h1><p>Gültig bis: ")
                .append(validUntil).append("<br>Zahlungsbedingungen: ")
                .append(AgentInputs.html(paymentTerms)).append("<br>Rabatt: ").append(discount)
                .append(" %</p><table><tr><th>Position</th><th>Menge</th><th>Einzelpreis</th>"
                        + "<th>Summe</th></tr>");
        for (Item item : items) {
            html.append("<tr><td>").append(AgentInputs.html(item.description())).append("</td><td>")
                    .append(item.quantity()).append("</td><td>").append(item.unitPrice())
                    .append("</td><td>").append(item.unitPrice().multiply(item.quantity()))
                    .append("</td></tr>");
        }
        html.append("</table><p><strong>Netto:</strong> ").append(offer.net())
                .append(" EUR<br><strong>Nach Rabatt:</strong> ").append(offer.discounted())
                .append(" EUR<br><strong>Brutto:</strong> ")
                .append(offer.gross() == null ? "offen (Steuersatz fehlt)" : offer.gross() + " EUR")
                .append("</p>");
        if (!openPoints.isEmpty()) {
            html.append("<h2>Offene Punkte</h2><ul>");
            for (String point : openPoints) {
                html.append("<li>").append(AgentInputs.html(point)).append("</li>");
            }
            html.append("</ul>");
        }
        html.append("<p><em>Entwurf. Der Agent versendet nichts und erfindet keine fehlenden "
                + "Preise; Versand erst nach Freigabe.</em></p>");
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

    /** One offer position. */
    public record Item(String description, BigDecimal quantity, BigDecimal unitPrice) {
    }

    /** The calculated offer. */
    public record Offer(BigDecimal net, BigDecimal discountPercent, BigDecimal discounted,
                        BigDecimal gross, BigDecimal taxRatePercent) {
    }
}
