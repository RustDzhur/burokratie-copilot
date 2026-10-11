package de.buerokratiecopilot.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.contracts.domain.Contract;
import de.buerokratiecopilot.contracts.domain.ContractClause;
import de.buerokratiecopilot.contracts.domain.ContractParty;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The contract register (spec §13.1).
 *
 * <p>The rule this service exists to enforce is the boundary between <strong>detected text</strong> and
 * <strong>legal interpretation</strong>.</p>
 *
 * <p>What the platform may do on its own: read a clause out of a document and store its words together
 * with the page and snippet they came from. That is a statement about the paper, and it can be checked
 * by anybody holding the same paper.</p>
 *
 * <p>What it may not do: conclude what the clause means. A notice period, a renewal and a liability
 * clause have legal effects, and a platform that paraphrases them produces advice it is not qualified
 * to give. An interpretation is therefore stored only together with its source — a rule, a provision or
 * the person who wrote it — and it is marked unverified until a person confirms it.</p>
 */
@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    private final ContractRepository contracts;
    private final ContractClauseRepository clauses;
    private final ContractPartyRepository parties;

    public ContractService(ContractRepository contracts, ContractClauseRepository clauses,
                           ContractPartyRepository parties) {
        this.contracts = contracts;
        this.clauses = clauses;
        this.parties = parties;
    }

    /** What is needed to create a contract record. */
    public record CreateRequest(UUID tenantId, String title, String contractNumber, String contractType,
                                UUID caseId, UUID counterpartyContactId, LocalDate startDate,
                                LocalDate endDate, LocalDate signedDate, Boolean autoRenewal,
                                Integer renewalPeriodMonths, Integer noticePeriodValue,
                                String noticePeriodUnit, BigDecimal costAmount, String costCurrency,
                                String costInterval, UUID sourceDocumentId, List<String> tags) {
    }

    /** A clause as it is shown: the text, where it came from, and what was concluded from it. */
    public record ClauseView(ContractClause clause, boolean verified, String interpretationState) {
    }

    /** The contract card. */
    public record Detail(Contract contract, List<ContractParty> parties, List<ClauseView> clauses,
                         List<String> criticalClausesWithoutInterpretation, boolean dataVerified) {
    }

    @Transactional
    public Contract create(CreateRequest request, UUID userId) {
        if (request.title() == null || request.title().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A contract needs a title");
        }
        if (request.contractNumber() != null && !request.contractNumber().isBlank()) {
            contracts.findByTenantIdAndContractNumber(request.tenantId(), request.contractNumber())
                    .ifPresent(existing -> {
                        throw ApiException.duplicate("A contract with this number already exists");
                    });
        }

        Contract contract = new Contract(UUID.randomUUID(), request.tenantId(), request.title().strip());
        contract.setContractNumber(request.contractNumber());
        contract.setContractType(request.contractType());
        contract.setCaseId(request.caseId());
        contract.setCounterpartyContactId(request.counterpartyContactId());
        contract.setStartDate(request.startDate());
        contract.setEndDate(request.endDate());
        contract.setSignedDate(request.signedDate());
        contract.setAutoRenewal(request.autoRenewal());
        contract.setRenewalPeriodMonths(request.renewalPeriodMonths());
        contract.setNoticePeriodValue(request.noticePeriodValue());
        contract.setNoticePeriodUnit(request.noticePeriodUnit());
        contract.setCostAmount(request.costAmount());
        contract.setCostCurrency(request.costCurrency());
        contract.setCostInterval(request.costInterval());
        contract.setSourceDocumentId(request.sourceDocumentId());
        contract.setTags(request.tags());
        contract.setCreatedBy(userId);
        return contracts.save(contract);
    }

    /**
     * Stores a clause as read from the document.
     *
     * <p>The text is kept verbatim and the snippet is required: a clause without the place it came from
     * cannot be checked, and a clause that cannot be checked is one a user has to trust. The acceptance
     * criterion of this task is exactly that a term is shown with its snippet.</p>
     */
    @Transactional
    public ContractClause addClause(UUID contractId, UUID tenantId, String clauseKey,
                                    ContractClause.ClauseType clauseType, String title, String clauseText,
                                    Integer sourcePage, String sourceSnippet, BigDecimal confidence,
                                    boolean critical) {
        if (clauseText == null || clauseText.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A clause needs its text");
        }
        if (sourceSnippet == null || sourceSnippet.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A clause needs the snippet it was read from; without it nobody can check it "
                            + "against the document");
        }

        ContractClause clause = new ContractClause(UUID.randomUUID(), tenantId, contractId,
                clauseKey == null || clauseKey.isBlank() ? "CLAUSE" : clauseKey.strip(),
                clauseType == null ? ContractClause.ClauseType.OTHER : clauseType, clauseText.strip());
        clause.setTitle(title);
        clause.setSourcePage(sourcePage);
        clause.setSourceSnippet(sourceSnippet.strip());
        clause.setConfidence(confidence);
        clause.setCritical(critical);
        clause.setSortOrder(clauses.findByContractIdOrderBySortOrderAsc(contractId).size() + 1);

        ContractClause saved = clauses.save(clause);
        log.info("Clause {} of contract {} stored from page {}", saved.getClauseKey(), contractId,
                sourcePage);
        return saved;
    }

    /**
     * Records what a clause means, together with the basis.
     *
     * @throws ApiException when no source is given. The entity refuses it as well, and the check here
     *                      turns that refusal into a client error with a readable message.
     */
    @Transactional
    public ContractClause interpret(UUID clauseId, String interpretation,
                                    ContractClause.InterpretationSource source, UUID authorUserId) {
        ContractClause clause = clauses.findById(clauseId)
                .orElseThrow(() -> ApiException.notFound("clause"));
        try {
            clause.interpret(interpretation, source, authorUserId);
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, ex.getMessage());
        }
        return clauses.save(clause);
    }

    /** A person confirms that an interpretation matches the clause it was drawn from. */
    @Transactional
    public ContractClause verifyInterpretation(UUID clauseId, UUID userId) {
        ContractClause clause = clauses.findById(clauseId)
                .orElseThrow(() -> ApiException.notFound("clause"));
        try {
            clause.verifyInterpretation(userId);
        }
        catch (IllegalStateException ex) {
            // Recorded as a conflict: there is nothing to verify, which is a state, not bad input.
            throw ApiException.conflict(ex.getMessage());
        }
        return clauses.save(clause);
    }

    /** Marks the extracted values as checked against the document by a person. */
    @Transactional
    public Contract verifyData(UUID contractId, UUID tenantId, UUID userId) {
        Contract contract = requireContract(contractId, tenantId);
        contract.markDataVerified(userId);
        return contracts.save(contract);
    }

    @Transactional
    public ContractParty addParty(UUID contractId, UUID tenantId, String partyRole, String partyName,
                                  UUID contactId, boolean primary) {
        if (partyName == null || partyName.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A party needs the name as the contract writes it");
        }
        ContractParty party = new ContractParty(UUID.randomUUID(), tenantId, contractId,
                partyRole == null || partyRole.isBlank() ? "OTHER" : partyRole, partyName.strip());
        party.setContactId(contactId);
        party.setPrimary(primary);
        return parties.save(party);
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID contractId, UUID tenantId) {
        Contract contract = requireContract(contractId, tenantId);
        List<ContractClause> stored = clauses.findByContractIdOrderBySortOrderAsc(contractId);

        List<ClauseView> views = new ArrayList<>();
        List<String> criticalWithoutInterpretation = new ArrayList<>();
        for (ContractClause clause : stored) {
            views.add(new ClauseView(clause, clause.isInterpretationVerified(),
                    interpretationState(clause)));
            if (clause.isCritical() && !clause.hasInterpretation()) {
                // Named explicitly rather than left to be noticed: a critical clause nobody has
                // interpreted is exactly the one a person should look at.
                criticalWithoutInterpretation.add(clause.getClauseKey());
            }
        }

        return new Detail(contract, parties.findByContractIdOrderByCreatedAtAsc(contractId), views,
                criticalWithoutInterpretation, contract.isDataVerified());
    }

    /**
     * How an interpretation is labelled, so detected text and legal reading never look alike.
     *
     * <p>Public because the API returns it: the distinction is part of the contract with the user, not
     * an internal detail.</p>
     */
    public static String interpretationState(ContractClause clause) {
        if (!clause.hasInterpretation()) {
            return "NOT_INTERPRETED";
        }
        if (!clause.isInterpretationVerified()) {
            return "UNVERIFIED_INTERPRETATION";
        }
        return "VERIFIED_INTERPRETATION";
    }

    @Transactional(readOnly = true)
    public Page<Contract> list(UUID tenantId, Pageable pageable) {
        return contracts.findByTenantIdAndDeletedAtIsNull(tenantId, pageable);
    }

    /** Contracts whose term ends in a window, which is what a renewal overview needs. */
    @Transactional(readOnly = true)
    public List<Contract> endingBetween(UUID tenantId, LocalDate from, LocalDate to) {
        return contracts.findByTenantIdAndEndDateBetweenAndDeletedAtIsNull(tenantId, from, to);
    }

    /**
     * The critical clauses of a contract with their state.
     *
     * <p>Deliberately a list of facts and not a recommendation: the platform says which clauses a
     * person has not looked at yet, not what to do about them.</p>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> criticalClauseStates(UUID contractId, UUID tenantId) {
        requireContract(contractId, tenantId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ContractClause clause : clauses.findByContractIdOrderBySortOrderAsc(contractId)) {
            if (!clause.isCritical()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("clauseKey", clause.getClauseKey());
            row.put("state", interpretationState(clause));
            row.put("sourcePage", clause.getSourcePage());
            row.put("hasInterpretation", clause.hasInterpretation());
            row.put("interpretationSource", clause.getInterpretationSource());
            rows.add(row);
        }
        return rows;
    }

    private Contract requireContract(UUID contractId, UUID tenantId) {
        return contracts.findByIdAndTenantIdAndDeletedAtIsNull(contractId, tenantId)
                .orElseThrow(() -> ApiException.notFound("contract"));
    }
}
