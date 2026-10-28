package de.buerokratiecopilot.contracts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.contracts.ContractDiff.Change;
import de.buerokratiecopilot.contracts.ContractDiff.ClauseSnapshot;
import de.buerokratiecopilot.contracts.domain.Contract;
import de.buerokratiecopilot.contracts.domain.ContractClause;
import de.buerokratiecopilot.contracts.domain.ContractObligation;
import de.buerokratiecopilot.contracts.domain.ContractVersion;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.settings.SettingsService;
import de.buerokratiecopilot.tasks.TaskService;
import de.buerokratiecopilot.tasks.domain.Task;
import de.buerokratiecopilot.tasks.domain.TaskAssignee;

/**
 * Contract versions, obligations and the reminders they produce (spec §13.1, D-002).
 *
 * <p>Two acceptances are implemented here.</p>
 *
 * <p><strong>A change in a new version is visible.</strong> Clauses carry the version they belong to,
 * and comparing two versions names each clause, shows both wordings and says what happened to it. The
 * comparison itself is the pure {@link ContractDiff}; this service only supplies the two clause sets.</p>
 *
 * <p><strong>A reminder is created by a rule.</strong> When an obligation reaches its reminder window —
 * the tenant's own {@code reminderDaysBefore}, not a period the platform picked — a task is created for
 * the responsible person through the task module, so the reminder is work like any other and appears in
 * the assignment queue if nobody is named. The platform does not decide how much warning is right.</p>
 */
@Service
public class ContractVersionService {

    private static final Logger log = LoggerFactory.getLogger(ContractVersionService.class);

    /** Fallback window when an obligation names no responsible person and the tenant set no default. */
    private static final int DEFAULT_REMINDER_DAYS = 14;

    private final ContractRepository contracts;
    private final ContractClauseRepository clauses;
    private final ContractVersionRepository versions;
    private final ContractObligationRepository obligations;
    private final TaskService tasks;
    private final SettingsService settings;

    public ContractVersionService(ContractRepository contracts, ContractClauseRepository clauses,
                                  ContractVersionRepository versions,
                                  ContractObligationRepository obligations, TaskService tasks,
                                  SettingsService settings) {
        this.contracts = contracts;
        this.clauses = clauses;
        this.versions = versions;
        this.obligations = obligations;
        this.tasks = tasks;
        this.settings = settings;
    }

    /** A version with the clauses that belong to it. */
    public record VersionView(ContractVersion version, List<ContractClause> clauses) {
    }

    /** What a comparison found, with the versions it compared. */
    public record DiffView(UUID contractId, UUID beforeVersionId, UUID afterVersionId,
                           List<Change> differences, Map<String, Long> summary) {
    }

    /**
     * Creates a version and makes it the current one.
     *
     * <p>Exactly one version is current, because "the contract as it stands" has to have one answer.</p>
     */
    @Transactional
    public ContractVersion createVersion(UUID contractId, UUID tenantId, String versionLabel,
                                         LocalDate effectiveFrom, String changeNote, UUID documentId,
                                         UUID userId) {
        Contract contract = requireContract(contractId, tenantId);
        if (versionLabel == null || versionLabel.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A version needs a label");
        }
        versions.findByContractIdAndVersionLabel(contractId, versionLabel.strip()).ifPresent(existing -> {
            throw ApiException.duplicate("A version with this label already exists");
        });

        versions.findByContractIdAndCurrentTrue(contractId).ifPresent(previous -> {
            previous.setCurrent(false);
            versions.save(previous);
        });

        ContractVersion version = new ContractVersion(UUID.randomUUID(), tenantId, contract.getId(),
                versionLabel.strip());
        version.setEffectiveFrom(effectiveFrom);
        version.setChangeNote(changeNote);
        version.setDocumentId(documentId);
        version.setCurrent(true);
        version.setCreatedBy(userId);
        log.info("Version {} created for contract {}", version.getVersionLabel(), contract.getId());
        return versions.save(version);
    }

    @Transactional(readOnly = true)
    public List<ContractVersion> versionsOf(UUID contractId, UUID tenantId) {
        requireContract(contractId, tenantId);
        return versions.findByContractIdOrderByCreatedAtAsc(contractId);
    }

    /** The clauses of one version. */
    @Transactional(readOnly = true)
    public VersionView view(UUID versionId, UUID tenantId) {
        ContractVersion version = versions.findById(versionId)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElseThrow(() -> ApiException.notFound("version"));
        return new VersionView(version, clauses.findByVersionIdOrderBySortOrderAsc(versionId));
    }

