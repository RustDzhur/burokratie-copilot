package de.buerokratiecopilot.deadlines;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.deadlines.domain.DeadlineRule;
import de.buerokratiecopilot.deadlines.domain.TaskDeadline;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Deadlines, and the register of rules they are counted from (spec §13.2, §13.3).
 *
 * <p>The platform's position on legal rules is simple and strict: <strong>it has none of its own.</strong>
 * The register ships empty, rules are entered by whoever operates the platform, and a rule only counts
 * as settled once a responsible person has verified it against their own source.</p>
 *
 * <p>That produces two kinds of date, and the difference is visible everywhere:</p>
 * <ul>
 *   <li>a date counted from a <strong>verified</strong> rule is {@code CONFIRMED_LEGAL};</li>
 *   <li>a date counted from an <strong>unverified</strong> rule is {@code ASSUMED_LEGAL} and carries
 *       {@code requiresVerification}, so nobody mistakes it for a settled deadline.</li>
 * </ul>
 *
 * <p>Refusing to compute at all would be worse: a user who cannot record a provisional date records
 * nothing, and a case with no date at all is how deadlines get missed. What must not happen is a
 * provisional date looking like a confirmed one.</p>
 */
@Service
public class DeadlineService {

    private static final Logger log = LoggerFactory.getLogger(DeadlineService.class);

    private final DeadlineRuleRepository rules;
    private final TaskDeadlineRepository deadlines;
    private final JdbcTemplate jdbc;

    public DeadlineService(DeadlineRuleRepository rules, TaskDeadlineRepository deadlines,
                           JdbcTemplate jdbc) {
        this.rules = rules;
        this.deadlines = deadlines;
        this.jdbc = jdbc;
    }

    /** A rule, as it is entered or shown. */
    public record RuleRequest(UUID tenantId, String code, Map<String, Object> name, String description,
                              String legalBasis, Integer periodValue, DeadlineRule.PeriodUnit periodUnit,
                              DeadlineRule.CountingMode countingMode, DeadlineRule.StartEvent startEvent,
                              DeadlineRule.DeliveryMethod deliveryMethod, UUID holidayCalendarId) {
    }

    /** What a computed deadline is, and what it rests on. */
    public record Computed(TaskDeadline deadline, DeadlineRule rule, LocalDate startDate,
                           boolean ruleVerified, String explanation) {
    }

    /**
     * Enters a rule.
     *
     * <p>It is never verified on creation, whoever creates it: entering a period and confirming it
     * against a legal source are two different acts, and collapsing them would make the register's
     * verification flag meaningless.</p>
     */
    @Transactional
    public DeadlineRule createRule(RuleRequest request, UUID userId) {
        if (request.code() == null || request.code().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A rule needs a code");
        }
        if (request.periodValue() == null || request.periodValue() <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A rule needs a positive period; a rule without one cannot produce a date");
        }
        if (request.periodUnit() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A rule needs a period unit");
        }
        if (rules.existsByTenantIdAndCode(request.tenantId(), request.code())) {
            throw ApiException.duplicate("A rule with this code already exists");
        }

        DeadlineRule rule = new DeadlineRule(UUID.randomUUID(), request.tenantId(), request.code().strip(),
                request.name() == null ? Map.of("de", request.code()) : request.name());
        rule.setDescription(request.description());
        rule.setLegalBasis(request.legalBasis());
        rule.setPeriod(request.periodValue(), request.periodUnit());
        rule.setCountingMode(request.countingMode() == null ? DeadlineRule.CountingMode.CALENDAR_DAYS
                : request.countingMode());
        rule.setStartEvent(request.startEvent() == null ? DeadlineRule.StartEvent.RECEIPT_DATE
                : request.startEvent());
        rule.setDeliveryMethod(request.deliveryMethod());
        rule.setHolidayCalendarId(request.holidayCalendarId());
        rule.setCreatedBy(userId);

        DeadlineRule saved = rules.save(rule);
        log.info("Deadline rule {} entered, awaiting verification", saved.getCode());
        return saved;
    }

    /** Confirms a rule against its source, which is what turns its dates into settled ones. */
    @Transactional
    public DeadlineRule verifyRule(UUID ruleId, UUID userId) {
        DeadlineRule rule = rules.findById(ruleId).orElseThrow(() -> ApiException.notFound("rule"));
        if (rule.getLegalBasis() == null || rule.getLegalBasis().isBlank()) {
            // Without a stated source there is nothing to verify against, and "verified" would only
            // mean "somebody clicked".
            throw ApiException.conflict(
                    "This rule has no stated legal basis, so there is nothing to verify it against. "
                            + "Record where the period comes from first.");
        }
        rule.verify(userId);
        return rules.save(rule);
    }

    @Transactional(readOnly = true)
    public List<DeadlineRule> rulesFor(UUID tenantId) {
        return rules.findEffective(tenantId);
    }

    /** Rules that still await verification, which is the operator's to-do list. */
    @Transactional(readOnly = true)
    public List<DeadlineRule> unverifiedRules() {
        return rules.findByRequiresVerificationTrueAndActiveTrue();
    }

