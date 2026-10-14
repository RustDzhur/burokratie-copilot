package de.buerokratiecopilot.approvals;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.approvals.domain.Approval;
import de.buerokratiecopilot.approvals.domain.Payment;
import de.buerokratiecopilot.approvals.domain.PaymentApproval;
import de.buerokratiecopilot.approvals.domain.PaymentApprovalRule;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * Payment approvals and the payments they release (spec §15, D-004).
 *
 * <p>Two acceptances are implemented here, and both are about refusing to move too fast.</p>
 *
 * <p><strong>A payment is not executed without a confirmation by an authorised person.</strong>
 * Preparing a payment and confirming it are different acts by design: the platform may work out what
 * should be paid, and a person decides whether it is. The base configuration never executes anything
 * automatically, and the decider has to hold the role or permission the rule names — an approval button
 * anybody can press is not a control.</p>
 *
 * <p><strong>A repeated confirmation does not duplicate the operation.</strong> A payment carries a key
 * derived from what is being paid, so the second confirmation finds the first payment instead of making
 * another one. Paying a supplier twice because a browser retried is the failure this prevents.</p>
 *
 * <p>No financial rule is invented: the thresholds, roles and levels come from the tenant's
 * {@code payment_approval_rules}.</p>
 */
@Service
public class PaymentApprovalService {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalService.class);

    private final ApprovalRepository approvals;
    private final PaymentRepository payments;
    private final PaymentApprovalRepository paymentApprovals;
    private final PaymentApprovalRuleRepository rules;
    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final de.buerokratiecopilot.workflow.approval.ApprovalService approvalService;
    private final de.buerokratiecopilot.workflow.approval.ApprovalBinding binding;

    public PaymentApprovalService(ApprovalRepository approvals, PaymentRepository payments,
                                  PaymentApprovalRepository paymentApprovals,
                                  PaymentApprovalRuleRepository rules, JdbcTemplate jdbc,
                                  SettingsService settings,
                                  de.buerokratiecopilot.workflow.approval.ApprovalService approvalService,
                                  de.buerokratiecopilot.workflow.approval.ApprovalBinding binding) {
        this.approvalService = approvalService;
        this.binding = binding;
        this.approvals = approvals;
        this.payments = payments;
        this.paymentApprovals = paymentApprovals;
        this.rules = rules;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** A payment prepared for approval. */
    public record PrepareRequest(UUID tenantId, UUID invoiceId, UUID caseId, BigDecimal amount,
                                 String currency, String counterpartyName, String counterpartyIban,
                                 String reference, LocalDate paymentDate) {
    }

    /** What preparing a payment produced. */
    public record Prepared(Payment payment, Approval approval, List<PaymentApproval> levels,
                           PaymentApprovalRule rule, boolean created) {

        public Map<String, Object> asDiagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("paymentId", payment.getId().toString());
            details.put("status", payment.getStatus().name());
            details.put("approvalId", approval == null ? null : approval.getId().toString());
            details.put("levels", levels.size());
            details.put("rule", rule == null ? null : rule.getCode());
            details.put("created", created);
            return details;
        }
    }

    /**
     * Prepares a payment and the approval it needs.
     *
     * <p>Idempotent by construction: preparing the same invoice and amount twice returns the payment
     * that already exists instead of a second one.</p>
     */
    @Transactional
    public Prepared prepare(PrepareRequest request, UUID userId) {
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Zahlungsbetrag muss positiv sein");
        }
        String currency = request.currency() == null || request.currency().isBlank() ? "EUR"
                : request.currency().toUpperCase(java.util.Locale.ROOT);
        String key = idempotencyKey(request.invoiceId(), request.amount(), currency);

        Optional<Payment> existing = payments.findByTenantIdAndIdempotencyKey(request.tenantId(), key);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            Approval approval = approvalOf(request.tenantId(), payment.getId());
            log.info("Payment {} already prepared for key {}", payment.getId(), key);
            return new Prepared(payment, approval,
                    approval == null ? List.of()
                            : paymentApprovals.findByApprovalIdOrderByApprovalLevelAsc(approval.getId()),
                    ruleFor(request.tenantId(), request.amount(), currency), false);
        }

        Payment payment = new Payment(UUID.randomUUID(), request.tenantId(), request.amount(), currency);
        payment.setInvoiceId(request.invoiceId());
        payment.setCaseId(request.caseId());
        payment.setCounterpartyName(request.counterpartyName());
        payment.setCounterpartyIban(request.counterpartyIban() == null ? null
                : request.counterpartyIban().replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT));
        payment.setReference(request.reference());
        payment.setPaymentDate(request.paymentDate());
        payment.setIdempotencyKey(key);
        payment.setCreatedBy(userId);
        payment.awaitApproval();
        Payment savedPayment = payments.save(payment);

        PaymentApprovalRule rule = ruleFor(request.tenantId(), request.amount(), currency);
        Approval approval = new Approval(UUID.randomUUID(), request.tenantId(), "PAYMENT",
                savedPayment.getId(), Approval.ApprovalType.PAYMENT_APPROVAL,
                "Zahlung über " + request.amount() + " " + currency);
        approval.setRequestedBy(userId);
        approval.setAutomationLevel(1);
        if (rule != null) {
            approval.setRequiredRole(rule.getRequiredRole());
            approval.setRequiredPermission(rule.getRequiredPermission());
            approval.addMetadata("rule", rule.getCode());
            approval.addMetadata("requireSecondPerson", rule.isRequireSecondPerson());
        }
        Approval savedApproval = approvals.save(approval);

        List<PaymentApproval> levels = new ArrayList<>();
        int levelCount = rule == null ? 1 : Math.max(1, rule.getApprovalLevels());
        for (int level = 1; level <= levelCount; level++) {
            PaymentApproval paymentApproval = new PaymentApproval(UUID.randomUUID(), request.tenantId(),
                    savedPayment.getId(), rule == null ? null : rule.getId(), level);
            paymentApproval.setApprovalId(savedApproval.getId());
            paymentApproval.setInvoiceId(request.invoiceId());
            levels.add(paymentApprovals.save(paymentApproval));
        }

        // The approval is bound to the payment as it is now: amount, receiving account, reference.
        payments.flush();
        approvals.flush();
        binding.of(request.tenantId(), "PAYMENT", savedPayment.getId()).ifPresent(fingerprint -> jdbc.update(
                "update approvals set content_hash = ?, subject_version = ? where id = ? and tenant_id = ?",
                fingerprint.hash(), fingerprint.version(), savedApproval.getId(), request.tenantId()));

        log.info("Payment {} prepared with {} approval level(s), rule {}", savedPayment.getId(),
                levels.size(), rule == null ? "none" : rule.getCode());
        return new Prepared(savedPayment, savedApproval, levels, rule, true);
    }

    /**
     * Records a decision on one approval level.
     *
     * <p>The decider must hold what the rule requires, and — when the rule demands two people — must
     * not be the same person who decided an earlier level. Both checks read the stored rule rather than
     * a convention, so a tenant can tighten its rule without a release.</p>
     */
    @Transactional
    public PaymentApproval decide(UUID paymentApprovalId, UUID tenantId, UUID deciderId,
                                  PaymentApproval.Status decision, String comment) {
        PaymentApproval level = paymentApprovals.findById(paymentApprovalId)
                .filter(candidate -> candidate.getTenantId().equals(tenantId))
                .orElseThrow(() -> ApiException.notFound("approval level"));
        if (!level.isPending()) {
            throw ApiException.conflict("Diese Freigabestufe ist bereits entschieden");
        }

        Payment payment = payments.findById(level.getPaymentId())
                .orElseThrow(() -> ApiException.notFound("payment"));
        PaymentApprovalRule rule = level.getRuleId() == null ? null
                : rules.findById(level.getRuleId()).orElse(null);

        requireAuthorised(tenantId, deciderId, rule);

        if (rule != null && rule.isRequireSecondPerson()) {
            boolean alreadyDecidedByThisPerson = paymentApprovals
                    .findByPaymentIdOrderByApprovalLevelAsc(payment.getId()).stream()
                    .anyMatch(other -> other.getApprovalLevel() != level.getApprovalLevel()
                            && deciderId.equals(other.getApproverUserId()));
            if (alreadyDecidedByThisPerson) {
                throw ApiException.conflict(
                        "Diese Zahlung verlangt zwei verschiedene Personen; Sie haben bereits eine "
                                + "Stufe entschieden");
            }
        }

        level.decide(decision, deciderId, comment);
        PaymentApproval saved = paymentApprovals.save(level);

        if (decision == PaymentApproval.Status.REJECTED) {
            // The approval record follows the levels: one rejection rejects it.
            jdbc.update("""
                    update approvals set status = 'REJECTED', decided_by = ?, decided_at = now(), decision_note = ?, updated_at = now()
                    where id = ? and tenant_id = ? and status = 'PENDING'
                    """, deciderId, comment, level.getApprovalId(), tenantId);
            // One rejection is enough to stop the payment; a rejected payment must not wait for the
            // remaining levels.
            paymentApprovals.findByApprovalIdOrderByApprovalLevelAsc(level.getApprovalId()).stream()
                    .filter(PaymentApproval::isPending)
                    .forEach(pending -> {
                        pending.skip("Die Zahlung wurde auf einer anderen Stufe abgelehnt");
                        paymentApprovals.save(pending);
                    });
            return saved;
        }

        boolean allDecided = paymentApprovals.findByApprovalIdOrderByApprovalLevelAsc(level.getApprovalId())
                .stream().noneMatch(PaymentApproval::isPending);
        if (allDecided) {
            payment.confirm(deciderId);
            payments.save(payment);
            payments.flush();
            // The approval record follows the levels: all agreed, so it is approved - for this payment as it is now.
            jdbc.update("""
                    update approvals set status = 'APPROVED', decided_by = ?, decided_at = now(), decision_note = ?, updated_at = now()
                    where id = ? and tenant_id = ? and status = 'PENDING'
                    """, deciderId, comment, level.getApprovalId(), tenantId);
            // What was confirmed is this payment, as it is now.
            binding.of(tenantId, "PAYMENT", payment.getId()).ifPresent(fingerprint -> jdbc.update(
                    "update payments set approved_content_hash = ? where id = ? and tenant_id = ?",
                    fingerprint.hash(), payment.getId(), tenantId));
            approvalOf(tenantId, payment.getId());
            log.info("Payment {} confirmed by {} after all levels agreed", payment.getId(), deciderId);
        }
        return saved;
    }

    /**
     * Executes a confirmed payment.
     *
     * <p>Refused unless the payment is confirmed. This is the acceptance criterion in code: there is no
     * path from a prepared payment to an executed one that skips a person.</p>
     */
    @Transactional
    public Payment execute(UUID paymentId, UUID tenantId, UUID userId, String externalReference) {
        Payment payment = payments.findByIdAndTenantIdAndDeletedAtIsNull(paymentId, tenantId)
                .orElseThrow(() -> ApiException.notFound("payment"));
        if (!payment.isConfirmed()) {
            throw ApiException.conflict(
                    "Die Zahlung ist nicht freigegeben. Eine Zahlung wird nur nach Bestätigung durch "
                            + "eine berechtigte Person ausgeführt.");
        }
        // The confirmation holds only for the payment it was given for.
        approvalService.requireBoundToCurrentContent(tenantId, "PAYMENT", paymentId);
        if (payment.isExecuted()) {
            // Executing twice is the failure this guard exists for, and it is reported rather than
            // silently ignored so the caller learns that nothing happened.
            throw ApiException.conflict("Die Zahlung wurde bereits ausgeführt");
        }
        payment.execute(userId, externalReference);
        return payments.save(payment);
    }

    @Transactional(readOnly = true)
    public Payment requirePayment(UUID paymentId, UUID tenantId) {
        return payments.findByIdAndTenantIdAndDeletedAtIsNull(paymentId, tenantId)
                .orElseThrow(() -> ApiException.notFound("payment"));
    }

    @Transactional(readOnly = true)
    public List<PaymentApproval> levelsOf(UUID paymentId) {
        return paymentApprovals.findByPaymentIdOrderByApprovalLevelAsc(paymentId);
    }

    @Transactional(readOnly = true)
    public List<Payment> awaitingConfirmation(UUID tenantId) {
        return payments.findByTenantIdAndStatus(tenantId, Payment.Status.AWAITING_APPROVAL);
    }

    /** The strictest rule that covers this payment, so a narrow rule cannot be bypassed. */
    @Transactional(readOnly = true)
    public PaymentApprovalRule ruleFor(UUID tenantId, BigDecimal amount, String currency) {
        return rules.findEffective(tenantId).stream()
                .filter(rule -> rule.appliesTo(amount, currency, "OUTGOING"))
                .max(Comparator.comparingInt(PaymentApprovalRule::getApprovalLevels)
                        .thenComparing(rule -> rule.isRequireSecondPerson() ? 1 : 0))
                .orElse(null);
    }

    /**
     * Whether the decider holds what the rule requires.
     *
     * <p>Read from the memberships and roles the platform already knows, not from a second permission
     * system: a caller who cannot approve must be refused by the same rules that govern everything
     * else.</p>
     */
    private void requireAuthorised(UUID tenantId, UUID userId, PaymentApprovalRule rule) {
        // Fail closed. A rule that names neither a role nor a permission would otherwise authorise
        // everybody, because both conditions become "no requirement". A payment without a rule still
        // needs somebody entitled to release money, and the base permission for that is
        // finance.approve.
        String requiredRole = rule == null ? null : rule.getRequiredRole();
        String requiredPermission = rule == null || rule.getRequiredPermission() == null
                ? "finance.approve" : rule.getRequiredPermission();

        Boolean authorised = jdbc.queryForObject("""
                select exists (
                    select 1 from memberships m
                    join membership_roles mr on mr.membership_id = m.id
                    join roles ro on ro.id = mr.role_id
                    left join role_permissions rp on rp.role_id = ro.id
                    left join permissions pe on pe.id = rp.permission_id
                    where m.tenant_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                      and (m.access_valid_until is null or m.access_valid_until > now())
                      and (?::text is null or ro.code = ?::text)
                      and pe.code = ?::text
                )
                """, Boolean.class, tenantId, userId, requiredRole, requiredRole, requiredPermission);

        if (!Boolean.TRUE.equals(authorised)) {
            throw ApiException.forbidden(
                    "Diese Zahlung darf nur von einer berechtigten Person freigegeben werden"
                            + (rule == null ? "" : " (Regel " + rule.getCode() + ")"));
        }
    }

    private Approval approvalOf(UUID tenantId, UUID paymentId) {
        List<Approval> found = approvals.findByTenantIdAndSubjectTypeAndSubjectId(tenantId, "PAYMENT",
                paymentId);
        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * The key that makes preparation and confirmation idempotent.
     *
     * <p>Derived from the invoice and the amount rather than from a request identifier, so it also
     * protects against two different browser sessions preparing the same payment.</p>
     */
    static String idempotencyKey(UUID invoiceId, BigDecimal amount, String currency) {
        String raw = (invoiceId == null ? "no-invoice" : invoiceId.toString()) + "|"
                + amount.stripTrailingZeros().toPlainString() + "|" + currency;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** Whether automatic execution is permitted for this tenant; the base configuration says no. */
    @Transactional(readOnly = true)
    public boolean automaticExecutionAllowed(UUID tenantId) {
        return settings.getBoolean("payments.allow_automatic_execution", false);
    }

    /** The tenant's own threshold, if it configured one, otherwise nothing is assumed. */
    @Transactional(readOnly = true)
    public BigDecimal configuredThreshold(UUID tenantId) {
        double value = settings.getDouble("payments.approval_threshold", 0);
        return value <= 0 ? null : BigDecimal.valueOf(value);
    }
}
