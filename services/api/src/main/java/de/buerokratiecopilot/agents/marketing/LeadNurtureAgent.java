package de.buerokratiecopilot.agents.marketing;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.agents.crm.CrmStore;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code marketing-lead-nurture} — Lead-Nurturing.
 *
 * <p>Places a CRM contact in a buying phase from the lead stage and the recency of contact, then
 * proposes exactly one next step (information, case study, conversation, pause). The step becomes a
 * task and is written to the contact's activity log; the contact metadata keeps the phase so a
 * later run does not restart the sequence. Nothing is sent.</p>
 */
@Component
public class LeadNurtureAgent implements Agent {

    private final CrmStore store;
    private final AgentWorkspace workspace;

    public LeadNurtureAgent(CrmStore store, AgentWorkspace workspace) {
        this.store = store;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "marketing-lead-nurture";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID contactId = AgentInputs.uuid(context.input("contactId"));
        if (contactId == null) {
            return AgentResult.skipped("Lead-Nurturing: 'contactId' fehlt oder ist keine gültige UUID.");
        }
        Optional<Map<String, Object>> row = store.findContactRow(context.tenantId(), contactId);
        if (row.isEmpty()) {
            return AgentResult.skipped("Lead-Nurturing: Kontakt " + contactId
                    + " ist im Mandanten nicht vorhanden.");
        }
        Map<String, Object> contact = row.get();
        String stage = leadStageOf(contact);
        if ("WON".equals(stage) || "LOST".equals(stage)) {
            return AgentResult.skipped("Lead-Nurturing: der Lead ist abgeschlossen (" + stage
                    + "); keine weitere Ansprache.");
        }
        int lastActivityDays = AgentInputs.integer(context.input("lastActivityDays"), 0, 0, 3650);
        String source = AgentInputs.blankTo(context.input("source"),
                metadataValue(contact, "source") == null ? "unbekannt"
                        : metadataValue(contact, "source"));
        String industry = AgentInputs.blankTo(context.input("industry"),
                metadataValue(contact, "industry") == null ? "ohne Branche"
                        : metadataValue(contact, "industry"));
        String interest = AgentInputs.blankTo(context.input("interest"), "ohne Angabe");
        boolean hasChannel = context.input("contactEmail") != null
                || contact.get("email") != null || contact.get("phone") != null
                || contact.get("mobile") != null;
        Nurture nurture = decide(stage, lastActivityDays, hasChannel);
        UUID taskId = null;
        String title = "Nurturing: " + nurture.action() + " — " + contact.get("display_name");
        if (!"PAUSE".equals(nurture.phase())) {
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    "marketing-lead-nurture:" + contactId + ":" + nurture.phase(),
                    title,
                    "Kontakt: " + contact.get("display_name") + " (" + source + ", " + industry + ")"
                            + "\nPhase: " + nurture.phase() + " (Lead-Stufe " + stage + ")"
                            + "\nInteresse: " + interest
                            + "\nLetzte Aktivität vor " + lastActivityDays + " Tag(en)"
                            + "\n\nNächster Schritt: " + nurture.explanation()
                            + "\nDer Agent versendet nichts; Schritt vorbereiten und freigeben.",
                    "DECISION".equals(nurture.phase()) ? Task.Priority.HIGH : Task.Priority.NORMAL,
                    context.now().plus(Duration.ofDays(nurture.dueDays())),
                    java.util.List.of("marketing", "nurturing",
                            nurture.phase().toLowerCase(Locale.ROOT)),
                    Map.of("contactId", contactId.toString(), "phase", nurture.phase(),
                            "stage", stage == null ? "UNKNOWN" : stage));
            taskId = task.id();
            if (task.created()) {
                context.addProduced("task", task.id().toString(), title);
            }
        }
        else {
            context.addProduced("contact", contactId.toString(),
                    "Nurturing pausiert: " + nurture.explanation());
        }

        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("externalKey", "nurture:" + contactId + ":" + nurture.phase());
        activity.put("type", "NURTURE_PLANNED");
        activity.put("phase", nurture.phase());
        activity.put("action", nurture.action());
        activity.put("occurredAt", context.now().toString());
        activity.put("agent", "marketing-lead-nurture");
        boolean appended = store.appendContactActivity(context.tenantId(), contactId, activity) > 0;
        store.mergeContactMetadata(context.tenantId(), contactId, Map.of("nurture",
                Map.of("phase", nurture.phase(), "action", nurture.action(),
                        "updatedAt", context.now().toString(),
                        "taskId", taskId == null ? "" : taskId.toString())));

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("contactId", contactId.toString());
        meta.put("phase", nurture.phase());
        meta.put("action", nurture.action());
        meta.put("stage", stage == null ? "UNKNOWN" : stage);
        meta.put("activityRecorded", String.valueOf(appended));
        if (taskId != null) {
            meta.put("taskId", taskId.toString());
        }
        return AgentResult.done("Lead-Nurturing für " + contact.get("display_name") + ": Phase "
                + nurture.phase() + ", nächster Schritt „" + nurture.action()
                + "“ als Aufgabe geplant" + (appended ? " und im Kontaktverlauf vermerkt" : "")
                + "; nichts versendet.", context.produced(), meta);
    }

    /** The nurture decision as a pure rule. Visible for tests. */
    public static Nurture decide(String stage, int lastActivityDays, boolean hasChannel) {
        if (!hasChannel) {
            return new Nurture("AWARENESS", "Kontaktdaten vervollständigen",
                    "Ohne E-Mail oder Telefon ist keine Ansprache möglich; zuerst Kontaktdaten ergänzen.", 1);
        }
        if (lastActivityDays > 30) {
            return new Nurture("PAUSE", "Pause und in 30 Tagen prüfen",
                    "Seit über 30 Tagen keine Aktivität; eine erneute Ansprache jetzt wäre aufdringlich.", 30);
        }
        String normalized = stage == null ? "NEW" : stage.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PROPOSAL", "NEGOTIATION" ->
                    new Nurture("DECISION", "Gesprächsangebot",
                            "Der Lead steht kurz vor der Entscheidung; ein konkretes Gesprächsangebot "
                                    + "mit Terminvorschlag passt.", 1);
            case "CONTACTED", "QUALIFIED" ->
                    new Nurture("CONSIDERATION", "Fallbeispiel senden",
                            "Der Lead prüft Lösungen; ein belegtes Fallbeispiel aus der passenden "
                                    + "Branche hilft bei der Entscheidung.", 3);
            default -> new Nurture("AWARENESS", "Informationsangebot",
                    "Der Lead ist neu; eine kurze, nutzenorientierte Information ohne Verkaufsdruck "
                            + "ist der passende erste Schritt.", 2);
        };
    }

    private static String leadStageOf(Map<String, Object> row) {
        Object metadata = row.get("metadata");
        if (!(metadata instanceof Map<?, ?> metaMap)) {
            return null;
        }
        Object lead = metaMap.get("lead");
        if (lead instanceof Map<?, ?> leadMap) {
            Object stage = leadMap.get("stage");
            return stage == null ? null : String.valueOf(stage);
        }
        return null;
    }

    private static String metadataValue(Map<String, Object> row, String key) {
        Object metadata = row.get("metadata");
        if (!(metadata instanceof Map<?, ?> map) || map.get(key) == null) {
            return null;
        }
        return String.valueOf(map.get(key));
    }

    /** One nurture decision. */
    public record Nurture(String phase, String action, String explanation, int dueDays) {
    }
}