    /**
     * Compares two versions of a contract.
     *
     * <p>Both versions must belong to the contract the caller named; anything else would silently
     * compare unrelated documents and report differences that never happened.</p>
     */
    @Transactional(readOnly = true)
    public DiffView diff(UUID contractId, UUID tenantId, UUID beforeVersionId, UUID afterVersionId) {
        requireContract(contractId, tenantId);

        ContractVersion before = versions.findById(beforeVersionId)
                .filter(version -> version.getContractId().equals(contractId))
                .orElseThrow(() -> ApiException.notFound("the earlier version"));
        ContractVersion after = versions.findById(afterVersionId)
                .filter(version -> version.getContractId().equals(contractId))
                .orElseThrow(() -> ApiException.notFound("the later version"));

        ContractDiff.Result result = ContractDiff.compare(snapshots(beforeVersionId),
                snapshots(afterVersionId));
        return new DiffView(contractId, before.getId(), after.getId(), result.differences(),
                result.summary());
    }

    /**
     * Compares a version with the current one.
     *
     * <p>The common case for a user: "what changed since the version I knew".</p>
     */
    @Transactional(readOnly = true)
    public DiffView diffAgainstCurrent(UUID contractId, UUID tenantId, UUID versionId) {
        ContractVersion current = versions.findByContractIdAndCurrentTrue(contractId)
                .orElseThrow(() -> ApiException.conflict("The contract has no current version"));
        return diff(contractId, tenantId, versionId, current.getId());
    }

    private List<ClauseSnapshot> snapshots(UUID versionId) {
        List<ClauseSnapshot> snapshots = new ArrayList<>();
        for (ContractClause clause : clauses.findByVersionIdOrderBySortOrderAsc(versionId)) {
            snapshots.add(new ClauseSnapshot(clause.getClauseKey(), clause.getClauseText(),
                    clause.isCritical()));
        }
        return snapshots;
    }

    /** Stores a clause as a version of the contract; the provenance rules of D-001 still apply. */
    @Transactional
    public ContractClause addClauseToVersion(UUID versionId, UUID tenantId, String clauseKey,
                                             ContractClause.ClauseType clauseType, String clauseText,
                                             Integer sourcePage, String sourceSnippet, boolean critical) {
        ContractVersion version = versions.findById(versionId)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElseThrow(() -> ApiException.notFound("version"));
        ContractClause clause = new ContractClause(UUID.randomUUID(), tenantId, version.getContractId(),
                clauseKey == null || clauseKey.isBlank() ? "CLAUSE" : clauseKey.strip(),
                clauseType == null ? ContractClause.ClauseType.OTHER : clauseType, clauseText.strip());
        clause.setSourcePage(sourcePage);
        clause.setSourceSnippet(sourceSnippet == null ? null : sourceSnippet.strip());
        clause.setCritical(critical);
        clause.setVersionId(versionId);
        clause.setSortOrder(clauses.findByVersionIdOrderBySortOrderAsc(versionId).size() + 1);
        return clauses.save(clause);
    }

    // ------------------------------------------------------------------ obligations

