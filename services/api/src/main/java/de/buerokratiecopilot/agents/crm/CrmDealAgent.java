package de.buerokratiecopilot.agents.crm;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.CaseService;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;

/**
 * {@code crm-deal} — Deal-Pipeline.
 *
 * <p>A deal is a case whose {@code metadata.deal} carries the pipeline stage. The agent moves
 * deals along the stages by deterministic rules (an event such as {@code PROPOSAL_SENT} names
 * the transition from the current stage), records every move in the case timeline, and flags
 * deals that have been sitting in a stage for longer than {@link #STALL_DAYS}. Re-running with
 * the same inputs moves nothing twice: a transition that would land on the current stage is a
 * no-op, and the stall flag is only written on the false→true edge.</p>
 */
@Component
public class CrmDealAgent implements Agent {

    static final List<String> STAGES = List.of("NEW", "CONTACTED", "QUALIFIED", "PROPOSAL",
            "NEGOTIATION", "WON", "LOST");
    static final Set<String> TERMINAL = Set.of("WON", "LOST");
    static final int STALL_DAYS = 14;

    /** Event → allowed transitions, mirrored in the manifest crm-deal.json. */
    static final Map<String, Map<String, String>> EVENT_RULES = Map.of(
            "CONTACTED", Map.of("NEW", "CONTACTED"),
            "RESPONDED", Map.of("CONTACTED", "QUALIFIED", "PROPOSAL", "NEGOTIATION"),
            "MEETING_HELD", Map.of("CONTACTED", "QUALIFIED"),
            "PROPOSAL_SENT", Map.of("QUALIFIED", "PROPOSAL"),
            "CONTRACT_SIGNED", Map.of("PROPOSAL", "WON", "NEGOTIATION", "WON"),
            "REJECTED", Map.of("*", "LOST"),
            "LOST", Map.of("*", "LOST"));

    private final CaseRepository cases;
    private final CaseService caseService;
    private final CrmStore store;

    public CrmDealAgent(CaseRepository cases, CaseService caseService, CrmStore store) {
        this.cases = cases;
        this.caseService = caseService;
        this.store = store;
    }

    @Override
    public String id() {
        return "crm-deal";
    }

    /** What processing one case produced. */
    private record Outcome(String kind, String message) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        String event = upperClean(context.input("event"));
        String stageInput = upperClean(context.input("stage"));
        String caseIdRaw = clean(context.input("caseId"));

        boolean explicit = caseIdRaw != null;
        List<UUID> caseIds;
        if (explicit) {
            try {
                caseIds = List.of(UUID.fromString(caseIdRaw));
            }
            catch (IllegalArgumentException e) {
                return AgentResult.failed("Deal-Pipeline: ungültige caseId: " + caseIdRaw);
            }
        }
        else {
            caseIds = store.findDealCaseIds(context.tenantId());
        }
        if (caseIds.isEmpty()) {
            return AgentResult.skipped(explicit
                    ? "Deal-Pipeline: kein Vorgang mit der caseId " + caseIdRaw + " gefunden."
                    : "Deal-Pipeline: keine Deals im Tenant gefunden.");
        }

        int moved = 0;
        int stalled = 0;
        int unchanged = 0;
        List<String> notes = new ArrayList<>();
        for (UUID caseId : caseIds) {
            Outcome outcome = process(context, caseId, event, stageInput);
            switch (outcome.kind()) {
                case "moved" -> {
                    moved++;
                    notes.add(outcome.message());
                }
                case "stalled" -> {
                    stalled++;
                    notes.add(outcome.message());
                }
                case "unchanged" -> unchanged++;
                case "norule" -> {
                    if (explicit) {
                        return AgentResult.skipped(outcome.message());
                    }
                }
                case "error" -> {
                    if (explicit) {
                        return AgentResult.failed(outcome.message());
                    }
                    notes.add(outcome.message());
                }
                case "missing" -> {
                    if (explicit) {
                        return AgentResult.skipped(outcome.message());
                    }
                }
                default -> {
                    // unknown kind is impossible by construction
                }
            }
        }

        if (moved == 0 && stalled == 0) {
            return AgentResult.skipped("Deal-Pipeline: keine Bewegung und kein Stillstand "
                    + "festgestellt. " + String.join(" ", notes));
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("moved", String.valueOf(moved));
        meta.put("stalled", String.valueOf(stalled));
        meta.put("checked", String.valueOf(caseIds.size()));
        meta.put("unchanged", String.valueOf(unchanged));
        return AgentResult.done("Deal-Pipeline: " + moved + " bewegt, " + stalled
                + " als stockend markiert.", context.produced(), meta);
    }

