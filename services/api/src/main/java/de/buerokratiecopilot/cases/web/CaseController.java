package de.buerokratiecopilot.cases.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.cases.CaseDocumentRepository;
import de.buerokratiecopilot.cases.CaseService;
import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseEvent;
import de.buerokratiecopilot.cases.domain.CaseParticipant;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Case API (spec §11, §20).
 *
 * <p>Access is decided per case by {@link CaseService}; the endpoint asks it rather than reimplementing
 * the rule. A user without access receives "not found", the same answer as for a case that does not
 * exist, so the API does not confirm the existence of matters they may not see.</p>
 */
@RestController
@RequestMapping("/api/v1/cases")
@Tag(name = "Cases", description = "Matters, their participants and their history")
public class CaseController {

    private final CaseService cases;

    public CaseController(CaseService cases) {
        this.cases = cases;
    }

    /** What a client submits to open a case. */
    public record CreateCaseRequest(String title, String caseTypeCode, String description,
                                    String priority, String responsibleUserId,
                                    String counterpartyContactId, String externalReference,
                                    String authorityContactId, Instant dueAt, List<String> tags) {
    }

    /** What a client submits to grant access. */
    public record AddParticipantRequest(String userId, String contactId, String role, String accessLevel,
                                        Instant expiresAt) {
    }

    public record ParticipantResponse(String id, String userId, String contactId, String role,
                                      String accessLevel, Instant grantedAt, Instant expiresAt,
                                      boolean active) {

        static ParticipantResponse from(Map<String, Object> row) {
            return new ParticipantResponse(string(row.get("id")), string(row.get("userId")),
                    string(row.get("contactId")), string(row.get("role")), string(row.get("accessLevel")),
                    (Instant) row.get("grantedAt"), (Instant) row.get("expiresAt"),
                    Boolean.TRUE.equals(row.get("active")));
        }

        private static String string(Object value) {
            return value == null ? null : value.toString();
        }
    }

    public record DocumentResponse(String documentId, String filename, String documentNumber,
                                   String processingStatus, boolean primary, String linkOrigin,
                                   Double confidence) {

        static DocumentResponse from(CaseDocumentRepository.LinkedDocument document) {
            return new DocumentResponse(document.documentId().toString(), document.filename(),
                    document.documentNumber(), document.processingStatus(), document.primary(),
                    document.linkOrigin(), document.confidence());
        }
    }

    public record EventResponse(String id, String type, String summary, String actorUserId,
                                Instant occurredAt, Map<String, Object> payload) {

        static EventResponse from(CaseEvent event) {
            return new EventResponse(event.getId().toString(), event.getEventType().name(),
                    event.getTitle(), event.getActorUserId() == null ? null
                            : event.getActorUserId().toString(), event.getOccurredAt(), event.getMetadata());
        }
    }

    public record CaseResponse(String id, String reference, String title, String status, String priority,
                               String typeCode, String typeName, String description, String responsibleUserId,
                               String counterpartyContactId, String externalReference, Instant openedAt,
                               Instant dueAt, String outcome, Instant closedAt, List<String> tags,
                               long documentCount, String accessLevel) {

        static CaseResponse from(Case caseRecord, String typeCode, String typeName, long documentCount,
                                 String accessLevel) {
            return new CaseResponse(caseRecord.getId().toString(), caseRecord.getReference(),
                    caseRecord.getTitle(), caseRecord.getStatus().name(), caseRecord.getPriority().name(),
                    typeCode, typeName, caseRecord.getDescription(),
                    caseRecord.getResponsibleUserId() == null ? null
                            : caseRecord.getResponsibleUserId().toString(),
                    caseRecord.getCounterpartyContactId() == null ? null
                            : caseRecord.getCounterpartyContactId().toString(),
                    caseRecord.getExternalReference(), caseRecord.getOpenedAt(), caseRecord.getDueAt(),
                    caseRecord.getOutcome(), caseRecord.getClosedAt(), caseRecord.getTags(), documentCount,
                    accessLevel);
        }
    }

    public record CaseDetailResponse(CaseResponse caseRecord, List<ParticipantResponse> participants,
                                     List<DocumentResponse> documents, List<EventResponse> timeline,
                                     List<String> requiredDocuments) {
    }