    @Transactional
    public ContractObligation addObligation(UUID contractId, UUID tenantId,
                                            ContractObligation.ObligationType type, String title,
                                            LocalDate dueDate, ContractObligation.Recurrence recurrence,
                                            java.math.BigDecimal amount, String currency,
                                            UUID responsibleUserId, Integer reminderDaysBefore,
                                            UUID clauseId) {
        requireContract(contractId, tenantId);
        if (title == null || title.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "An obligation needs a title");
        }
        ContractObligation obligation = new ContractObligation(UUID.randomUUID(), tenantId, contractId,
                type == null ? ContractObligation.ObligationType.OTHER : type, title.strip());
        obligation.setDueDate(dueDate);
        obligation.setRecurrence(recurrence == null ? ContractObligation.Recurrence.ONE_TIME : recurrence);
        obligation.setAmount(amount);
        if (currency != null && !currency.isBlank()) {
            obligation.setCurrency(currency.toUpperCase(java.util.Locale.ROOT));
        }
        obligation.setResponsibleUserId(responsibleUserId);
        obligation.setReminderDaysBefore(reminderDaysBefore);
        obligation.setContractClauseId(clauseId);
        return obligations.save(obligation);
    }

    @Transactional(readOnly = true)
    public List<ContractObligation> obligationsOf(UUID contractId, UUID tenantId) {
        requireContract(contractId, tenantId);
        return obligations.findByContractIdOrderByDueDateAsc(contractId);
    }

    /** The obligation calendar: everything due in a window. */
    @Transactional(readOnly = true)
    public List<ContractObligation> calendar(UUID tenantId, LocalDate from, LocalDate to) {
        return obligations.findByTenantIdAndDueDateBetweenOrderByDueDateAsc(tenantId, from, to);
    }

    @Transactional
    public ContractObligation fulfil(UUID obligationId, UUID tenantId, UUID userId) {
        ContractObligation obligation = obligations.findById(obligationId)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElseThrow(() -> ApiException.notFound("obligation"));
        obligation.fulfil(userId);
        return obligations.save(obligation);
    }

    /**
     * What the reminder run did.
     *
     * @param created tasks that were created
     * @param skipped obligations that were not due for a reminder, with the reason
     */
    public record ReminderRun(int created, List<String> skipped, List<String> createdTasks) {

        public Map<String, Object> asDiagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("created", created);
            details.put("skipped", skipped.size());
            details.put("tasks", createdTasks);
            return details;
        }
    }

    /**
     * Creates a reminder task for every obligation whose reminder window has been reached.
     *
     * <p>The window is the tenant's rule, per obligation, falling back to a configured default. An
     * obligation without a due date, without a responsible person and without a rule produces no task:
     * the platform does not invent a date or a person to warn.</p>
     *
     * @param today the day the run is made for, passed in so the behaviour is testable and reproducible
     */
    @Transactional
    public ReminderRun runReminders(UUID tenantId, LocalDate today, UUID actorId) {
        List<String> skipped = new ArrayList<>();
        List<String> created = new ArrayList<>();

        for (ContractObligation obligation : obligations.findRemindable(tenantId)) {
            if (obligation.getDueDate() == null) {
                skipped.add(obligation.getId() + ": no due date");
                continue;
            }
            if (obligation.getDueDate().isBefore(today)) {
                // The date has passed; the obligation is missed, which is a different fact and a
                // different conversation from a reminder.
                obligation.markMissed();
                obligations.save(obligation);
                skipped.add(obligation.getId() + ": due date has passed, marked missed");
                continue;
            }
            if (!obligation.isReminderDue(today)) {
                skipped.add(obligation.getId() + ": reminder window not reached");
                continue;
            }

            Task task = tasks.create(new TaskService.CreateRequest(tenantId, null, null,
                    "Vertragspflicht: " + obligation.getTitle(),
                    "Aus dem Vertrag fällig am " + obligation.getDueDate(),
                    Task.Priority.HIGH, "RULE",
                    "Fälligkeit aus dem Vertragskalender", obligation.getDueDate().atStartOfDay(
                            java.time.ZoneId.of("Europe/Berlin")).toInstant(),
                    null, "Die Pflicht ist erfüllt und im Vertrag vermerkt", false, null,
                    obligation.getResponsibleUserId(), List.of("contract-obligation"),
                    Task.Source.SYSTEM, null), actorId);

            if (obligation.getResponsibleUserId() == null) {
                // Nobody was named, so the task goes to the manual distribution queue rather than
                // being handed to whoever happens to be around.
                skipped.add(obligation.getId() + ": no responsible person, task awaits distribution");
            }
            created.add(task.getId().toString());
        }

        log.info("Contract reminder run for {}: {} task(s) created", tenantId, created.size());
        return new ReminderRun(created.size(), skipped, created);
    }

    /** The reminder window an obligation gets when the tenant configured a default. */
    @Transactional(readOnly = true)
    public int defaultReminderDays(UUID tenantId) {
        return settings.getInt("contracts.reminder_days_default", DEFAULT_REMINDER_DAYS);
    }

    /** Obligations that will produce a reminder within the given number of days. */
    @Transactional(readOnly = true)
    public List<ContractObligation> upcomingReminders(UUID tenantId, LocalDate today, int days) {
        return obligations.findByTenantIdAndDueDateBetweenOrderByDueDateAsc(tenantId, today,
                today.plusDays(days)).stream()
                .filter(ContractObligation::isOpen)
                .toList();
    }

    private Contract requireContract(UUID contractId, UUID tenantId) {
        return contracts.findByIdAndTenantIdAndDeletedAtIsNull(contractId, tenantId)
                .orElseThrow(() -> ApiException.notFound("contract"));
    }
}
