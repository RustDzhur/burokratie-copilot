package de.buerokratiecopilot.contracts.web;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.contracts.ContractService;
import de.buerokratiecopilot.contracts.domain.Contract;
import de.buerokratiecopilot.contracts.domain.ContractClause;
import de.buerokratiecopilot.contracts.domain.ContractParty;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Contract API (spec §13, §20).
 *
 * <p>Every clause response carries its snippet, its page and its interpretation state. The state is not
 * decoration: "detected text", "an interpretation nobody has checked" and "a verified reading" are
 * three different claims, and a user who cannot tell them apart will treat the middle one as the last.</p>
 */
@RestController
@RequestMapping("/api/v1/contracts")
@Tag(name = "Contracts", description = "Contracts, their clauses and the boundary of interpretation")
public class ContractController {

    private final ContractService contracts;

    public ContractController(ContractService contracts) {
        this.contracts = contracts;
    }

    public record CreateContractRequest(String title, String contractNumber, String contractType,
                                        String caseId, String counterpartyContactId, LocalDate startDate,
                                        LocalDate endDate, LocalDate signedDate, Boolean autoRenewal,
                                        Integer renewalPeriodMonths, Integer noticePeriodValue,
                                        String noticePeriodUnit, BigDecimal costAmount,
                                        String costCurrency, String costInterval,
                                        String sourceDocumentId, List<String> tags) {
    }

    public record ClauseRequest(String clauseKey, String clauseType, String title, String clauseText,
                                Integer sourcePage, String sourceSnippet, BigDecimal confidence,
                                boolean critical) {
    }

    public record InterpretationRequest(String interpretation, String source) {
    }

    public record PartyRequest(String partyRole, String partyName, String contactId, boolean primary) {
    }

    public record ClauseResponse(String id, String clauseKey, String clauseType, String title,
                                 String clauseText, Integer sourcePage, String sourceSnippet,
                                 Double confidence, boolean critical, String interpretation,
                                 String interpretationSource, boolean interpretationVerified,
                                 String state) {

        static ClauseResponse from(ContractService.ClauseView view) {
            ContractClause clause = view.clause();
            return new ClauseResponse(clause.getId().toString(), clause.getClauseKey(),
                    clause.getClauseType().name(), clause.getTitle(), clause.getClauseText(),
                    clause.getSourcePage(), clause.getSourceSnippet(),
                    clause.getConfidence() == null ? null : clause.getConfidence().doubleValue(),
                    clause.isCritical(), clause.getInterpretation(), clause.getInterpretationSource(),
                    view.verified(), view.interpretationState());
        }
    }

    public record PartyResponse(String id, String partyRole, String partyName, String contactId,
                                boolean primary) {

        static PartyResponse from(ContractParty party) {
            return new PartyResponse(party.getId().toString(), party.getPartyRole(),
                    party.getPartyName(),
                    party.getContactId() == null ? null : party.getContactId().toString(),
                    party.isPrimary());
        }
    }

    public record ContractResponse(String id, String title, String contractNumber, String contractType,
                                   String status, LocalDate startDate, LocalDate endDate,
                                   Boolean autoRenewal, Integer renewalPeriodMonths,
                                   Integer noticePeriodValue, String noticePeriodUnit,
                                   BigDecimal costAmount, String costCurrency, String costInterval,
                                   boolean dataVerified, String sourceDocumentId) {

        static ContractResponse from(Contract contract) {
            return new ContractResponse(contract.getId().toString(), contract.getTitle(),
                    contract.getContractNumber(), contract.getContractType(),
                    contract.getStatus().name(), contract.getStartDate(), contract.getEndDate(),
                    contract.getAutoRenewal(), contract.getRenewalPeriodMonths(),
                    contract.getNoticePeriodValue(), contract.getNoticePeriodUnit(),
                    contract.getCostAmount(), contract.getCostCurrency(), contract.getCostInterval(),
                    contract.isDataVerified(),
                    contract.getSourceDocumentId() == null ? null
                            : contract.getSourceDocumentId().toString());
        }
    }

    public record ContractDetailResponse(ContractResponse contract, List<PartyResponse> parties,
                                         List<ClauseResponse> clauses,
                                         List<String> criticalClausesWithoutInterpretation) {
    }

