package de.buerokratiecopilot.agents.knowledge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code knowledge-meeting-memory} — Besprechungsgedächtnis.
 *
 * <p>Reads meeting notes and keeps what is <em>explicitly written</em>: decisions ("Entscheidung:",
 * "Beschluss:", "Wir beschließen …"), open points ("Offen:", "Frage:") and actions ("Aufgabe:",
 * "To-do:", "Nächster Schritt:"). Lines without such a marker are not interpreted — the agent must
 * not invent a decision nobody recorded. When nothing can be evidenced, the run refuses and says
 * why.</p>
 *
 * <p>The memory is stored as a JSON artifact keyed by the meeting id or the hash of the notes; the
 * actions become tasks, idempotent by title. A rerun of the same protocol does not duplicate them.</p>
 */
@Component
public class MeetingMemoryAgent implements Agent {

    static final String ARTIFACT_KIND = "meeting-memory";
    static final int MAX_ITEMS = 100;
    private static final int MAX_PRODUCED_DECISIONS = 50;

    private static final List<String> ACTION_PREFIXES = List.of(
            "offene aufgabe", "action item", "nächster schritt", "naechster schritt", "next step",
            "maßnahme", "massnahme", "aufgabe", "to-do", "todo", "action");
    private static final List<String> OPEN_PREFIXES = List.of(
            "offener punkt", "offene punkte", "offene frage", "offener punkt", "ungeklärt",
            "ungeklaert", "offen", "open point", "open", "frage", "tbd", "klärung", "klaerung");
    private static final List<String> DECISION_PREFIXES = List.of(
            "entscheidung", "beschluss", "entschieden", "decision", "decided", "vereinbart",
            "abgestimmt", "freigegeben", "genehmigt");
    private static final List<String> DECISION_SENTENCES = List.of(
            "wir beschließen", "wir beschliessen", "wir haben beschlossen",
            "es wird beschlossen", "beschlossen:");

    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•·–—]|\\d+[.)]|[a-z]\\))\\s*");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern GERMAN_DATE =
            Pattern.compile("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b");

    private final CaseRepository cases;
    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public MeetingMemoryAgent(CaseRepository cases, AgentTaskFactory tasks,
                              AgentArtifactStore artifacts) {
        this.cases = cases;
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "knowledge-meeting-memory";
    }

    /** What the notes evidence. Empty lists mean "not written down", not "nothing happened". */
    public record Memory(List<String> decisions, List<String> openPoints, List<String> actions) {

        public boolean isEmpty() {
            return decisions.isEmpty() && openPoints.isEmpty() && actions.isEmpty();
        }
    }

    @Override
    public AgentResult run(AgentContext context) {
        String notes = AgentInputs.text(context.input("notes"));
        if (notes == null) {
            return AgentResult.refused("NO_INPUT",
                    "Ohne Besprechungsnotizen kann kein Gedächtnis festgehalten werden.");
        }
        UUID meetingId = AgentInputs.uuid(context.input("meetingId"));
        if (AgentInputs.text(context.input("meetingId")) != null && meetingId == null) {
            return AgentResult.refused("INVALID_INPUT",
                    "meetingId ist keine gültige UUID: " + context.input("meetingId"));
        }
        String caseRaw = context.input("caseId");
        UUID caseId = AgentInputs.uuid(caseRaw);
        if (AgentInputs.text(caseRaw) != null && caseId == null) {
            return AgentResult.refused("INVALID_INPUT",
                    "caseId ist keine gültige UUID: " + caseRaw);
        }
        Case caseRecord = null;
        if (caseId != null) {
            caseRecord = cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, context.tenantId())
                    .orElse(null);
            if (caseRecord == null) {
                return AgentResult.refused("NOT_FOUND",
                        "Der Vorgang " + caseId + " wurde nicht gefunden (oder gehört zu einem "
                                + "anderen Konto).");
            }
        }

        Memory memory = parse(notes);
        if (memory.isEmpty()) {
            return AgentResult.refused("NO_DATA",
                    "Die Notizen enthalten keine belegte Entscheidung, keinen offenen Punkt und "
                            + "keine Aufgabe. Es wird nichts festgehalten, was nicht dasteht.");
        }

        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        List<String> participants = AgentInputs.distinct(
                AgentInputs.list(context.input("participants")), 50);
        String notesHash = AgentInputs.sha256(notes);
        String reference = meetingId != null ? meetingId.toString()
                : "notizen-" + notesHash.substring(0, 16);
        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND, reference,
                caseId == null ? null : caseId.toString());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("meetingId", meetingId == null ? null : meetingId.toString());
        payload.put("caseId", caseId == null ? null : caseId.toString());
        payload.put("caseReference", caseRecord == null ? null : caseRecord.getReference());
        payload.put("notesHash", notesHash);
        payload.put("participants", participants);
        payload.put("decisions", memory.decisions());
        payload.put("openPoints", memory.openPoints());
        payload.put("actions", memory.actions());
        payload.put("note", "Nur belegte Aussagen aus den Notizen; keine erfundenen Entscheidungen.");

        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("meeting-memory", artifact.id().toString(),
                "Besprechungsgedächtnis: " + memory.decisions().size() + " Entscheidung(en), "
                        + memory.openPoints().size() + " offener Punkt(e), "
                        + memory.actions().size() + " Aufgabe(n)");

        for (int index = 0; index < Math.min(memory.decisions().size(), MAX_PRODUCED_DECISIONS);
                index++) {
            String decision = memory.decisions().get(index);
            String producedRef = artifact.id() + "#entscheidung-" + (index + 1);
            context.addProduced("decision", producedRef, decision);
        }
        for (int index = 0; index < Math.min(memory.openPoints().size(), MAX_PRODUCED_DECISIONS);
                index++) {
            String openPoint = memory.openPoints().get(index);
            context.addProduced("open-point", artifact.id() + "#offen-" + (index + 1), openPoint);
        }

        int created = 0;
        for (String action : memory.actions()) {
            LocalDate dueDate = dueDateIn(action);
            String title = "Besprechung: " + AgentInputs.truncate(action, 280);
            AgentTaskFactory.Request request = new AgentTaskFactory.Request(title,
                    "Aus den Besprechungsnotizen"
                            + (caseRecord == null ? "" : " zum Vorgang " + caseRecord.getReference())
                            + (participants.isEmpty() ? ""
                                    : " (Teilnehmende: " + String.join(", ", participants) + ")")
                            + ": " + action,
                    "Die Aufgabe ist erledigt und im Protokoll nachvollziehbar.",
                    dueDate == null ? AgentInputs.dueAt(today, 7) : AgentInputs.dueAt(dueDate, 0),
                    Task.Priority.NORMAL, List.of("besprechung", "aufgabe"), caseId, null);
            var optionalTask = tasks.createIfAbsent(context, request);
            if (optionalTask.isPresent()) {
                AgentTaskFactory.produce(context, optionalTask.get());
                created++;
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("decisions", String.valueOf(memory.decisions().size()));
        meta.put("openPoints", String.valueOf(memory.openPoints().size()));
        meta.put("actions", String.valueOf(memory.actions().size()));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("participants", String.valueOf(participants.size()));
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Besprechungsgedächtnis: " + memory.decisions().size()
                + " Entscheidung(en), " + memory.openPoints().size() + " offene(r) Punkt(e), "
                + memory.actions().size() + " Aufgabe(n) festgehalten"
                + (created > 0 ? "; " + created + " Aufgabe(n) angelegt" : "") + ".",
                context.produced(), meta);
    }

    /**
     * Extracts the parts of the notes that carry a marker. Visible for tests: the parser is the
     * rule set of this agent and must be reviewable without a database.
     */
    public static Memory parse(String notes) {
        Set<String> decisions = new LinkedHashSet<>();
        Set<String> openPoints = new LinkedHashSet<>();
        Set<String> actions = new LinkedHashSet<>();
        if (notes == null) {
            return new Memory(List.of(), List.of(), List.of());
        }
        for (String rawLine : notes.split("\\R")) {
            String line = BULLET.matcher(rawLine).replaceFirst("").strip();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.GERMAN);
            String action = statementAfter(lower, line, ACTION_PREFIXES);
            if (action != null) {
                actions.add(action);
                continue;
            }
            String decision = statementAfter(lower, line, DECISION_PREFIXES);
            if (decision != null) {
                decisions.add(decision);
                continue;
            }
            String openPoint = statementAfter(lower, line, OPEN_PREFIXES);
            if (openPoint != null) {
                openPoints.add(openPoint);
                continue;
            }
            if (DECISION_SENTENCES.stream().anyMatch(lower::startsWith)) {
                decisions.add(line);
            }
        }
        return new Memory(cap(decisions), cap(openPoints), cap(actions));
    }

    /** The statement after a marker word, or null when the line does not start with one. */
    private static String statementAfter(String lower, String original, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (!lower.startsWith(prefix)) {
                continue;
            }
            if (lower.length() > prefix.length()
                    && !isSeparator(lower.charAt(prefix.length()))) {
                continue;
            }
            String rest = original.substring(prefix.length()).strip();
            if (!rest.isEmpty() && isLeadingSeparator(rest.charAt(0))) {
                rest = rest.substring(1).strip();
            }
            return rest.isEmpty() ? null : rest;
        }
        return null;
    }

    private static boolean isSeparator(char value) {
        return isLeadingSeparator(value) || Character.isWhitespace(value);
    }

    private static boolean isLeadingSeparator(char value) {
        return value == ':' || value == '-' || value == '–' || value == '—' || value == '.';
    }

    private static List<String> cap(Set<String> values) {
        List<String> list = new ArrayList<>(values);
        return List.copyOf(list.subList(0, Math.min(list.size(), MAX_ITEMS)));
    }

    /** The first date written in the action, or null. */
    public static LocalDate dueDateIn(String action) {
        if (action == null) {
            return null;
        }
        Matcher iso = ISO_DATE.matcher(action);
        if (iso.find()) {
            return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                    Integer.parseInt(iso.group(3)));
        }
        Matcher german = GERMAN_DATE.matcher(action);
        if (german.find()) {
            return LocalDate.of(Integer.parseInt(german.group(3)),
                    Integer.parseInt(german.group(2)), Integer.parseInt(german.group(1)));
        }
        return null;
    }
}
