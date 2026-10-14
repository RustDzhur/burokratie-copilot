package de.buerokratiecopilot.cases;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.cases.domain.CaseParticipant;
import de.buerokratiecopilot.cases.domain.CaseStatusMachine;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Moving a case through its lifecycle (spec §11.2, §11.3).
 *
 * <p>The rule this service exists for: <strong>a case is not closed by accident.</strong> Closing
 * means a matter is finished, and the platform refuses to state that while work is still open — an
 * unfinished task or a document the case type requires but nobody attached. Closing anyway is possible,
 * because reality sometimes demands it, but only as a deliberate act: the caller must acknowledge the
 * open work, must give a reason from the fixed list, and must say in words why.</p>
 *
 * <p>One combination is refused outright whatever the caller says: a case cannot be closed with the
 * reason "outcome achieved" while obligations are still open. That would be a claim the platform can
 * see to be false, and recording it would make the case history lie.</p>
 */
@Service
public class CaseLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CaseLifecycleService.class);

    /** Why a case was closed. The values are fixed by the schema so reports can rely on them. */
    public enum CloseReason {
        OUTCOME_ACHIEVED, USER_DECISION, CANCELLED, DUPLICATE, NOT_REQUIRED, OTHER
    }

    private final CaseRepository cases;
    private final CaseService caseService;
    private final JdbcTemplate jdbc;
    private final CaseCriteriaService criteria;

    public CaseLifecycleService(CaseRepository cases, CaseService caseService, JdbcTemplate jdbc, CaseCriteriaService criteria) {
        this.criteria = criteria;
        this.cases = cases;
        this.caseService = caseService;
        this.jdbc = jdbc;
    }

    /** An obligation that is still open and therefore blocks an honest closure. */
    public record OpenObligation(String kind, String description) {
    }

    /** What is still open before a case can be closed. */
    public record Obligations(List<OpenObligation> open, int openTasks, List<String> missingDocuments) {

        public boolean isEmpty() {
            return open.isEmpty();
        }

        public List<String> descriptions() {
            return open.stream().map(OpenObligation::description).toList();
        }
    }

    /** A requested transition. */
    public record TransitionRequest(Case.Status target, String reason, CloseReason closeReason,
                                    String outcome, String note, boolean acknowledgeOpenWork) {
    }

    /**
     * What is still open on a case.
     *
     * <p>Tasks that are neither done nor cancelled, and documents the case type declares as required
     * that are not attached. Both are facts about the case, not opinions, which is why they can be
     * used to refuse a closure.</p>
     */
    @Transactional(readOnly = true)
    public Obligations obligationsOf(Case caseRecord) {
        List<OpenObligation> open = new ArrayList<>();

        List<Map<String, Object>> tasks = jdbc.queryForList("""
                select title, status, due_at from tasks
                where case_id = ? and status not in ('DONE', 'CANCELLED')
                order by due_at nulls last, created_at
                """, caseRecord.getId());
        for (Map<String, Object> task : tasks) {
            open.add(new OpenObligation("TASK", "Aufgabe \"%s\" ist noch %s"
                    .formatted(task.get("title"), task.get("status"))));
        }

        List<String> missing = missingRequiredDocuments(caseRecord);
        for (String document : missing) {
            open.add(new OpenObligation("DOCUMENT", "Erforderliche Unterlage fehlt: " + document));
        }

        // The completion criteria of the case that are not yet met: each is one fact the case still lacks.
        for (Map<String, Object> criterion : criteria.open(caseRecord.getId())) {
            open.add(new OpenObligation("CRITERION", "Abschlusskriterium offen: " + criterion.get("description")));
        }

        return new Obligations(open, tasks.size(), missing);
    }

    /** Required documents of the case type that are not attached to the case. */
    @Transactional(readOnly = true)
    public List<String> missingRequiredDocuments(Case caseRecord) {
        if (caseRecord.getCaseTypeId() == null) {
            return List.of();
        }
        return jdbc.query("""
                select coalesce(r.label ->> 'de', dt.code, r.requirement_key) as name
                from case_type_document_requirements r
                left join document_types dt on dt.id = r.document_type_id
                where r.case_type_id = ? and r.is_mandatory = true
                  and not exists (
                      select 1 from case_documents cd
                      join documents d on d.id = cd.document_id
                      where cd.case_id = ? and d.deleted_at is null
                        and (r.document_type_id is null or d.document_type_id = r.document_type_id))
                order by r.sort_order
                """, (rs, rowNum) -> rs.getString("name"), caseRecord.getCaseTypeId(),
                caseRecord.getId());
    }

    /**
     * Moves a case to another state.
     *
     * @throws ApiException when the transition is not allowed, or when a closure is attempted without
     *                      acknowledging open work
     */
    @Transactional
    public Case changeStatus(UUID caseId, TransitionRequest request, UUID userId, boolean seeAll) {
        Case caseRecord = cases.findById(caseId)
                .filter(candidate -> candidate.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("case"));
        caseService.requireAccess(caseRecord, userId, CaseParticipant.AccessLevel.WRITE, seeAll);

        Case.Status from = caseRecord.getStatus();
        Case.Status to = request.target();
        if (to == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A target status is required");
        }
        CaseStatusMachine.requireTransition(from, to);

        if (CaseStatusMachine.isClosing(to)) {
            // The platform looks at its facts first: a task that is done, a document that arrived, a letter that was delivered.
            criteria.evaluate(caseId);
            if (to == Case.Status.COMPLETED && !criteria.open(caseId).isEmpty()) {
                // COMPLETED says the result was reached; with a criterion open that is a claim the platform can see to be
                // false. Closing for another reason stays possible, as a deliberate act (status CLOSED).
                throw ApiException.conflict("The case cannot be completed while its completion criteria are open: "
                        + String.join("; ", criteria.open(caseId).stream().map(c -> String.valueOf(c.get("description"))).toList())
                        + ". Meet or give up each criterion, or close the case deliberately instead.");
            }
            applyClosure(caseRecord, request, userId, from);
        }
        else {
            reopenIfNecessary(caseRecord, request, userId, from);
        }

        caseRecord.setStatus(to);
        if (CaseStatusMachine.isWaiting(to)) {
            caseRecord.setWaitingSince(Instant.now());
        }
        else {
            caseRecord.setWaitingSince(null);
        }

        Case saved = cases.save(caseRecord);

        CaseEvent.EventType eventType = CaseStatusMachine.isClosing(to) ? CaseEvent.EventType.CASE_CLOSED
                : (to == Case.Status.REOPENED ? CaseEvent.EventType.CASE_REOPENED
                        : CaseEvent.EventType.STATUS_CHANGED);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", from.name());
        payload.put("to", to.name());
        if (request.reason() != null) {
            payload.put("reason", request.reason());
        }
        if (request.closeReason() != null) {
            payload.put("closeReason", request.closeReason().name());
        }
        if (request.acknowledgeOpenWork()) {
            // Recorded explicitly, because "who closed this despite open work and why" is the question
            // asked afterwards. The note travels with it: an acknowledgement without the words that
            // came with it is not reviewable.
            payload.put("closedWithOpenWork", true);
            if (request.note() != null && !request.note().isBlank()) {
                payload.put("note", request.note().strip());
            }
        }

        caseService.record(saved, eventType, userId,
                "Status: %s → %s".formatted(from, to), payload);
        log.info("Case {} moved from {} to {} by user {}", caseRecord.getReference(), from, to, userId);
        return saved;
    }

    /** Applies a closure: reason, explicit acknowledgement of open work, and the closing fields. */
    private void applyClosure(Case caseRecord, TransitionRequest request, UUID userId,
                              Case.Status from) {
        CloseReason closeReason = request.closeReason() == null ? CloseReason.USER_DECISION
                : request.closeReason();
        Obligations obligations = obligationsOf(caseRecord);

        if (!obligations.isEmpty()) {
            if (closeReason == CloseReason.OUTCOME_ACHIEVED) {
                // The platform can see that this is not true, so it will not record it.
                throw ApiException.conflict(
                        "The outcome cannot be recorded as achieved while obligations are still open: "
                                + String.join("; ", obligations.descriptions())
                                + ". Close the case for another reason, or finish the open work first.");
            }
            if (!request.acknowledgeOpenWork()) {
                throw ApiException.conflict(
                        "The case still has open obligations: " + String.join("; ",
                                obligations.descriptions())
                                + ". Confirm the closure explicitly and say why to proceed.");
            }
            if (request.note() == null || request.note().isBlank()) {
                // A bare acknowledgement is not a decision anybody can review later.
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Closing a case with open obligations requires a note explaining why");
            }
        }

        caseRecord.setClosedAt(Instant.now());
        caseRecord.setClosedBy(userId);
        caseRecord.setCloseReason(closeReason.name());
        if (request.outcome() != null && !request.outcome().isBlank()) {
            caseRecord.setOutcome(request.outcome().strip());
        }
    }

    /**
     * Leaves a closed state.
     *
     * <p>The schema ties {@code closed_at} to the two closing states, so reopening has to clear it.
     * The previous reason moves into the timeline rather than being lost, which is where somebody looks
     * for it anyway.</p>
     */
    private void reopenIfNecessary(Case caseRecord, TransitionRequest request, UUID userId,
                                   Case.Status from) {
        if (CaseStatusMachine.isClosing(from)) {
            if (request.reason() == null || request.reason().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Reopening a closed case requires a reason");
            }
            caseRecord.setClosedAt(null);
            caseRecord.setClosedBy(null);
            caseRecord.setCloseReason(null);
        }
    }

    /** States this case may move to, so an interface never offers an impossible action. */
    @Transactional(readOnly = true)
    public List<Case.Status> possibleTargets(UUID caseId, UUID userId, boolean seeAll) {
        Case caseRecord = cases.findById(caseId).orElseThrow(() -> ApiException.notFound("case"));
        CaseParticipant.AccessLevel level = caseService.accessLevel(caseRecord, userId, seeAll);
        if (level == null || !level.allows(CaseParticipant.AccessLevel.WRITE)) {
            return List.of();
        }
        return CaseStatusMachine.allowedFrom(caseRecord.getStatus()).stream()
                .sorted(java.util.Comparator.comparing(Enum::name))
                .toList();
    }

    /** Parses a close reason that came from a client. */
    public static CloseReason parseCloseReason(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CloseReason.valueOf(value.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Unknown close reason: " + value + "; expected one of "
                            + java.util.Arrays.toString(CloseReason.values()));
        }
    }
}