    @PostMapping
    @PreAuthorize("hasAuthority('contracts.create')")
    @Operation(summary = "Create a contract record; nothing is interpreted at this point")
    public ResponseEntity<ContractResponse> create(@RequestBody CreateContractRequest request) {
        Contract created = contracts.create(new ContractService.CreateRequest(
                RequestContext.tenantId().orElseThrow(), request.title(), request.contractNumber(),
                request.contractType(), uuid(request.caseId()), uuid(request.counterpartyContactId()),
                request.startDate(), request.endDate(), request.signedDate(), request.autoRenewal(),
                request.renewalPeriodMonths(), request.noticePeriodValue(), request.noticePeriodUnit(),
                request.costAmount(), request.costCurrency(), request.costInterval(),
                uuid(request.sourceDocumentId()), request.tags()), CurrentUser.required());
        return ResponseEntity.status(HttpStatus.CREATED).body(ContractResponse.from(created));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('contracts.view')")
    @Operation(summary = "Contracts of the tenant")
    public ResponseEntity<Page<ContractResponse>> list(
            @PageableDefault(size = 25, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(contracts.list(RequestContext.tenantId().orElseThrow(), pageable)
                .map(ContractResponse::from));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('contracts.view')")
    @Operation(summary = "The contract with its parties, clauses and their interpretation states")
    public ResponseEntity<ContractDetailResponse> detail(@PathVariable UUID id) {
        ContractService.Detail detail = contracts.detail(id, RequestContext.tenantId().orElseThrow());
        return ResponseEntity.ok(new ContractDetailResponse(ContractResponse.from(detail.contract()),
                detail.parties().stream().map(PartyResponse::from).toList(),
                detail.clauses().stream().map(ClauseResponse::from).toList(),
                detail.criticalClausesWithoutInterpretation()));
    }

    @GetMapping("/{id}/critical-clauses")
    @PreAuthorize("hasAuthority('contracts.view')")
    @Operation(summary = "Critical clauses and whether anybody has interpreted them yet")
    public ResponseEntity<List<Map<String, Object>>> criticalClauses(@PathVariable UUID id) {
        return ResponseEntity.ok(contracts.criticalClauseStates(id,
                RequestContext.tenantId().orElseThrow()));
    }

    @PostMapping("/{id}/clauses")
    @PreAuthorize("hasAuthority('contracts.edit')")
    @Operation(summary = "Store a clause as read, with the snippet it came from")
    public ResponseEntity<ClauseResponse> addClause(@PathVariable UUID id,
                                                    @RequestBody ClauseRequest request) {
        ContractClause clause = contracts.addClause(id, RequestContext.tenantId().orElseThrow(),
                request.clauseKey(),
                request.clauseType() == null ? null
                        : ContractClause.ClauseType.valueOf(request.clauseType()),
                request.title(), request.clauseText(), request.sourcePage(), request.sourceSnippet(),
                request.confidence(), request.critical());
        return ResponseEntity.status(HttpStatus.CREATED).body(ClauseResponse.from(
                new ContractService.ClauseView(clause, clause.isInterpretationVerified(),
                        ContractService.interpretationState(clause))));
    }

    @PostMapping("/clauses/{clauseId}/interpretation")
    @PreAuthorize("hasAuthority('contracts.edit')")
    @Operation(summary = "Record what a clause means, together with the basis it was read on")
    public ResponseEntity<ClauseResponse> interpret(@PathVariable UUID clauseId,
                                                    @RequestBody InterpretationRequest request) {
        // No default: a missing basis must be refused, not quietly filled with "a person said so".
        // That silence is exactly how a platform ends up asserting legal conclusions of its own.
        ContractClause.InterpretationSource source = request.source() == null || request.source().isBlank()
                ? null
                : parseSource(request.source());
        ContractClause clause = contracts.interpret(clauseId, request.interpretation(), source,
                CurrentUser.required());
        return ResponseEntity.ok(ClauseResponse.from(new ContractService.ClauseView(clause,
                clause.isInterpretationVerified(), ContractService.interpretationState(clause))));
    }

    @PostMapping("/clauses/{clauseId}/verify")
    @PreAuthorize("hasAuthority('contracts.edit')")
    @Operation(summary = "Confirm that an interpretation matches the clause")
    public ResponseEntity<ClauseResponse> verifyInterpretation(@PathVariable UUID clauseId) {
        ContractClause clause = contracts.verifyInterpretation(clauseId, CurrentUser.required());
        return ResponseEntity.ok(ClauseResponse.from(new ContractService.ClauseView(clause,
                clause.isInterpretationVerified(), ContractService.interpretationState(clause))));
    }

    @PostMapping("/{id}/verify-data")
    @PreAuthorize("hasAuthority('contracts.edit')")
    @Operation(summary = "Mark the extracted values as checked against the document")
    public ResponseEntity<ContractResponse> verifyData(@PathVariable UUID id) {
        return ResponseEntity.ok(ContractResponse.from(contracts.verifyData(id,
                RequestContext.tenantId().orElseThrow(), CurrentUser.required())));
    }

    @PostMapping("/{id}/parties")
    @PreAuthorize("hasAuthority('contracts.edit')")
    @Operation(summary = "Add a party, with the name as the contract writes it")
    public ResponseEntity<PartyResponse> addParty(@PathVariable UUID id,
                                                  @RequestBody PartyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(PartyResponse.from(
                contracts.addParty(id, RequestContext.tenantId().orElseThrow(), request.partyRole(),
                        request.partyName(), uuid(request.contactId()), request.primary())));
    }

    @GetMapping("/ending")
    @PreAuthorize("hasAuthority('contracts.view')")
    @Operation(summary = "Contracts whose term ends in the given window")
    public ResponseEntity<List<ContractResponse>> ending(@RequestParam LocalDate from,
                                                         @RequestParam LocalDate to) {
        return ResponseEntity.ok(contracts.endingBetween(RequestContext.tenantId().orElseThrow(), from, to)
                .stream().map(ContractResponse::from).toList());
    }

    private static ContractClause.InterpretationSource parseSource(String value) {
        try {
            return ContractClause.InterpretationSource.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            // A basis the platform does not recognise is not a basis.
            throw new de.buerokratiecopilot.shared.error.ApiException(
                    de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST,
                    "An interpretation basis must be RULE or HUMAN, but was: " + value);
        }
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }
}