    private Outcome process(AgentContext context, UUID caseId, String event, String stageInput) {
        UUID tenantId = context.tenantId();
        UUID actor = context.actorUserId();
        Optional<Case> caseOpt = cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId);
        if (caseOpt.isEmpty()) {
            return new Outcome("missing", "Vorgang " + caseId + " nicht gefunden.");
        }
        Case caseRecord = caseOpt.get();

        Map<String, Object> deal = new LinkedHashMap<>(asMap(caseRecord.getMetadata().get("deal")));
        String current = deal.get("stage") == null ? null : String.valueOf(deal.get("stage"));
        if (current == null) {
            current = "NEW";
        }
        if (!STAGES.contains(current)) {
            return new Outcome("error", "Unbekannte Deal-Stufe '" + current
                    + "' am Vorgang " + caseId + ".");
        }

        String target = null;
        if (event != null) {
            if (!EVENT_RULES.containsKey(event)) {
                return new Outcome("error", "Unbekanntes Deal-Ereignis: " + event);
            }
            Map<String, String> rules = EVENT_RULES.get(event);
            target = rules.containsKey(current) ? rules.get(current) : rules.get("*");
            if (target == null) {
                return new Outcome("norule", "Ereignis " + event + " löst in Stufe " + current
                        + " keine Bewegung aus (Vorgang " + caseId + ").");
            }
        }
        else if (stageInput != null) {
            if (!STAGES.contains(stageInput)) {
                return new Outcome("error", "Unbekannte Zielstufe: " + stageInput);
            }
            if (TERMINAL.contains(current) && !current.equals(stageInput)) {
                return new Outcome("norule", "Deal ist beendet (" + current
                        + ") und wird nicht mehr bewegt.");
            }
            target = stageInput;
        }

        boolean movedDeal = false;
        if (target != null && !target.equals(current)) {
            Map<String, Object> next = new LinkedHashMap<>(deal);
            next.put("stage", target);
            next.put("lastMovedAt", context.now().toString());
            next.put("lastEvent", event);
            next.remove("stalled");
            next.remove("stalledAt");
            if ("WON".equals(target)) {
                next.put("wonAt", context.now().toString());
            }
            if ("LOST".equals(target)) {
                next.put("lostAt", context.now().toString());
            }
            next.put("agent", "crm-deal");
            store.mergeCaseMetadata(tenantId, caseId, Map.of("deal", next));
            caseService.record(caseRecord, CaseEvent.EventType.NOTE_ADDED, actor,
                    "Deal: " + current + " → " + target,
                    Map.of("deal", next, "event", event == null ? "" : event, "agent", "crm-deal"));
            context.addProduced("deal-stage", caseId.toString(), current + " → " + target);
            if ("WON".equals(target) && caseRecord.getCounterpartyContactId() != null) {
                store.markCustomer(tenantId, caseRecord.getCounterpartyContactId());
                context.addProduced("contact", caseRecord.getCounterpartyContactId().toString(),
                        "Gewonnener Deal: als Kunde markiert");
            }
            deal = next;
            current = target;
            movedDeal = true;
        }

        // Stillstand: eine nicht beendete Stufe ohne Bewegung seit STALL_DAYS wird markiert —
        // nur auf der Flanke falsch→wahr, damit wiederholte Läufe nicht erneut protokollieren.
        if (!TERMINAL.contains(current) && !movedDeal) {
            Instant base = parseInstant((String) deal.get("lastMovedAt"));
            if (base == null) {
                base = caseRecord.getCreatedAt();
            }
            boolean isStalled = base != null
                    && Duration.between(base, context.now()).toDays() >= STALL_DAYS;
            boolean wasStalled = Boolean.TRUE.equals(deal.get("stalled"));
            if (isStalled && !wasStalled) {
                Map<String, Object> next = new LinkedHashMap<>(deal);
                next.put("stalled", true);
                next.put("stalledAt", context.now().toString());
                next.put("agent", "crm-deal");
                store.mergeCaseMetadata(tenantId, caseId, Map.of("deal", next));
                caseService.record(caseRecord, CaseEvent.EventType.NOTE_ADDED, actor,
                        "Deal stockt in Stufe " + current,
                        Map.of("deal", next, "agent", "crm-deal"));
                context.addProduced("deal-stall", caseId.toString(), "Stufe " + current);
                return new Outcome("stalled", "Vorgang " + caseId + " stockt in Stufe " + current
                        + ".");
            }
        }

        if (movedDeal) {
            return new Outcome("moved", "Vorgang " + caseId + " → Stufe " + current + ".");
        }
        return new Outcome("unchanged", "Vorgang " + caseId + " bleibt in Stufe " + current + ".");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        }
        catch (Exception e) {
            return null;
        }
    }

    private static String upperClean(String value) {
        return value == null || value.isBlank() ? null
                : value.strip().toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