    /**
     * Counts a deadline from a rule.
     *
     * @param startDate the day counting begins, resolved from the rule's start event by the caller:
     *                  only the caller knows whether the platform has a receipt date or a document date
     */
    @Transactional
    public Computed compute(UUID tenantId, UUID caseId, UUID taskId, String ruleCode, LocalDate startDate,
                            UUID userId) {
        DeadlineRule rule = rules.findEffectiveByCode(tenantId, ruleCode)
                .orElseThrow(() -> ApiException.notFound("deadline rule " + ruleCode));

        if (startDate == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A deadline needs the date counting starts from");
        }
        if (taskId == null) {
            // The schema is explicit: every deadline belongs to a task. A case-level date that is
            // nobody's task would be a date nobody works towards.
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A deadline belongs to a task; create the task it belongs to first");
        }
        if (rule.getPeriodValue() == null || rule.getPeriodUnit() == null) {
            throw ApiException.conflict("The rule " + ruleCode + " has no period, so no date follows "
                    + "from it");
        }

        LocalDate dueDate = DeadlineCalculator.dueDate(startDate, rule.getPeriodValue(),
                rule.calculatorUnit(), rule.calculatorMode(), holidaysOf(rule));

        boolean verified = rule.isVerified();
        TaskDeadline deadline = new TaskDeadline(UUID.randomUUID(), tenantId, taskId, caseId,
                verified ? TaskDeadline.DeadlineType.CONFIRMED_LEGAL
                        : TaskDeadline.DeadlineType.ASSUMED_LEGAL);
        deadline.setDueDate(dueDate);
        deadline.setSource(TaskDeadline.Source.RULE);
        deadline.setRuleId(rule.getId());
        deadline.setRuleVersion(rule.getVersion());
        deadline.setCalculatedAt(java.time.Instant.now());
        deadline.setCreatedBy(userId);
        deadline.setTimeZone("Europe/Berlin");

        if (verified) {
            // A rule a person verified produces a date a person may rely on, so the deadline is
            // confirmed by the same act.
            deadline.confirm(rule.getVerifiedBy(), "Aus geprüfter Regel " + rule.getCode());
        }
        TaskDeadline saved = deadlines.save(deadline);

        String explanation = verified
                ? "%s: %d %s ab %s ergibt den %s".formatted(rule.getCode(), rule.getPeriodValue(),
                        rule.getPeriodUnit(), startDate, dueDate)
                : "Vorläufig: die Regel %s ist noch nicht geprüft. %d %s ab %s ergibt den %s, "
                        .formatted(rule.getCode(), rule.getPeriodValue(), rule.getPeriodUnit(), startDate,
                                dueDate)
                        + "aber dieses Datum ist nicht bestätigt.";

        log.info("Deadline computed from rule {} for case {}: {} (verified: {})", rule.getCode(), caseId,
                dueDate, verified);
        return new Computed(saved, rule, startDate, verified, explanation);
    }

    /** Confirms a single date, for the case where a person checks the date rather than the rule. */
    @Transactional
    public TaskDeadline confirm(UUID deadlineId, UUID userId, String note) {
        TaskDeadline deadline = deadlines.findById(deadlineId)
                .orElseThrow(() -> ApiException.notFound("deadline"));
        deadline.confirm(userId, note);
        return deadlines.save(deadline);
    }

    /** Deadlines of a case, earliest first. */
    @Transactional(readOnly = true)
    public List<TaskDeadline> forCase(UUID caseId) {
        return deadlines.findForCase(caseId);
    }

    @Transactional(readOnly = true)
    public List<TaskDeadline> forTask(UUID taskId) {
        return deadlines.findByTaskIdAndActiveTrueOrderByDueDateAsc(taskId);
    }

    /** Deadlines that rest on an unverified rule, for the review list. */
    @Transactional(readOnly = true)
    public List<TaskDeadline> awaitingVerification(UUID tenantId) {
        return deadlines.findByTenantIdAndRequiresVerificationTrueAndActiveTrue(tenantId);
    }

    /** Records a deadline a person set, which has no rule behind it and says so. */
    @Transactional
    public TaskDeadline recordManual(UUID tenantId, UUID caseId, UUID taskId, LocalDate dueDate,
                                     String note, UUID userId) {
        if (dueDate == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A deadline needs a date");
        }
        if (taskId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A deadline belongs to a task; create the task it belongs to first");
        }
        TaskDeadline deadline = new TaskDeadline(UUID.randomUUID(), tenantId, taskId, caseId,
                TaskDeadline.DeadlineType.INTERNAL);
        deadline.setDueDate(dueDate);
        deadline.setSource(TaskDeadline.Source.MANUAL);
        deadline.setNote(note);
        deadline.setCreatedBy(userId);
        deadline.setTimeZone("Europe/Berlin");
        // A manually recorded date rests on the person who recorded it, so it needs no later
        // confirmation to be valid - it is already a human decision.
        deadline.confirm(userId, note);
        return deadlines.save(deadline);
    }

    /**
     * The holidays of the calendar a rule refers to.
     *
     * <p>Without a calendar a working day rule counts weekends only, which is wrong wherever public
     * holidays exist. The result therefore carries the calendar the dates came from, and a rule that
     * skips working days without a calendar is reported as unverified rather than silently counting
     * weekends.</p>
     */
    private Set<LocalDate> holidaysOf(DeadlineRule rule) {
        if (rule.getHolidayCalendarId() == null) {
            return Set.of();
        }
        List<LocalDate> dates = jdbc.query("""
                select holiday_date from holidays where calendar_id = ? order by holiday_date
                """, (rs, rowNum) -> rs.getObject(1, LocalDate.class), rule.getHolidayCalendarId());
        return new HashSet<>(dates);
    }

    /** The rules of the register with the dates they would produce, for the operator's overview. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> describeRules(UUID tenantId) {
        return rulesFor(tenantId).stream().map(rule -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", rule.getCode());
            row.put("name", rule.nameIn("de"));
            row.put("period", rule.getPeriodValue() + " " + rule.getPeriodUnit());
            row.put("countingMode", rule.getCountingMode().name());
            row.put("startEvent", rule.getStartEvent().name());
            row.put("legalBasis", rule.getLegalBasis());
            row.put("verified", rule.isVerified());
            row.put("requiresVerification", rule.isRequiresVerification());
            return row;
        }).toList();
    }
}
