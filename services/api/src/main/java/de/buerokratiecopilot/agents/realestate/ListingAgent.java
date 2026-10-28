package de.buerokratiecopilot.agents.realestate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code realestate-listing} — Objekt-Exposé.
 *
 * <p>Builds an exposé from the property data the caller supplies: description, equipment, location,
 * energy class and price. The platform has no property master data, so the caller's values are the
 * only source — and missing mandatory details are listed as gaps rather than filled with plausible
 * text. A prepared exposé is not a published exposé: the artifact is stored as a draft and a release
 * task is created.</p>
 */
@Component
public class ListingAgent implements Agent {

    static final String ARTIFACT_KIND = "realestate-listing";
    static final BigDecimal MAX_PRICE = new BigDecimal("100000000");

    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public ListingAgent(AgentTaskFactory tasks, AgentArtifactStore artifacts) {
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "realestate-listing";
    }

    /** The data an exposé needs, as read from the inputs. */
    public record Listing(String propertyId, String address, BigDecimal livingArea, BigDecimal rooms,
                          String energyClass, BigDecimal price, String highlights) {

        public String label() {
            return address == null || address.isBlank() ? propertyId : address;
        }
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<String> missing = new ArrayList<>();
        String propertyId = AgentInputs.text(context.input("propertyId"));
        if (propertyId == null) {
            missing.add("propertyId");
        }
        BigDecimal livingArea = positive(AgentInputs.decimal(context.input("livingArea")));
        if (livingArea == null) {
            missing.add("livingArea");
        }
        BigDecimal rooms = positive(AgentInputs.decimal(context.input("rooms")));
        if (rooms == null) {
            missing.add("rooms");
        }
        BigDecimal price = AgentInputs.decimal(context.input("price"));
        if (price == null || price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0) {
            missing.add("price");
        }
        if (!missing.isEmpty()) {
            return AgentResult.refused("MISSING_REQUIRED",
                    "Für das Exposé fehlen Pflichtangaben: " + String.join(", ", missing) + ".",
                    Map.of("missing", String.join(",", missing)));
        }

        Listing listing = new Listing(propertyId, AgentInputs.text(context.input("address")),
                livingArea, rooms, AgentInputs.text(context.input("energyClass")), price,
                AgentInputs.text(context.input("highlights")));
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);

        List<Map<String, Object>> gaps = new ArrayList<>();
        if (listing.address() == null) {
            gaps.add(gap("NO_ADDRESS", "Ohne Adresse fehlt die Lage im Exposé."));
        }
        if (listing.energyClass() == null) {
            gaps.add(gap("NO_ENERGY_CLASS",
                    "Die Energieklasse fehlt; sie ist für die Veröffentlichung erforderlich."));
        }
        if (listing.highlights() == null) {
            gaps.add(gap("NO_HIGHLIGHTS",
                    "Es sind keine Ausstattungs-Höhepunkte angegeben; das Exposé bleibt sachlich."));
        }
        boolean readyForPublication = listing.address() != null && listing.energyClass() != null;

        String listingText = listingText(listing);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("propertyId", propertyId);
        payload.put("address", listing.address());
        payload.put("livingArea", livingArea.toPlainString());
        payload.put("rooms", rooms.toPlainString());
        payload.put("energyClass", listing.energyClass());
        payload.put("price", price.toPlainString());
        payload.put("pricePerSquareMetre", livingArea.signum() == 0 ? null
                : price.divide(livingArea, 2, RoundingMode.HALF_UP).toPlainString());
        payload.put("highlights", listing.highlights());
        payload.put("listingText", listingText);
        payload.put("readyForPublication", readyForPublication);
        payload.put("gaps", gaps);
        payload.put("source", "CALLER_INPUT");
        payload.put("note", "Das Exposé wird erst nach Freigabe veröffentlicht; es beruht auf den "
                + "übergebenen Angaben, da keine Objektstammdaten angebunden sind.");

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND,
                AgentInputs.slug(propertyId, 80));
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("realestate-listing", artifact.id().toString(),
                "Exposé " + listing.label() + " (" + gaps.size() + " Lücke(n))");

        String taskTitle = "Exposé freigeben: " + AgentInputs.truncate(listing.label(), 250);
        String description = listingText + "\n\nLücken: "
                + (gaps.isEmpty() ? "keine" : String.join("; ", gaps.stream()
                        .map(item -> String.valueOf(item.get("message"))).toList()))
                + "\nDas Exposé ist vorbereitet, aber NICHT veröffentlicht.";
        AgentTaskFactory.Request request = new AgentTaskFactory.Request(taskTitle, description,
                "Die Freigabe ist erteilt und die Veröffentlichung dokumentiert.",
                AgentInputs.dueAt(today, 7), Task.Priority.NORMAL,
                List.of("expose", "freigabe"), null, context.actorUserId());
        int created = 0;
        var optionalTask = tasks.createIfAbsent(context, request);
        if (optionalTask.isPresent()) {
            AgentTaskFactory.produce(context, optionalTask.get());
            created = 1;
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("propertyId", propertyId);
        meta.put("gaps", String.valueOf(gaps.size()));
        meta.put("readyForPublication", String.valueOf(readyForPublication));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("published", "false");
        meta.put("requiresApproval", "true");
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Exposé für " + listing.label() + " vorbereitet: "
                + AgentInputs.money(price) + ", " + livingArea.toPlainString() + " m², "
                + rooms.toPlainString() + " Zimmer, " + gaps.size() + " Lücke(n). "
                + "Veröffentlichung erst nach Freigabe.", context.produced(), meta);
    }

    /** The text a person would review; visible for tests. */
    String listingText(Listing listing) {
        StringBuilder text = new StringBuilder();
        text.append("Objekt: ").append(listing.label()).append("\n");
        text.append("Wohnfläche: ").append(listing.livingArea().toPlainString()).append(" m²\n");
        text.append("Zimmer: ").append(listing.rooms().toPlainString()).append("\n");
        text.append("Preis: ").append(AgentInputs.money(listing.price())).append("\n");
        text.append("Energieklasse: ").append(listing.energyClass() == null
                ? "nicht angegeben" : listing.energyClass()).append("\n");
        if (listing.address() != null) {
            text.append("Lage: ").append(listing.address()).append("\n");
        }
        if (listing.highlights() != null) {
            text.append("Ausstattung: ").append(listing.highlights()).append("\n");
        }
        text.append("\nHinweis: Angaben beruhen auf den übergebenen Objektdaten; "
                + "Pflichtangaben sind vor der Veröffentlichung zu prüfen.");
        return text.toString();
    }

    private static BigDecimal positive(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    private static Map<String, Object> gap(String code, String message) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("code", code);
        gap.put("message", message);
        return gap;
    }
}
