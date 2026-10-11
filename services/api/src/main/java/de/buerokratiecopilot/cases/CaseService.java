package de.buerokratiecopilot.cases;

import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.cases.domain.CaseParticipant;
import de.buerokratiecopilot.cases.domain.CaseType;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Cases: the thread a matter is worked along (spec §11.1, §12).
 *
 * <p>Two rules shape this service.</p>
 *
 * <p><strong>Access is per case and per person.</strong> A user sees a case when they take part in it —
 * as owner, as responsible person or as a participant — and not because of their job title. A tax
 * matter is not visible to the whole company because everyone shares a role, and the specification
 * requires access to be limited to the participants.</p>
 *
 * <p><strong>Every change leaves a trace.</strong> Creating a case, changing its status or granting
 * access writes an event. The timeline is the answer to "what is the state of this matter", which is
 * the question a user actually has, and a status change without its reason is a change nobody can
 * explain three months later.</p>
 */
@Service
public class CaseService {

    private static final Logger log = LoggerFactory.getLogger(CaseService.class);

    /** Prefix of the generated case number; the format is quoted in correspondence, so it is stable. */
    private static final String REFERENCE_PREFIX = "V";

    private final CaseRepository cases;
    private final CaseParticipantRepository participants;
    private final CaseEventRepository events;
    private final CaseTypeRepository caseTypes;
    private final CaseDocumentRepository caseDocuments;
    private final JdbcTemplate jdbc;

    public CaseService(CaseRepository cases, CaseParticipantRepository participants,
                       CaseEventRepository events, CaseTypeRepository caseTypes,
                       CaseDocumentRepository caseDocuments, JdbcTemplate jdbc) {
        this.cases = cases;
        this.participants = participants;
        this.events = events;
        this.caseTypes = caseTypes;
        this.caseDocuments = caseDocuments;
        this.jdbc = jdbc;
    }

    /** What is needed to open a case. */
    public record CreateRequest(UUID tenantId, String title, String caseTypeCode, String description,
                                Case.Priority priority, UUID responsibleUserId, UUID counterpartyContactId,
                                String externalReference, UUID authorityContactId, Instant dueAt,
                                List<String> tags) {
    }

    /** Everything a case card shows. */
    public record Detail(Case caseRecord, CaseType type, List<CaseParticipant> participants,
                         List<CaseDocumentRepository.LinkedDocument> documents,
                         List<CaseEvent> timeline, List<String> requiredDocuments,
                         CaseParticipant.AccessLevel accessLevel) {
    }

    /**
     * Opens a case.
     *
     * <p>The creator becomes its owner with full access. Without that a case could be created that
     * nobody can see — which is exactly the failure mode a strict access model produces when the
     * creator is forgotten.</p>
     */
    @Transactional
    public Case create(CreateRequest request, UUID userId) {
        if (request.title() == null || request.title().isBlank()) {
            throw new ApiException(de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST,
                    "A case needs a title");
        }

        CaseType type = null;
        if (request.caseTypeCode() != null && !request.caseTypeCode().isBlank()) {
            type = caseTypes.findByCode(request.caseTypeCode())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown case type: " + request.caseTypeCode()));
        }

        Case caseRecord = new Case(UUID.randomUUID(), request.tenantId(), nextReference(request.tenantId()),
                request.title().strip());
        caseRecord.setCaseTypeId(type == null ? null : type.getId());
        caseRecord.setDescription(request.description());
        caseRecord.setPriority(request.priority() != null ? request.priority()
                : priorityOf(type).orElse(Case.Priority.NORMAL));
        caseRecord.setOwnerUserId(userId);
        caseRecord.setResponsibleUserId(request.responsibleUserId() != null ? request.responsibleUserId()
                : userId);
        caseRecord.setCounterpartyContactId(request.counterpartyContactId());
        caseRecord.setExternalReference(request.externalReference());
        caseRecord.setAuthorityContactId(request.authorityContactId());
        caseRecord.setDueAt(request.dueAt());
        caseRecord.setTags(request.tags());
        caseRecord.setCreatedBy(userId);

        Case saved = cases.save(caseRecord);

        CaseParticipant owner = new CaseParticipant(UUID.randomUUID(), request.tenantId(), saved.getId(),
                userId, null, CaseParticipant.Role.OWNER, CaseParticipant.AccessLevel.FULL);
        owner.setGrantedBy(userId);
        participants.save(owner);

        record(saved, CaseEvent.EventType.CASE_CREATED, userId,
                "Der Vorgang wurde angelegt", Map.of("reference", saved.getReference(),
                        "title", saved.getTitle()));
        log.info("Case {} created by user {}", saved.getReference(), userId);
        return saved;
    }

