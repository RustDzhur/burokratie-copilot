package de.buerokratiecopilot.agents.sales;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
 * {@code sales-followup-cadence} — Vertriebs-Follow-up-Kadenz.
 *
 * <p>Turns an open deal into a dated sequence of follow-up tasks (3, 7, 14, 30 days by default),
 * alternating the channel. The cadence stops when the case already shows a reaction after the offer
 * was sent and the deal is won or lost. Each step is one task with context, not a bulk email; the
 * agent sends nothing.</p>
 */
@Component
public class FollowupCadenceAgent implements Agent {

    private static final int MAX_STEPS = 10;
    private static final List<String> DEFAULT_CADENCE = List.of("3", "7", "14", "30");
    private static final List<String> DEFAULT_CHANNELS = List.of("E-Mail", "Telefon");

    private final CrmStore store;
    private final AgentWorkspace workspace;

    public FollowupCadenceAgent(CrmStore store, AgentWorkspace workspace) {
        this.store = store;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "sales-followup-cadence";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID dealId = AgentInputs.uuid(context.input("dealId"));
        if (dealId == null) {
            return AgentResult.skipped("Follow-up-Kadenz: 'dealId' fehlt oder ist keine gültige UUID.");
        }
        Optional<CrmStore.DealInfo> deal = store.dealInfo(context.tenantId(), dealId);
        if (deal.isEmpty()) {
            return AgentResult.skipped("Follow-up-Kadenz: Vorgang " + dealId
                    + " hat keine Deal-Daten im Mandanten.");
        }
        String stage = deal.get().stage();
        if ("WON".equals(stage) || "LOST".equals(stage)) {
            return AgentResult.skipped("Follow-up-Kadenz: der Deal ist abgeschlossen (" + stage + ").");
        }
        List<Integer> offsets = offsets(context.input("cadenceDays"));
        if (offsets.isEmpty()) {
            return AgentResult.failed("Follow-up-Kadenz: 'cadenceDays' enthält keine gültigen "
                    + "positiven Tagesabstände.");
        }
        LocalDate offerSent = AgentInputs.date(context.input("offerSentAt"));
        if (offerSent == null) {
            offerSent = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        }
        boolean stopOnReply = AgentInputs.bool(context.input("stopOnReply"), true);
        if (stopOnReply) {
            Optional<Instant> lastActivity = store.lastActivityAt(context.tenantId(), dealId, null);
            Instant offerInstant = offerSent.atStartOfDay(AgentInputs.BERLIN).toInstant();
            if (lastActivity.isPresent() && lastActivity.get().isAfter(offerInstant)) {
                return AgentResult.skipped("Follow-up-Kadenz: seit dem Angebot am " + offerSent
                        + " ist bereits eine Aktivität am Vorgang dokumentiert ("
                        + lastActivity.get() + "); die Kadenz endet.");
            }
        }
        List<String> channels = AgentInputs.list(context.input("channels"));
        if (channels.isEmpty()) {
            channels = DEFAULT_CHANNELS;
        }
        String sourceKey = "sales-followup-cadence:" + dealId + ":" + offerSent;
        List<AgentResult.Produced> created = new ArrayList<>();
        int steps = 0;
        int existing = 0;
        int skippedPast = 0;
        for (int i = 0; i < offsets.size() && i < MAX_STEPS; i++) {
            int offset = offsets.get(i);
            String channel = channels.get(i % channels.size());
            LocalDate dueDate = offerSent.plusDays(offset);
            Instant due = dueDate.atTime(9, 0).atZone(AgentInputs.BERLIN).toInstant();
            if (due.isBefore(context.now())) {
                due = context.now().plus(Duration.ofHours(1));
                skippedPast++;
            }
            String title = "Nachfassen (+" + offset + " Tage): Deal " + dealId;
            AgentWorkspace.TaskRef task = workspace.ensureTask(context,
                    sourceKey + ":step:" + offset, title,
                    "Deal: " + dealId + "\nStufe: " + (stage == null ? "unbekannt" : stage)
                            + "\nAngebot gesendet: " + offerSent
                            + "\nKadenzschritt: Tag " + offset + "\nKanal: " + channel
                            + "\n\nDer Agent versendet keine Massen-E-Mail; Kontakt aufnehmen und "
                            + "Ergebnis dokumentieren. Bei Antwort oder Abschluss Kadenz beenden.",
                    Task.Priority.NORMAL, due,
                    List.of("sales", "followup"), Map.of("dealId", dealId.toString(),
                            "step", offset, "channel", channel));
            steps++;
            if (task.created()) {
                created.add(new AgentResult.Produced("task", task.id().toString(), title));
                context.addProduced("task", task.id().toString(), title);
            }
            else {
                existing++;
            }
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("dealId", dealId.toString());
        meta.put("steps", String.valueOf(steps));
        meta.put("tasksCreated", String.valueOf(created.size()));
        meta.put("tasksExisting", String.valueOf(existing));
        meta.put("overdueStepsRequeued", String.valueOf(skippedPast));
        meta.put("offerSentAt", offerSent.toString());
        return AgentResult.done("Follow-up-Kadenz für Deal " + dealId + ": " + steps
                + " Schritt(e) geplant, " + created.size() + " Aufgabe(n) neu, " + existing
                + " bereits vorhanden; nichts versendet.", context.produced(), meta);
    }

    /** Parses the cadence string, deduplicates and sorts. Visible for tests. */
    public static List<Integer> offsets(String raw) {
        List<String> parts = raw == null || raw.isBlank() ? DEFAULT_CADENCE
                : AgentInputs.list(raw);
        return parts.stream().map(AgentInputs::integer).filter(value -> value != null
                        && value > 0 && value <= 365)
                .distinct().sorted(Comparator.naturalOrder()).limit(MAX_STEPS).toList();
    }
}
