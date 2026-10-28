package de.buerokratiecopilot.agents.healthcare;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code healthcare-shift-handover} — Schichtübergabe.
 *
 * <p>Builds a handover protocol from the organisational items the shift passes on: room or patient
 * reference, category, note and responsibility. It creates the tasks the next shift has to pick up
 * and groups the items by reference.</p>
 *
 * <p><strong>No diagnoses.</strong> Items carrying a diagnosis field (or the category
 * {@code DIAGNOSE}) are not copied into the protocol; they are listed as rejected with the reason.
 * The protocol is therefore safe to hand over, and it says honestly what it left out. The platform
 * has no care-planning module, so no patient records are read from somewhere else — an empty
 * protocol is explicitly marked as such instead of being filled.</p>
 */
@Component
public class ShiftHandoverAgent implements Agent {

    static final String ARTIFACT_KIND = "shift-handover";
    static final Set<String> CATEGORIES = Set.of("ORGANISATORISCH", "PFLEGE", "MEDIKATION",
            "TERMIN", "SONSTIGES");
    private static final LocalTime NEXT_SHIFT = LocalTime.of(7, 0);

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public ShiftHandoverAgent(JdbcTemplate jdbc, AgentTaskFactory tasks,
                              AgentArtifactStore artifacts) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "healthcare-shift-handover";
    }

    public record Item(String patientRef, String category, String text, String responsible) {
    }

    /** Parsed items plus what was deliberately left out. */
    public record Parsed(List<Item> items, List<Map<String, Object>> rejected, List<String> notes) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        String wardId = AgentInputs.text(context.input("wardId"));
        if (wardId == null) {
            return AgentResult.refused("NO_INPUT",
                    "Für die Schichtübergabe wird eine wardId benötigt.");
        }
        String dateRaw = context.input("shiftDate");
        if (AgentInputs.text(dateRaw) == null) {
            return AgentResult.refused("NO_INPUT",
                    "Für die Schichtübergabe wird ein Schichtdatum benötigt.");
        }
        LocalDate shiftDate = AgentInputs.date(dateRaw);
        if (shiftDate == null) {
            return AgentResult.refused("INVALID_INPUT",
                    "Das Schichtdatum ist ungültig: " + dateRaw);
        }
        UUID handoverFrom = optionalUuid(context, "handoverFrom");
        if (handoverFrom == null && AgentInputs.text(context.input("handoverFrom")) != null) {
            return AgentResult.refused("INVALID_INPUT",
                    "handoverFrom ist keine gültige UUID: " + context.input("handoverFrom"));
        }
        UUID handoverTo = optionalUuid(context, "handoverTo");
        if (handoverTo == null && AgentInputs.text(context.input("handoverTo")) != null) {
            return AgentResult.refused("INVALID_INPUT",
                    "handoverTo ist keine gültige UUID: " + context.input("handoverTo"));
        }

        String openItemsRaw = context.input("openItems");
        Parsed parsed = parseItems(openItemsRaw);
        if (parsed == null) {
            return AgentResult.refused("INVALID_INPUT",
                    "openItems ist kein gültiges JSON-Array: " + openItemsRaw);
        }
        if (parsed.items().isEmpty() && !parsed.rejected().isEmpty()) {
            return AgentResult.refused("MEDICAL_DATA_REJECTED",
                    "Es wurden ausschließlich medizinische Angaben übergeben; das Protokoll "
                            + "enthält bewusst keine Diagnosen.",
                    Map.of("rejected", String.valueOf(parsed.rejected().size())));
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        Map<String, List<Item>> byReference = new LinkedHashMap<>();
        for (Item item : parsed.items()) {
            byReference.computeIfAbsent(item.patientRef(), ignored -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<Item>> entry : byReference.entrySet()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Item item : entry.getValue()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("category", item.category());
                row.put("text", item.text());
                row.put("responsible", item.responsible());
                items.add(row);
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("patientRef", entry.getKey());
            group.put("items", items);
            groups.add(group);
        }

        UUID assignee = handoverTo;
        List<String> gaps = new ArrayList<>();
        if (handoverTo != null) {
            Integer active = jdbc.queryForObject("""
                    select count(*) from memberships
                    where tenant_id = ? and user_id = ? and status = 'ACTIVE'
                    """, Integer.class, context.tenantId(), handoverTo);
            if (active == null || active == 0) {
                gaps.add("Die übergebende Person " + handoverTo + " ist kein aktives Mitglied "
                        + "dieses Kontos; die Aufgaben bleiben der ausführenden Person "
                        + "zugeordnet.");
                assignee = context.actorUserId();
            }
        }
        if (AgentInputs.text(openItemsRaw) == null) {
            gaps.add("Es wurden keine offenen Punkte angegeben; das Protokoll ist leer.");
        }
        if (!parsed.notes().isEmpty()) {
            gaps.addAll(parsed.notes());
        }
        gaps.add("Offene Aufgaben aus einer Pflegeplanung sind nicht angebunden (kein "
                + "Pflegeplanungsmodul); übergeben werden ausschließlich die Angaben in openItems.");

        int created = 0;
        for (Item item : parsed.items()) {
            String title = "Übergabe " + AgentInputs.truncate(wardId, 80) + " " + shiftDate + ": "
                    + AgentInputs.truncate(item.text(), 200);
            String description = "Bereich: " + item.patientRef() + " (" + item.category() + "). "
                    + item.text() + (item.responsible() == null ? ""
                            : " Verantwortlich: " + item.responsible())
                    + ". Nur organisatorische Angaben.";
            AgentTaskFactory.Request request = new AgentTaskFactory.Request(title, description,
                    "Der Punkt ist in der nächsten Schicht erledigt oder erneut übergeben.",
                    AgentInputs.dueAt(shiftDate, 1, NEXT_SHIFT), Task.Priority.NORMAL,
                    List.of("uebergabe", "schicht"), null,
                    assignee == null ? context.actorUserId() : assignee);
            var optionalTask = tasks.createIfAbsent(context, request);
            if (optionalTask.isPresent()) {
                AgentTaskFactory.produce(context, optionalTask.get());
                created++;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("wardId", wardId);
        payload.put("shiftDate", shiftDate.toString());
        payload.put("handoverFrom", handoverFrom == null ? null : handoverFrom.toString());
        payload.put("handoverTo", handoverTo == null ? null : handoverTo.toString());
        payload.put("groups", groups);
        payload.put("rejected", parsed.rejected());
        payload.put("gaps", gaps);
        payload.put("itemCount", parsed.items().size());
        payload.put("note", "Nur organisatorische Angaben; keine Diagnosen und keine "
                + "medizinischen Entscheidungen.");

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND, AgentInputs.slug(wardId, 60),
                shiftDate.toString());
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("shift-handover", artifact.id().toString(),
                "Übergabeprotokoll " + wardId + " " + shiftDate + ": " + parsed.items().size()
                        + " Punkt(e), " + created + " Aufgabe(n)");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("wardId", wardId);
        meta.put("shiftDate", shiftDate.toString());
        meta.put("items", String.valueOf(parsed.items().size()));
        meta.put("groups", String.valueOf(groups.size()));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("rejected", String.valueOf(parsed.rejected().size()));
        meta.put("noDiagnosis", "true");
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Schichtübergabe " + wardId + " am " + shiftDate + ": "
                + parsed.items().size() + " Punkt(e) in " + groups.size() + " Bereich(en), "
                + created + " Aufgabe(n) für die nächste Schicht"
                + (parsed.rejected().isEmpty() ? "" : ", " + parsed.rejected().size()
                        + " medizinische Angabe(n) nicht übernommen")
                + ".", context.produced(), meta);
    }

    /**
     * Parses the open items. Returns {@code null} for malformed JSON; medical entries are listed as
     * rejected instead of being copied. Visible for tests.
     */
    public static Parsed parseItems(String raw) {
        if (AgentInputs.text(raw) == null) {
            return new Parsed(List.of(), List.of(), List.of());
        }
        JsonNode node = AgentInputs.json(raw);
        if (node == null || !node.isArray()) {
            return null;
        }
        List<Item> items = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int index = 0;
        for (JsonNode element : node) {
            index++;
            if (element.isTextual()) {
                String text = element.asText().strip();
                if (!text.isEmpty()) {
                    items.add(new Item("Allgemein", "ORGANISATORISCH", text, null));
                }
                continue;
            }
            if (!element.isObject()) {
                rejected.add(rejected(index, "Kein Objekt und kein Text"));
                continue;
            }
            if (element.has("diagnose") || element.has("diagnosis")
                    || element.has("medicalDiagnosis")) {
                rejected.add(rejected(index, "Medizinische Angabe (Diagnose) wird nicht übernommen"));
                continue;
            }
            String category = firstText(element, "category", "kategorie");
            String normalised = category == null ? "ORGANISATORISCH"
                    : category.strip().toUpperCase(Locale.ROOT);
            if ("DIAGNOSE".equals(normalised) || "DIAGNOSIS".equals(normalised)) {
                rejected.add(rejected(index, "Kategorie Diagnose wird nicht übernommen"));
                continue;
            }
            if (!CATEGORIES.contains(normalised)) {
                notes.add("Unbekannte Kategorie \"" + category + "\" als SONSTIGES geführt.");
                normalised = "SONSTIGES";
            }
            String text = firstText(element, "item", "note", "text", "task", "beschreibung");
            if (text == null) {
                rejected.add(rejected(index, "Punkt ohne Beschreibung"));
                continue;
            }
            String reference = firstText(element, "patientRef", "room", "zimmer", "patient",
                    "patientReference");
            String responsible = firstText(element, "responsible", "verantwortlich", "owner");
            items.add(new Item(reference == null ? "Allgemein" : reference, normalised, text,
                    responsible));
        }
        return new Parsed(List.copyOf(items), List.copyOf(rejected), List.copyOf(notes));
    }

    private static Map<String, Object> rejected(int index, String reason) {
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("index", index);
        rejected.put("reason", reason);
        return rejected;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = AgentInputs.nodeText(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static UUID optionalUuid(AgentContext context, String key) {
        return AgentInputs.uuid(context.input(key));
    }
}