    /** Cases the user may see, newest first. */
    @Transactional(readOnly = true)
    public Page<Case> listVisible(UUID tenantId, UUID userId, boolean seeAll, Pageable pageable) {
        return seeAll ? cases.findAllInTenant(tenantId, pageable)
                : cases.findVisibleTo(tenantId, userId, pageable);
    }

    /**
     * The case card.
     *
     * @throws ApiException when the user may not see the case — the same answer as for a case that
     *                      does not exist, so an outsider cannot learn that it does
     */
    @Transactional(readOnly = true)
    public Detail detail(UUID caseId, UUID userId, boolean seeAll) {
        Case caseRecord = cases.findById(caseId).filter(c -> c.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.notFound("case"));
        CaseParticipant.AccessLevel level = accessLevel(caseRecord, userId, seeAll);
        if (level == null) {
            throw ApiException.notFound("case");
        }

        CaseType type = caseRecord.getCaseTypeId() == null ? null
                : caseTypes.findById(caseRecord.getCaseTypeId()).orElse(null);

        return new Detail(caseRecord, type, participants.findByCaseIdOrderByGrantedAtAsc(caseId),
                caseDocuments.findByCaseId(caseId), events.findTop20ByCaseIdOrderByOccurredAtDesc(caseId),
                requiredDocuments(caseRecord.getCaseTypeId()), level);
    }

    /**
     * Grants access.
     *
     * <p>Only somebody with write access may grant it, and a participant is either a user of the
     * tenant or an external contact — the schema enforces the second part and this method enforces the
     * first.</p>
     */
    @Transactional
    public CaseParticipant addParticipant(UUID caseId, UUID tenantId, UUID userId, UUID contactId,
                                          CaseParticipant.Role role, CaseParticipant.AccessLevel level,
                                          Instant expiresAt, UUID grantedBy, boolean seeAll) {
        Case caseRecord = cases.findById(caseId).orElseThrow(() -> ApiException.notFound("case"));
        requireAccess(caseRecord, grantedBy, CaseParticipant.AccessLevel.WRITE, seeAll);

        if (userId != null) {
            participants.findByCaseIdAndUserId(caseId, userId).ifPresent(existing -> {
                throw ApiException.duplicate("This user already takes part in the case");
            });
        }

        if ((userId == null) == (contactId == null)) {
            // Checked here so the caller receives a client error rather than the domain exception
            // surfacing as a server failure.
            throw new ApiException(de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST,
                    "A participant is either a user of the tenant or an external contact, "
                            + "never both and never neither");
        }

        CaseParticipant participant = new CaseParticipant(UUID.randomUUID(), tenantId, caseId, userId,
                contactId, role == null ? CaseParticipant.Role.PARTICIPANT : role,
                level == null ? CaseParticipant.AccessLevel.READ : level);
        participant.setGrantedBy(grantedBy);
        participant.setExpiresAt(expiresAt);
        CaseParticipant saved = participants.save(participant);

        record(caseRecord, CaseEvent.EventType.APPROVAL_GRANTED, grantedBy,
                "Zugriff wurde erteilt", Map.of("role", saved.getRole().name(),
                        "accessLevel", saved.getAccessLevel().name()));
        return saved;
    }