    @PostMapping
    @PreAuthorize("hasAuthority('cases.create')")
    @Operation(summary = "Open a case; the creator becomes its owner")
    public ResponseEntity<CaseResponse> create(@RequestBody CreateCaseRequest request) {
        UUID userId = CurrentUser.required();
        CaseService.CreateRequest createRequest = new CaseService.CreateRequest(
                RequestContext.tenantId().orElseThrow(),
                request.title(), request.caseTypeCode(), request.description(),
                request.priority() == null ? null : Case.Priority.valueOf(request.priority()),
                uuidOrNull(request.responsibleUserId()), uuidOrNull(request.counterpartyContactId()),
                request.externalReference(), uuidOrNull(request.authorityContactId()), request.dueAt(),
                request.tags());

        Case created = cases.createWithRetry(createRequest, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CaseResponse.from(created, request.caseTypeCode(), null, 0, "FULL"));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('cases.view')")
    @Operation(summary = "Cases the caller may see")
    public ResponseEntity<Page<CaseResponse>> list(
            @PageableDefault(size = 25, sort = "openedAt") Pageable pageable) {
        UUID userId = CurrentUser.required();
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        boolean seeAll = hasPermission("cases.assign");

        return ResponseEntity.ok(cases.listVisible(tenantId, userId, seeAll, pageable)
                .map(caseRecord -> CaseResponse.from(caseRecord, null, null,
                        cases.detail(caseRecord.getId(), userId, seeAll).documents().size(),
                        cases.accessLevel(caseRecord, userId, seeAll).name())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('cases.view')")
    @Operation(summary = "The case card: participants, documents, history and required documents")
    public ResponseEntity<CaseDetailResponse> detail(@PathVariable UUID id) {
        UUID userId = CurrentUser.required();
        CaseService.Detail detail = cases.detail(id, userId, hasPermission("cases.assign"));

        return ResponseEntity.ok(new CaseDetailResponse(
                CaseResponse.from(detail.caseRecord(),
                        detail.type() == null ? null : detail.type().getCode(),
                        detail.type() == null ? null : detail.type().nameIn("de"),
                        detail.documents().size(), detail.accessLevel().name()),
                cases.participantRows(id).stream().map(ParticipantResponse::from).toList(),
                detail.documents().stream().map(DocumentResponse::from).toList(),
                detail.timeline().stream().map(EventResponse::from).toList(),
                detail.requiredDocuments()));
    }

    @PostMapping("/{id}/participants")
    @PreAuthorize("hasAuthority('cases.edit')")
    @Operation(summary = "Grant access to a user of the tenant or to an external contact")
    public ResponseEntity<ParticipantResponse> addParticipant(@PathVariable UUID id,
                                                              @RequestBody AddParticipantRequest request) {
        UUID userId = CurrentUser.required();
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        boolean seeAll = hasPermission("cases.assign");

        cases.addParticipant(id, tenantId, uuidOrNull(request.userId()), uuidOrNull(request.contactId()),
                request.role() == null ? null : CaseParticipant.Role.valueOf(request.role()),
                request.accessLevel() == null ? null
                        : CaseParticipant.AccessLevel.valueOf(request.accessLevel()),
                request.expiresAt(), userId, seeAll);

        Map<String, Object> created = cases.participantRows(id).getLast();
        return ResponseEntity.status(HttpStatus.CREATED).body(ParticipantResponse.from(created));
    }

    @PatchMapping("/{id}/participants/{participantId}")
    @PreAuthorize("hasAuthority('cases.edit')")
    @Operation(summary = "Change an existing grant of access")
    public ResponseEntity<ParticipantResponse> updateParticipant(@PathVariable UUID id,
                                                                 @PathVariable UUID participantId,
                                                                 @RequestBody AddParticipantRequest request) {
        UUID userId = CurrentUser.required();
        cases.updateParticipant(id, participantId,
                request.accessLevel() == null ? null
                        : CaseParticipant.AccessLevel.valueOf(request.accessLevel()),
                request.role() == null ? null : CaseParticipant.Role.valueOf(request.role()),
                request.expiresAt(), userId, hasPermission("cases.assign"));

        return ResponseEntity.ok(ParticipantResponse.from(cases.participantRows(id).stream()
                .filter(row -> participantId.toString().equals(String.valueOf(row.get("id"))))
                .findFirst().orElseThrow()));
    }

    @DeleteMapping("/{id}/participants/{participantId}")
    @PreAuthorize("hasAuthority('cases.edit')")
    @Operation(summary = "Revoke a grant; the row stays, so history remains answerable")
    public ResponseEntity<Void> revokeParticipant(@PathVariable UUID id, @PathVariable UUID participantId) {
        cases.revokeParticipant(id, participantId, CurrentUser.required(), hasPermission("cases.assign"));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasAuthority('cases.view')")
    @Operation(summary = "How many cases are open, overdue and in total")
    public ResponseEntity<Map<String, Object>> statistics() {
        UUID userId = CurrentUser.required();
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        return ResponseEntity.ok(cases.statistics(tenantId, userId, hasPermission("cases.assign")));
    }

    /** Whether the caller holds a permission, used only for the tenant-wide view. */
    private static boolean hasPermission(String permission) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> permission.equals(authority.getAuthority()));
    }

    private static UUID uuidOrNull(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
