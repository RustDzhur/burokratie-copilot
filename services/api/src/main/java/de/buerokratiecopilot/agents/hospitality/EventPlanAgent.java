package de.buerokratiecopilot.agents.hospitality;

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
 * {@code hospitality-event-plan} — Veranstaltungsplanung.
 *
 * <p>Turns the request (name, date, guests, room, catering, budget) into a plan a person can work
 * with: room and technology, menu, staffing, a relative timeline and a checklist whose items become
 * tasks with a role and a due date. The staffing numbers are a planning rule, not a capacity
 * promise — the agent says explicitly that it cannot confirm the room because no room master data is
 * connected.</p>
 */
@Component
public class EventPlanAgent implements Agent {

    static final String ARTIFACT_KIND = "event-plan";
    static final int SERVICE_GUESTS_PER_STAFF = 12;
    static final int KITCHEN_GUESTS_PER_STAFF = 25;
    static final int MAX_GUESTS = 10000;

    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public EventPlanAgent(AgentTaskFactory tasks, AgentArtifactStore artifacts) {
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "hospitality-event-plan";
    }

    /** One checklist item with the role that owns it and the offset from the event date. */
    public record PlanTask(String item, String role, int daysOffset, String description) {
    }

    /** The derived planning facts; pure and testable. */
    public record Plan(int serviceStaff, int kitchenStaff, int staffTotal,
                       List<PlanTask> checklist, List<Map<String, Object>> timeline,
                       List<String> gaps, BigDecimal budgetPerGuest) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        String eventName = AgentInputs.text(context.input("eventName"));
        if (eventName == null) {
            return AgentResult.refused("NO_INPUT", "Für die Veranstaltung wird ein Name benötigt.");
        }
        String dateRaw = context.input("date");
        LocalDate date = AgentInputs.date(dateRaw);
        if (AgentInputs.text(dateRaw) == null) {
            return AgentResult.refused("NO_INPUT", "Für die Veranstaltung wird ein Datum benötigt.");
        }
        if (date == null) {
            return AgentResult.refused("INVALID_INPUT", "Das Veranstaltungsdatum ist ungültig: "
                    + dateRaw);
        }
        Integer guests = guests(context.input("expectedGuests"));
        if (guests == null) {
            return AgentResult.refused("MISSING_REQUIRED",
                    "Für die Veranstaltung wird eine gültige Gästezahl benötigt (1–"
                            + MAX_GUESTS + ").");
        }
        String roomId = AgentInputs.text(context.input("roomId"));
        String catering = AgentInputs.text(context.input("cateringNeeds"));
        BigDecimal budget = AgentInputs.decimal(context.input("budget"));
        if (budget != null && budget.signum() < 0) {
            return AgentResult.refused("INVALID_INPUT", "Das Budget darf nicht negativ sein.");
        }

        Plan plan = plan(guests, roomId, catering, budget);
        String slug = AgentInputs.slug(eventName, 50);

        List<Map<String, Object>> checklist = new ArrayList<>();
        for (PlanTask item : plan.checklist()) {
            checklist.add(Map.of("item", item.item(), "role", item.role(),
                    "daysOffset", item.daysOffset(), "description", item.description()));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("eventName", eventName);
        payload.put("date", date.toString());
        payload.put("expectedGuests", guests);
        payload.put("roomId", roomId);
        payload.put("cateringNeeds", catering);
        payload.put("budget", budget == null ? null : budget.toPlainString());
        payload.put("budgetPerGuest", plan.budgetPerGuest() == null ? null
                : plan.budgetPerGuest().toPlainString());
        payload.put("staffing", Map.of("service", plan.serviceStaff(), "kitchen",
                plan.kitchenStaff(), "total", plan.staffTotal()));
        payload.put("checklist", checklist);
        payload.put("timeline", plan.timeline());
        payload.put("gaps", plan.gaps());
        payload.put("capacityConfirmed", false);
        payload.put("note", "Der Agent bestätigt keine Kapazitäten ohne Prüfung der Raumdaten; "
                + "die Personalzahlen sind ein Planungswert.");

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND, date.toString(), slug);
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("event-plan", artifact.id().toString(),
                "Veranstaltungsplan " + eventName + " am " + date + ": " + guests + " Gäste, "
                        + plan.staffTotal() + " Mitarbeitende im Bedarf");

