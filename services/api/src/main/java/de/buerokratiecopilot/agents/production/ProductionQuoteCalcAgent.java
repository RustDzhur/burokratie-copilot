package de.buerokratiecopilot.agents.production;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code production-quote-calc} — Fertigungskalkulation.
 *
 * <p>Calculates a quote from material cost, labour time and machine time plus overhead, discount and
 * margin. Every value is either an explicit input or an extracted document value; a missing value is
 * listed, never estimated. The output is an HTML calculation sheet stored as an artifact and a task
 * for commercial review.</p>
 *
 * <p>There is no PDF service for arbitrary quotes in this platform. The agent therefore produces an
 * HTML document and says so — it does not claim a PDF that does not exist.</p>
 */
@Component
public class ProductionQuoteCalcAgent implements Agent {

    private static final BigDecimal DEFAULT_OVERHEAD = new BigDecimal("12");
    private static final BigDecimal DEFAULT_MARGIN = new BigDecimal("10");
    private static final int MAX_LINES = 100;

    private final DocumentRepository documents;
    private final DocumentExtractionRepository extractions;
    private final AgentArtifactStore artifacts;
    private final AgentTaskFactory tasks;

    public ProductionQuoteCalcAgent(DocumentRepository documents,
                                    DocumentExtractionRepository extractions,
                                    AgentArtifactStore artifacts, AgentTaskFactory tasks) {
        this.documents = documents;
        this.extractions = extractions;
        this.artifacts = artifacts;
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "production-quote-calc";
    }

    public record Position(String label, BigDecimal amount, String basis) {
    }

    public record Calculation(BigDecimal material, BigDecimal labor, BigDecimal machine,
                              BigDecimal overhead, BigDecimal subtotal, BigDecimal discount,
                              BigDecimal net, BigDecimal margin, BigDecimal total,
                              BigDecimal unitPrice, List<Position> positions,
                              List<String> assumptions, List<String> missing) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID productId;
        try {
            productId = UUID.fromString(require(context.input("productId")));
        }
        catch (RuntimeException ex) {
            return AgentResult.skipped("productId (UUID) fehlt oder ist ungültig; ohne Produkt "
                    + "keine Kalkulation.");
        }
        BigDecimal quantity = decimal(context.input("quantity"));
        if (quantity == null || quantity.signum() <= 0) {
            return AgentResult.failed("quantity muss eine positive Zahl sein.");
        }
        BigDecimal material = decimal(context.input("materialCosts"));
        BigDecimal hourlyRate = decimal(context.input("hourlyRate"));
        BigDecimal machineRate = decimal(context.input("machineRate"));
        BigDecimal laborHours = decimal(context.input("laborHours"));
        BigDecimal machineHours = decimal(context.input("machineHours"));
        BigDecimal overheadPercent = decimal(context.input("overheadPercent"));
        BigDecimal marginPercent = decimal(context.input("marginPercent"));
        BigDecimal discountPercent = decimal(context.input("discountPercent"));
        if (overheadPercent == null) {
            overheadPercent = DEFAULT_OVERHEAD;
        }
        if (marginPercent == null) {
            marginPercent = DEFAULT_MARGIN;
        }
        if (discountPercent == null) {
            discountPercent = BigDecimal.ZERO;
        }
        if (overheadPercent.signum() < 0 || marginPercent.signum() < 0
                || marginPercent.compareTo(new BigDecimal("100")) >= 0
                || discountPercent.signum() < 0
                || discountPercent.compareTo(new BigDecimal("100")) > 0) {
            return AgentResult.failed("Die Prozentsätze sind ungültig (Gemeinkosten >= 0, Marge "
                    + "0–<100, Rabatt 0–100).");
        }

        Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(productId,
                context.tenantId()).orElse(null);
        Map<String, String> values = documentValues(document);
        if (material == null) {
            material = decimal(values.get("materialkosten"));
        }
        if (hourlyRate == null) {
            hourlyRate = decimal(values.get("stundensatz"));
        }
        if (machineRate == null) {
            machineRate = decimal(values.get("maschinensatz"));
        }
        if (laborHours == null) {
            laborHours = decimal(values.get("arbeitsstunden"));
        }
        if (machineHours == null) {
            machineHours = decimal(values.get("maschinenstunden"));
        }