    /**
     * Changes an existing grant.
     *
     * <p>Changing access is a normal operation — an advisor moves from reading to writing, a
     * temporary grant gets an end date — and it is recorded like every other change, because "who
     * could change this in March" has to stay answerable.</p>
     */
    @Transactional
    public CaseParticipant updateParticipant(UUID caseId, UUID participantId,
                                             CaseParticipant.AccessLevel level,
                                             CaseParticipant.Role role, Instant expiresAt, UUID userId,
                                             boolean seeAll) {
        Case caseRecord = cases.findById(caseId).orElseThrow(() -> ApiException.notFound("case"));
        requireAccess(caseRecord, userId, CaseParticipant.AccessLevel.WRITE, seeAll);

        CaseParticipant participant = participants.findById(participantId)
                .filter(candidate -> candidate.getCaseId().equals(caseId))
                .orElseThrow(() -> ApiException.notFound("participant"));

        if (participant.getRole() == CaseParticipant.Role.OWNER
                && level != null && !level.allows(CaseParticipant.AccessLevel.FULL)) {
            throw ApiException.conflict("The owner of a case cannot lose full access to it");
        }

        Map<String, Object> before = Map.of("accessLevel", participant.getAccessLevel().name(),
                "role", participant.getRole().name());
        if (level != null) {
            participant.setAccessLevel(level);
        }
        if (role != null) {
            participant.setRole(role);
        }
        if (expiresAt != null) {
            participant.setExpiresAt(expiresAt);
        }
        CaseParticipant saved = participants.save(participant);

        record(caseRecord, CaseEvent.EventType.APPROVAL_GRANTED, userId, "Zugriff wurde geändert",
                Map.of("before", before, "after", Map.of("accessLevel", saved.getAccessLevel().name(),
                        "role", saved.getRole().name())));
        return saved;
    }

    /** Revokes a grant. The row stays, because "who could see this in March" must stay answerable. */
    @Transactional
    public void revokeParticipant(UUID caseId, UUID participantId, UUID userId, boolean seeAll) {
        Case caseRecord = cases.findById(caseId).orElseThrow(() -> ApiException.notFound("case"));
        requireAccess(caseRecord, userId, CaseParticipant.AccessLevel.WRITE, seeAll);

        CaseParticipant participant = participants.findById(participantId)
                .filter(candidate -> candidate.getCaseId().equals(caseId))
                .orElseThrow(() -> ApiException.notFound("participant"));
        if (participant.getRole() == CaseParticipant.Role.OWNER) {
            throw ApiException.conflict(
                    "The owner of a case cannot be removed from it; transfer the ownership instead");
        }
        participant.revoke();
        participants.save(participant);
    }

    /** Whether the user may see the case at all, and how much they may do. */
    @Transactional(readOnly = true)
    public CaseParticipant.AccessLevel accessLevel(Case caseRecord, UUID userId, boolean seeAll) {
        if (seeAll) {
            return CaseParticipant.AccessLevel.FULL;
        }
        if (userId == null) {
            return null;
        }
        if (userId.equals(caseRecord.getOwnerUserId())) {
            return CaseParticipant.AccessLevel.FULL;
        }
        if (userId.equals(caseRecord.getResponsibleUserId())) {
            return CaseParticipant.AccessLevel.WRITE;
        }
        Instant now = Instant.now();
        return participants.findByCaseIdOrderByGrantedAtAsc(caseRecord.getId()).stream()
                .filter(participant -> participant.isActive(now))
                .filter(participant -> userId.equals(participant.getUserId()))
                .map(CaseParticipant::getAccessLevel)
                .max(Enum::compareTo)
                .orElse(null);
    }

    /** The user must hold at least the required level, otherwise the request is refused. */
    @Transactional(readOnly = true)
    public void requireAccess(Case caseRecord, UUID userId, CaseParticipant.AccessLevel required,
                              boolean seeAll) {
        CaseParticipant.AccessLevel level = accessLevel(caseRecord, userId, seeAll);
        if (level == null || !level.allows(required)) {
            // Reported as forbidden rather than as not found here: the user already knows the case
            // exists, they were simply not allowed to change it.
            throw ApiException.forbidden("This action needs " + required + " access to the case");
        }
    }

    /** Records a timeline entry. */
    @Transactional
    public CaseEvent record(Case caseRecord, CaseEvent.EventType type, UUID actor, String summary,
                            Map<String, Object> payload) {
        CaseEvent event = new CaseEvent(UUID.randomUUID(), caseRecord.getTenantId(), caseRecord.getId(),
                type, actor, summary);
        event.setMetadata(payload);
        return events.save(event);
    }

