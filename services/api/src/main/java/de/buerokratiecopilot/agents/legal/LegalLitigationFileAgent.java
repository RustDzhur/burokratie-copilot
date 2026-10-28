package de.buerokratiecopilot.agents.legal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.cases.CaseDocumentRepository;
import de.buerokratiecopilot.cases.CaseEventRepository;
import de.buerokratiecopilot.cases.CaseParticipantRepository;
import de.buerokratiecopilot.cases.CaseRepository;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.cases.domain.CaseParticipant;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code legal-litigation-file} — Streitfall-Akte.
 *
 * <p>Builds a working file for one case: header, chronology of what happened, linked documents,
 * participants, deadlines and the gaps that are visible from the data. The file is prepared for a
 * person — and, if needed, for a lawyer; it is not an assessment of the case. That sentence is part
 * of the artifact, not just of this comment.</p>
 *
 * <p>Deadlines come from {@code task_deadlines}, so the file shows the dates the platform really
 * tracks. A case without documents, counterparty or deadline is not silently presented as complete:
 * each missing piece is a gap with a follow-up task.</p>
 */
@Component
public class LegalLitigationFileAgent implements Agent {

    static final String ARTIFACT_KIND = "litigation-file";
    private static final int URGENT_DAYS = 14;
    private static final int MAX_EVENTS = 100;
    private static final int MAX_CHRONOLOGY = 200;

    private final CaseRepository cases;
    private final CaseDocumentRepository caseDocuments;
    private final CaseEventRepository caseEvents;
    private final CaseParticipantRepository participants;
    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public LegalLitigationFileAgent(CaseRepository cases, CaseDocumentRepository caseDocuments,
                                    CaseEventRepository caseEvents,
                                    CaseParticipantRepository participants, JdbcTemplate jdbc,
                                    AgentTaskFactory tasks, AgentArtifactStore artifacts) {
        this.cases = cases;
        this.caseDocuments = caseDocuments;
        this.caseEvents = caseEvents;
        this.participants = participants;
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "legal-litigation-file";
    }