        Calculation calculation = calculate(quantity, material, hourlyRate, laborHours, machineRate,
                machineHours, overheadPercent, marginPercent, discountPercent);
        if (calculation.positions().isEmpty()) {
            return AgentResult.skipped("Keine belastbaren Kostenwerte: materialCosts oder "
                    + "Stundensatz/Stunden fehlen. Fehlende Werte werden nicht geschätzt.");
        }

        String productName = document == null
                ? "Produkt " + productId
                : document.getOriginalFilename() + (document.getDocumentNumber() == null ? ""
                        : " (" + document.getDocumentNumber() + ")");
        String content = toHtml(productName, quantity, calculation, document);
        String sourceKey = "quote:" + productId + ":" + quantity.stripTrailingZeros().toPlainString()
                + ":" + hash(material + "|" + hourlyRate + "|" + laborHours + "|" + machineRate
                        + "|" + machineHours + "|" + overheadPercent + "|" + marginPercent + "|"
                        + discountPercent);
        AgentArtifactStore.Artifact artifact;
        try {
            artifact = artifacts.store(context.tenantId(), context.actorUserId(), sourceKey,
                    "Kalkulation " + productName, "kalkulation-" + productId + ".html",
                    "text/html", "HTML", content);
        }
        catch (RuntimeException ex) {
            return AgentResult.failed("Die Kalkulation konnte nicht gespeichert werden: "
                    + ex.getMessage());
        }