        int created = 0;
        for (PlanTask item : plan.checklist()) {
            String title = "Veranstaltung " + AgentInputs.truncate(eventName, 200) + ": "
                    + item.item();
            String description = "Rolle: " + item.role() + ". " + item.description()
                    + " Anlass: " + eventName + " am " + date + " mit " + guests + " Gästen.";
            AgentTaskFactory.Request request = new AgentTaskFactory.Request(title, description,
                    "Der Schritt ist erledigt und das Ergebnis für die Veranstaltung dokumentiert.",
                    AgentInputs.dueAt(date, item.daysOffset()), Task.Priority.NORMAL,
                    List.of("veranstaltung", slug), null, context.actorUserId());
            var optionalTask = tasks.createIfAbsent(context, request);
            if (optionalTask.isPresent()) {
                AgentTaskFactory.produce(context, optionalTask.get());
                created++;
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("eventName", eventName);
        meta.put("date", date.toString());
        meta.put("guests", String.valueOf(guests));
        meta.put("staffTotal", String.valueOf(plan.staffTotal()));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("gaps", String.valueOf(plan.gaps().size()));
        meta.put("capacityConfirmed", "false");
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Veranstaltungsplan " + eventName + " am " + date + " für " + guests
                + " Gäste: " + plan.checklist().size() + " Aufgabe(n) angelegt, "
                + plan.gaps().size() + " Lücke(n). Kapazitäten sind nicht bestätigt.",
                context.produced(), meta);
    }

    /**
     * The planning rule: staffing, relative timeline, checklist and the gaps that follow from the
     * missing inputs. Visible for tests.
     */
    public static Plan plan(int guests, String roomId, String catering, BigDecimal budget) {
        int service = Math.max(1, (int) Math.ceil(guests / (double) SERVICE_GUESTS_PER_STAFF));
        int kitchen = Math.max(1, (int) Math.ceil(guests / (double) KITCHEN_GUESTS_PER_STAFF));
        int total = service + kitchen;

        List<PlanTask> checklist = List.of(
                new PlanTask("Raum reservieren und Kapazität prüfen", "Veranstaltungsleitung", -21,
                        "Raum für " + guests + " Gäste anfragen und die tatsächliche Kapazität "
                                + "bestätigen lassen."),
                new PlanTask("Menü und Catering abstimmen", "Küchenleitung", -14,
                        "Menü auf Basis von \"" + (catering == null ? "noch offen" : catering)
                                + "\" festlegen; Allergien und Getränke berücksichtigen."),
                new PlanTask("Personal einteilen", "Serviceleitung", -10,
                        "Bedarf: " + service + " Service, " + kitchen + " Küche (" + total
                                + " gesamt). Schichten und Vertretung planen."),
                new PlanTask("Technik und Bestuhlung prüfen", "Technik", -3,
                        "Bestuhlung, Ton, Licht und Zugänge für " + guests + " Gäste prüfen."),
                new PlanTask("Gästezahl final bestätigen", "Veranstaltungsleitung", -2,
                        "Verbindliche Gästezahl an Küche und Service melden."),
                new PlanTask("Ablauf und Aufbau abstimmen", "Serviceleitung", -1,
                        "Timeline, Aufbau und Übergaben mit allen Beteiligten durchgehen."),
                new PlanTask("Nachbereitung und Abrechnung", "Verwaltung", 2,
                        "Rechnung, Feedback und Nachkalkulation abschließen."));

        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(phase("Aufbau", -240));
        timeline.add(phase("Empfang", -30));
        timeline.add(phase("Begrüßung", 0));
        timeline.add(phase("Menü", 15));
        timeline.add(phase("Programm", 105));
        timeline.add(phase("Abschluss", 165));
        timeline.add(phase("Abbau", 180));

        List<String> gaps = new ArrayList<>();
        if (roomId == null) {
            gaps.add("Kein Raum angegeben; die Raumplanung steht aus.");
        }
        gaps.add("Die Raumkapazität kann nicht geprüft werden (keine Raumstammdaten "
                + "angebunden).");
        if (catering == null) {
            gaps.add("Kein Catering-Bedarf angegeben; das Menü ist offen.");
        }
        if (budget == null) {
            gaps.add("Kein Budget angegeben; eine Wirtschaftlichkeitsprüfung ist nicht möglich.");
        }
        BigDecimal perGuest = budget == null || guests == 0 ? null
                : budget.divide(BigDecimal.valueOf(guests), 2, RoundingMode.HALF_UP);
        return new Plan(service, kitchen, total, checklist, List.copyOf(timeline),
                List.copyOf(gaps), perGuest);
    }

    private static Map<String, Object> phase(String name, int offsetMinutes) {
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("phase", name);
        phase.put("offsetMinutes", offsetMinutes);
        return phase;
    }

    private static Integer guests(String raw) {
        String value = AgentInputs.text(raw);
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed < 1 || parsed > MAX_GUESTS ? null : parsed;
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }
}