    /** A tracked deadline of the case, with the days left from today. */
    public record Deadline(UUID id, String title, String type, LocalDate dueDate, long daysLeft,
                           String taskStatus) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID caseId = AgentInputs.uuid(context.input("caseId"));
        if (caseId == null) {
            return AgentResult.refused("NO_INPUT",
                    "Für die Streitfall-Akte wird eine gültige caseId benötigt.");
        }
        Case caseRecord = cases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, context.tenantId())
                .orElse(null);
        if (caseRecord == null) {
            return AgentResult.refused("NOT_FOUND",
                    "Der Vorgang " + caseId + " wurde nicht gefunden (oder gehört zu einem anderen "
                            + "Konto).");
        }

        String counterpartyRaw = context.input("counterpartyContactId");
        UUID counterpartyId = AgentInputs.uuid(counterpartyRaw);
        if (AgentInputs.text(counterpartyRaw) != null && counterpartyId == null) {
            return AgentResult.refused("INVALID_INPUT",
                    "counterpartyContactId ist keine gültige UUID: " + counterpartyRaw);
        }
        if (counterpartyId == null) {
            counterpartyId = caseRecord.getCounterpartyContactId();
        }
        BigDecimal disputeAmount = null;
        String disputeRaw = context.input("disputeAmount");
        if (AgentInputs.text(disputeRaw) != null) {
            disputeAmount = AgentInputs.decimal(disputeRaw);
            if (disputeAmount == null) {
                return AgentResult.refused("INVALID_INPUT",
                        "disputeAmount ist keine Zahl: " + disputeRaw);
            }
        }
        String jurisdiction = AgentInputs.text(context.input("jurisdiction"));
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);

        List<CaseDocumentRepository.LinkedDocument> documents =
                caseDocuments.findByCaseId(caseRecord.getId());
        List<CaseEvent> events = caseEvents.findByCaseIdOrderByOccurredAtDesc(caseRecord.getId())
                .stream().limit(MAX_EVENTS).toList();
        List<CaseParticipant> caseParticipants =
                participants.findByCaseIdOrderByGrantedAtAsc(caseRecord.getId());
        List<Deadline> deadlines = deadlines(context.tenantId(), caseRecord.getId(), today);
        List<Map<String, Object>> openTasks = openTasks(context.tenantId(), caseRecord.getId());
        Map<String, Object> counterparty = counterparty(context.tenantId(), counterpartyId);

        List<Map<String, Object>> gaps = new ArrayList<>();
        if (documents.isEmpty()) {
            gaps.add(gap("NO_DOCUMENTS", "Dem Vorgang sind keine Dokumente zugeordnet."));
        }
        else if (documents.stream().anyMatch(document -> !"READY".equals(document.processingStatus())
                && !"ARCHIVED".equals(document.processingStatus())
                && !"COMPLETED".equals(document.processingStatus()))) {
            gaps.add(gap("DOCUMENTS_NOT_READY",
                    "Mindestens ein Dokument ist noch nicht verarbeitet oder geprüft; der Beleg "
                            + "ist damit nicht belastbar."));
        }
        if (counterparty == null) {
            gaps.add(gap("NO_COUNTERPARTY",
                    "Zur Gegenseite ist kein Kontakt hinterlegt."));
        }
        if (deadlines.isEmpty()) {
            gaps.add(gap("NO_DEADLINE",
                    "Im Vorgang ist keine aktive Frist hinterlegt."));
        }
        if (caseParticipants.isEmpty()) {
            gaps.add(gap("NO_PARTICIPANTS",
                    "Es sind keine Beteiligten mit Zugriff auf den Vorgang erfasst."));
        }
        if (disputeAmount == null) {
            gaps.add(gap("NO_DISPUTE_AMOUNT",
                    "Es ist kein Streitwert angegeben; die Akte nennt keinen Betrag."));
        }
        if (jurisdiction == null) {
            gaps.add(gap("NO_JURISDICTION",
                    "Es ist kein Gerichtsstand angegeben."));
        }

        List<Map<String, Object>> chronology = chronology(events, caseRecord, documents);
        List<Map<String, Object>> deadlineRows = new ArrayList<>();
        for (Deadline deadline : deadlines) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", deadline.id().toString());
            row.put("title", deadline.title());
            row.put("type", deadline.type());
            row.put("dueDate", deadline.dueDate() == null ? null : deadline.dueDate().toString());
            row.put("daysLeft", deadline.daysLeft());
            row.put("overdue", deadline.daysLeft() < 0);
            row.put("taskStatus", deadline.taskStatus());
            deadlineRows.add(row);
        }

        List<Map<String, Object>> tableOfContents = new ArrayList<>();
        tableOfContents.add(section("1. Vorgang", 1));
        tableOfContents.add(section("2. Chronologie", chronology.size()));
        tableOfContents.add(section("3. Belege", documents.size()));
        tableOfContents.add(section("4. Fristen", deadlines.size()));
        tableOfContents.add(section("5. Beteiligte", caseParticipants.size()));
        tableOfContents.add(section("6. Offene Aufgaben", openTasks.size()));
        tableOfContents.add(section("7. Lücken", gaps.size()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("caseId", caseRecord.getId().toString());
        payload.put("reference", caseRecord.getReference());
        payload.put("title", caseRecord.getTitle());
        payload.put("status", caseRecord.getStatus() == null ? null
                : caseRecord.getStatus().name());
        payload.put("openedAt", caseRecord.getOpenedAt() == null ? null
                : caseRecord.getOpenedAt().toString());
        payload.put("dueAt", caseRecord.getDueAt() == null ? null : caseRecord.getDueAt().toString());
        payload.put("disputeAmount", disputeAmount == null ? null : disputeAmount.toString());
        payload.put("jurisdiction", jurisdiction);
        payload.put("counterparty", counterparty);
        payload.put("tableOfContents", tableOfContents);
        payload.put("chronology", chronology);
        payload.put("documents", documents.stream().map(document -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("documentId", document.documentId().toString());
            row.put("filename", document.filename());
            row.put("documentNumber", document.documentNumber());
            row.put("processingStatus", document.processingStatus());
            row.put("primary", document.primary());
            return row;
        }).toList());
        payload.put("participants", caseParticipants.stream().map(participant -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", participant.getUserId() == null ? null
                    : participant.getUserId().toString());
            row.put("contactId", participant.getContactId() == null ? null
                    : participant.getContactId().toString());
            row.put("role", participant.getRole() == null ? null : participant.getRole().name());
            row.put("accessLevel", participant.getAccessLevel() == null ? null
                    : participant.getAccessLevel().name());
            row.put("grantedAt", participant.getGrantedAt() == null ? null
                    : participant.getGrantedAt().toString());
            return row;
        }).toList());
        payload.put("deadlines", deadlineRows);
        payload.put("openTasks", openTasks);
        payload.put("gaps", gaps);
        payload.put("note", "Die Akte dient der Vorbereitung und ersetzt keine anwaltliche "
                + "Bewertung.");

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND, caseRecord.getId().toString());
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("litigation-file", artifact.id().toString(),
                "Streitfall-Akte " + caseRecord.getReference() + ": " + documents.size()
                        + " Beleg(e), " + deadlines.size() + " Frist(en), " + gaps.size()
                        + " Lücke(n)");

        int created = 0;
        for (Deadline deadline : deadlines) {
            if (deadline.daysLeft() > URGENT_DAYS) {
                continue;
            }
            context.addProduced("deadline", deadline.id().toString(),
                    (deadline.daysLeft() < 0 ? "Überfällig: " : "Fällig in " + deadline.daysLeft()
                            + " Tagen: ") + deadline.title());
        }
        created += create(context, "Streitfall-Akte prüfen: " + caseRecord.getReference(),
                "Die Akte für " + caseRecord.getTitle() + " ist zusammengestellt. Lücken: "
                        + (gaps.isEmpty() ? "keine" : String.join("; ", gaps.stream()
                                .map(gap -> String.valueOf(gap.get("message"))).toList()))
                        + ". Prüfung durch eine fachkundige Person, gegebenenfalls anwaltliche "
                        + "Bewertung.",
                "Die Akte ist geprüft und die nächsten Schritte sind entschieden.",
                AgentInputs.dueAt(today, 3), Task.Priority.HIGH,
                List.of("streitfall", "pruefung"), caseRecord.getId(), context.actorUserId());
        for (Deadline deadline : deadlines) {
            if (deadline.daysLeft() > URGENT_DAYS) {
                continue;
            }
            created += create(context,
                    "Frist im Streitfall " + caseRecord.getReference() + ": "
                            + AgentInputs.truncate(deadline.title(), 250),
                    "Frist " + deadline.dueDate() + " (" + deadline.daysLeft() + " Tage). "
                            + "Akte und Belege prüfen; die Frist ist keine anwaltliche Empfehlung.",
                    "Die Frist ist gewahrt oder eine Verlängerung ist dokumentiert.",
                    deadline.dueDate() == null ? AgentInputs.dueAt(today, 1)
                            : AgentInputs.dueAt(deadline.dueDate(), 0),
                    deadline.daysLeft() < 0 ? Task.Priority.CRITICAL : Task.Priority.HIGH,
                    List.of("streitfall", "frist"), caseRecord.getId(), context.actorUserId());
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("caseId", caseRecord.getId().toString());
        meta.put("documents", String.valueOf(documents.size()));
        meta.put("events", String.valueOf(events.size()));
        meta.put("deadlines", String.valueOf(deadlines.size()));
        meta.put("urgentDeadlines", String.valueOf(deadlines.stream()
                .filter(deadline -> deadline.daysLeft() <= URGENT_DAYS).count()));
        meta.put("gaps", String.valueOf(gaps.size()));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        meta.put("requiresLegalReview", "true");
        return AgentResult.done("Streitfall-Akte " + caseRecord.getReference() + ": "
                + documents.size() + " Beleg(e), " + chronology.size() + " Chronologie-Eintrag/"
                + "Einträge, " + deadlines.size() + " Frist(en), " + gaps.size() + " Lücke(n), "
                + created + " Aufgabe(n). Die Akte ersetzt keine anwaltliche Bewertung.",
                context.produced(), meta);
    }

    private int create(AgentContext context, String title, String description,
                       String completionCriteria, java.time.Instant dueAt, Task.Priority priority,
                       List<String> tags, UUID caseId, UUID assignee) {
        AgentTaskFactory.Request request = new AgentTaskFactory.Request(title, description,
                completionCriteria, dueAt, priority, tags, caseId, assignee);
        var optionalTask = tasks.createIfAbsent(context, request);
        if (optionalTask.isPresent()) {
            AgentTaskFactory.produce(context, optionalTask.get());
            return 1;
        }
        return 0;
    }

    private List<Deadline> deadlines(UUID tenantId, UUID caseId, LocalDate today) {
        return jdbc.query("""
                select d.id, t.title, d.deadline_type,
                       coalesce(d.due_date, (d.due_at at time zone 'Europe/Berlin')::date) as due_date,
                       t.status
                from task_deadlines d
                join tasks t on t.id = d.task_id and t.deleted_at is null
                where d.tenant_id = ? and d.is_active
                  and coalesce(d.case_id, t.case_id) = ?
                order by due_date nulls last
                """, (rs, rowNum) -> {
                    LocalDate dueDate = AgentInputs.toLocalDate(rs.getObject("due_date"));
                    return new Deadline(rs.getObject("id", UUID.class), rs.getString("title"),
                            rs.getString("deadline_type"), dueDate,
                            dueDate == null ? Long.MAX_VALUE
                                    : ChronoUnit.DAYS.between(today, dueDate),
                            rs.getString("status"));
                }, tenantId, caseId);
    }

    private List<Map<String, Object>> openTasks(UUID tenantId, UUID caseId) {
        return jdbc.queryForList("""
                select id::text as id, title, status, due_at
                from tasks
                where tenant_id = ? and case_id = ? and deleted_at is null
                  and status not in ('DONE', 'CANCELLED')
                order by due_at nulls last, created_at
                limit 100
                """, tenantId, caseId);
    }

    private Map<String, Object> counterparty(UUID tenantId, UUID contactId) {
        if (contactId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, display_name, email::text as email, phone
                from contacts
                where tenant_id = ? and id = ? and deleted_at is null
                """, tenantId, contactId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.getFirst();
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("contactId", String.valueOf(row.get("id")));
        party.put("displayName", row.get("display_name"));
        party.put("email", row.get("email"));
        party.put("phone", row.get("phone"));
        return party;
    }

    private static List<Map<String, Object>> chronology(List<CaseEvent> events, Case caseRecord,
                                                        List<CaseDocumentRepository.LinkedDocument>
                                                                documents) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (CaseEvent event : events) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", event.getOccurredAt() == null ? null
                    : event.getOccurredAt().toString());
            entry.put("type", event.getEventType() == null ? "EREIGNIS"
                    : event.getEventType().name());
            entry.put("title", event.getTitle() == null ? "Ereignis" : event.getTitle());
            entry.put("description", event.getDescription());
            entries.add(entry);
        }
        if (caseRecord.getOpenedAt() != null) {
            Map<String, Object> opened = new LinkedHashMap<>();
            opened.put("date", caseRecord.getOpenedAt().toString());
            opened.put("type", "VORGANG_ERÖFFNET");
            opened.put("title", "Vorgang eröffnet");
            opened.put("description", caseRecord.getReference());
            entries.add(opened);
        }
        for (CaseDocumentRepository.LinkedDocument document : documents) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", null);
            entry.put("type", "DOKUMENT");
            entry.put("title", document.filename());
            entry.put("description", document.documentNumber());
            entries.add(entry);
        }
        entries.sort(Comparator.comparing(
                (Map<String, Object> entry) -> entry.get("date") == null ? "" :
                        String.valueOf(entry.get("date"))).reversed());
        return entries.stream().limit(MAX_CHRONOLOGY).toList();
    }

    private static Map<String, Object> section(String title, int entries) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("title", title);
        section.put("entries", entries);
        return section;
    }

    private static Map<String, Object> gap(String code, String message) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("code", code);
        gap.put("message", message);
        return gap;
    }
}