        String title = "Angebot prüfen: " + truncate(productName, 200);
        AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                context.actorUserId(), title,
                description(productName, quantity, calculation, artifact),
                Task.Priority.NORMAL,
                "Kalkulation ist ein Nachweis mit Annahmen; Preis und Rabatt müssen kommerziell "
                        + "geprüft und freigegeben werden.",
                context.now().plus(5, ChronoUnit.DAYS),
                List.of("production", "quote"));

        context.addProduced("quote-calculation", artifact.storageKey(),
                productName + ": " + calculation.total().toPlainString() + " EUR gesamt");
        context.addProduced("task", task.taskId().toString(), title);
        for (String assumption : calculation.assumptions()) {
            context.addProduced("quote-assumption", productId.toString(), assumption);
        }
        for (String missing : calculation.missing()) {
            context.addProduced("quote-missing", productId.toString(), missing);
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("productId", productId.toString());
        meta.put("productName", productName);
        meta.put("quantity", quantity.toPlainString());
        meta.put("material", calculation.material().toPlainString());
        meta.put("labor", calculation.labor().toPlainString());
        meta.put("machine", calculation.machine().toPlainString());
        meta.put("overhead", calculation.overhead().toPlainString());
        meta.put("discount", calculation.discount().toPlainString());
        meta.put("net", calculation.net().toPlainString());
        meta.put("margin", calculation.margin().toPlainString());
        meta.put("total", calculation.total().toPlainString());
        meta.put("unitPrice", calculation.unitPrice().toPlainString());
        meta.put("missing", String.valueOf(calculation.missing().size()));
        meta.put("storageKey", artifact.storageKey());
        meta.put("artifactCreated", String.valueOf(artifact.created()));
        meta.put("taskId", task.taskId().toString());
        meta.put("taskCreated", String.valueOf(task.created()));
        meta.put("limitation", "Kein PDF-Dienst für freie Angebote angebunden; das Ergebnis ist "
                + "ein HTML-Kalkulationsnachweis. Fehlende Werte werden nicht geschätzt.");

        return AgentResult.done("Kalkulation " + productName + ": " + calculation.total()
                        .toPlainString() + " EUR gesamt, Einzelpreis " + calculation.unitPrice()
                        .toPlainString() + " EUR. " + calculation.missing().size()
                        + " fehlende(r) Wert(e) ausgewiesen. Kein PDF, kein Versand.",
                context.produced(), meta);
    }

    /**
     * The arithmetic of the quote. Visible for tests.
     *
     * <p>{@code materialCosts} is the total material cost of the quoted quantity. Labour and machine
     * cost apply only when both a rate and a time are given; a rate without hours is reported as
     * missing instead of being multiplied by an invented number.</p>
     */
    public static Calculation calculate(BigDecimal quantity, BigDecimal material,
                                        BigDecimal hourlyRate, BigDecimal laborHours,
                                        BigDecimal machineRate, BigDecimal machineHours,
                                        BigDecimal overheadPercent, BigDecimal marginPercent,
                                        BigDecimal discountPercent) {
        List<String> assumptions = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<Position> positions = new ArrayList<>();
        BigDecimal zero = BigDecimal.ZERO;
        BigDecimal materialValue = material == null ? zero : material;
        if (material != null) {
            positions.add(new Position("Materialkosten (gesamt)", scale(material),
                    "Eingabe oder Dokumentwert"));
            assumptions.add("materialCosts ist die gesamte Materialkostenposition, kein "
                    + "Einzelpreis.");
        }
        else {
            missing.add("Materialkosten fehlen.");
        }

        BigDecimal labor = zero;
        if (hourlyRate != null && laborHours != null) {
            labor = scale(hourlyRate.multiply(laborHours));
            positions.add(new Position("Arbeitszeit", labor, hourlyRate.toPlainString()
                    + " EUR/h × " + laborHours.toPlainString() + " h"));
        }
        else if (hourlyRate != null || laborHours != null) {
            missing.add("Stundensatz und Arbeitsstunden sind nicht vollständig; die Arbeitszeit "
                    + "wird nicht geschätzt.");
        }

        BigDecimal machine = zero;
        if (machineRate != null && machineHours != null) {
            machine = scale(machineRate.multiply(machineHours));
            positions.add(new Position("Maschinenzeit", machine, machineRate.toPlainString()
                    + " EUR/h × " + machineHours.toPlainString() + " h"));
        }
        else if (machineRate != null || machineHours != null) {
            missing.add("Maschinensatz und Maschinenstunden sind nicht vollständig; die "
                    + "Maschinenzeit wird nicht geschätzt.");
        }

        if (positions.isEmpty()) {
            return new Calculation(scale(materialValue), scale(labor), scale(machine), zero, zero,
                    zero, zero, zero, zero, zero, List.of(), assumptions, missing);
        }

        BigDecimal direct = materialValue.add(labor).add(machine);
        BigDecimal overhead = scale(direct.multiply(overheadPercent)
                .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
        BigDecimal subtotal = direct.add(overhead);
        BigDecimal discount = scale(subtotal.multiply(discountPercent)
                .divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
        BigDecimal net = subtotal.subtract(discount);
        BigDecimal margin = scale(net.multiply(marginPercent)
                .divide(new BigDecimal("100").subtract(marginPercent), 6, RoundingMode.HALF_UP));
        BigDecimal total = net.add(margin);
        BigDecimal unitPrice = total.divide(quantity, 2, RoundingMode.HALF_UP);

        positions.add(new Position("Gemeinkosten " + overheadPercent.toPlainString() + " %",
                overhead, "auf direkte Kosten"));
        if (discount.signum() > 0) {
            positions.add(new Position("Rabatt " + discountPercent.toPlainString() + " %",
                    discount.negate(), "auf Zwischensumme"));
        }
        positions.add(new Position("Marge " + marginPercent.toPlainString() + " %", margin,
                "Bruttomarge vom Verkaufspreis"));
        assumptions.add("Die Marge ist als Bruttomarge vom Verkaufspreis gerechnet.");
        assumptions.add("Der Gesamtpreis ist auf zwei Nachkommastellen gerundet.");

        return new Calculation(scale(materialValue), scale(labor), scale(machine), overhead,
                scale(subtotal), discount, scale(net), margin, scale(total), unitPrice,
                List.copyOf(positions), List.copyOf(assumptions), List.copyOf(missing));
    }

    private Map<String, String> documentValues(Document document) {
        if (document == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (DocumentExtraction extraction : extractions.findByDocumentId(document.getId())) {
            String key = extraction.getFieldKey() == null ? ""
                    : extraction.getFieldKey().toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-zäöüß]", "");
            String value = extraction.getNormalizedValue() == null ? extraction.getFieldValue()
                    : extraction.getNormalizedValue();
            if (key.contains("material")) {
                values.putIfAbsent("materialkosten", value);
            }
            else if (key.contains("stundensatz") || key.contains("hourly")) {
                values.putIfAbsent("stundensatz", value);
            }
            else if (key.contains("maschinensatz")) {
                values.putIfAbsent("maschinensatz", value);
            }
            else if (key.contains("arbeitsstunden") || key.contains("laborhours")
                    || key.equals("stunden")) {
                values.putIfAbsent("arbeitsstunden", value);
            }
            else if (key.contains("maschinenstunden") || key.contains("machinehours")) {
                values.putIfAbsent("maschinenstunden", value);
            }
        }
        return values;
    }

    private static String toHtml(String productName, BigDecimal quantity, Calculation calculation,
                                 Document document) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><title>")
                .append(escape("Kalkulation " + productName)).append("</title></head><body>")
                .append("<h1>Kalkulation: ").append(escape(productName)).append("</h1>")
                .append("<p>Menge: ").append(quantity.toPlainString())
                .append(" | Quelle: ")
                .append(document == null ? "Eingabewerte" : "Dokument "
                        + escape(document.getOriginalFilename())).append("</p>")
                .append("<table border=\"1\" cellpadding=\"4\"><thead><tr><th>Position</th>")
                .append("<th>Betrag (EUR)</th><th>Basis</th></tr></thead><tbody>");
        for (Position position : calculation.positions()) {
            html.append("<tr><td>").append(escape(position.label())).append("</td><td>")
                    .append(position.amount().toPlainString()).append("</td><td>")
                    .append(escape(position.basis())).append("</td></tr>");
        }
        html.append("<tr><th>Gesamt</th><th>").append(calculation.total().toPlainString())
                .append("</th><th>Einzelpreis ").append(calculation.unitPrice().toPlainString())
                .append("</th></tr></tbody></table>");
        html.append("<h2>Annahmen</h2><ul>");
        for (String assumption : calculation.assumptions()) {
            html.append("<li>").append(escape(assumption)).append("</li>");
        }
        html.append("</ul><h2>Fehlende Werte</h2><ul>");
        if (calculation.missing().isEmpty()) {
            html.append("<li>keine</li>");
        }
        else {
            for (String missing : calculation.missing()) {
                html.append("<li>").append(escape(missing)).append("</li>");
            }
        }
        html.append("</ul><p>Entwurf – keine Freigabe, kein Versand.</p></body></html>");
        return html.toString();
    }

    private static String description(String productName, BigDecimal quantity,
                                      Calculation calculation,
                                      AgentArtifactStore.Artifact artifact) {
        StringBuilder text = new StringBuilder();
        text.append("Kalkulationsentwurf für ").append(productName)
                .append(" (Menge ").append(quantity.toPlainString()).append(")\n")
                .append("Gesamt: ").append(calculation.total().toPlainString()).append(" EUR\n")
                .append("Einzelpreis: ").append(calculation.unitPrice().toPlainString())
                .append(" EUR\n")
                .append("Ablage: ").append(artifact.storageKey()).append("\n\nPositionen:\n");
        for (Position position : calculation.positions().stream().limit(MAX_LINES).toList()) {
            text.append("- ").append(position.label()).append(": ")
                    .append(position.amount().toPlainString()).append(" EUR (")
                    .append(position.basis()).append(")\n");
        }
        if (!calculation.missing().isEmpty()) {
            text.append("\nFehlende Werte (nicht geschätzt):\n");
            for (String missing : calculation.missing()) {
                text.append("- ").append(missing).append('\n');
            }
        }
        text.append("\nBitte Preis, Rabatt und Annahmen prüfen und das Angebot freigeben. ")
                .append("Der Agent versendet nichts.");
        return text.toString();
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.strip().replace(',', '.'));
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing");
        }
        return value.strip();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value
                : value.substring(0, max - 3) + "...";
    }
}