    /** The documents a case of this type normally needs (seeded reference data). */
    @Transactional(readOnly = true)
    public List<String> requiredDocuments(UUID caseTypeId) {
        if (caseTypeId == null) {
            return List.of();
        }
        // The requirement points at a document type and carries its own label; resolving both gives
        // a user something readable even when a document type was renamed.
        return jdbc.query("""
                select coalesce(dt.code, r.requirement_key) as code,
                       r.label ->> 'de' as label,
                       r.is_mandatory
                from case_type_document_requirements r
                left join document_types dt on dt.id = r.document_type_id
                where r.case_type_id = ?
                order by r.sort_order
                """, (rs, rowNum) -> {
            String label = rs.getString("label");
            String code = rs.getString("code");
            String name = label == null || label.isBlank() ? code : label;
            return rs.getBoolean("is_mandatory") ? name : name + " (optional)";
        }, caseTypeId);
    }

    /** Case numbers per month, for the overview the specification asks for. */
    @Transactional(readOnly = true)
    public Map<String, Object> statistics(UUID tenantId, UUID userId, boolean seeAll) {
        Map<String, Object> statistics = new LinkedHashMap<>();
        List<Case.Status> open = List.of(Case.Status.NEW, Case.Status.IN_PROGRESS, Case.Status.REOPENED,
                Case.Status.WAITING_FOR_USER, Case.Status.WAITING_FOR_DOCUMENTS,
                Case.Status.WAITING_FOR_APPROVAL, Case.Status.WAITING_FOR_EXTERNAL_PARTY,
                Case.Status.ON_HOLD);

        Page<Case> visible = listVisible(tenantId, userId, seeAll,
                org.springframework.data.domain.PageRequest.of(0, 500));
        long openCount = visible.stream().filter(caseRecord -> open.contains(caseRecord.getStatus())).count();
        long overdue = visible.stream()
                .filter(caseRecord -> caseRecord.isOpen() && caseRecord.getDueAt() != null
                        && caseRecord.getDueAt().isBefore(Instant.now()))
                .count();

        statistics.put("open", openCount);
        statistics.put("overdue", overdue);
        statistics.put("total", visible.getTotalElements());
        return statistics;
    }

    /**
     * The next case number for a tenant.
     *
     * <p>The number is derived from the highest one in use rather than from a count, so deleting a
     * case cannot make the next one collide with an existing number. A collision is still possible
     * under concurrency, which is why {@link #create} retries — a case number that appears twice in
     * correspondence is worse than a slightly slower creation.</p>
     */
    String nextReference(UUID tenantId) {
        Integer highest = jdbc.queryForObject("""
                select coalesce(max(nullif(regexp_replace(reference, '^.*[^0-9]', ''), '')::int), 0)
                from cases
                where tenant_id = ? and reference ~ '^[A-Z]-[0-9]{4}-[0-9]+$'
                """, Integer.class, tenantId);
        int next = (highest == null ? 0 : highest) + 1;
        return "%s-%d-%04d".formatted(REFERENCE_PREFIX, Year.now().getValue(), next);
    }

    private static java.util.Optional<Case.Priority> priorityOf(CaseType type) {
        if (type == null || type.getDefaultPriority() == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Case.Priority.valueOf(type.getDefaultPriority()
                    .toUpperCase(Locale.ROOT)));
        }
        catch (IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }

    /** Retries a creation when the generated reference was taken by a concurrent request. */
    @Transactional
    public Case createWithRetry(CreateRequest request, UUID userId) {
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return create(request, userId);
            }
            catch (DataIntegrityViolationException ex) {
                last = ex;
                log.warn("Case reference collision on attempt {}, retrying", attempt + 1);
            }
        }
        throw new IllegalStateException("Could not allocate a case number", last);
    }

    /** All participants as DTO-friendly data, for the case card. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> participantRows(UUID caseId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CaseParticipant participant : participants.findByCaseIdOrderByGrantedAtAsc(caseId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", participant.getId());
            row.put("userId", participant.getUserId());
            row.put("contactId", participant.getContactId());
            row.put("role", participant.getRole().name());
            row.put("accessLevel", participant.getAccessLevel().name());
            row.put("grantedAt", participant.getGrantedAt());
            row.put("expiresAt", participant.getExpiresAt());
            row.put("revokedAt", participant.getRevokedAt());
            row.put("active", participant.isActive(Instant.now()));
            rows.add(row);
        }
        return rows;
    }
}
