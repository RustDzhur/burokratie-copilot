package de.buerokratiecopilot.invoices.outgoing;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Allocates invoice numbers (spec §14, D-006).
 *
 * <p>The acceptance criterion is numbering without gaps and without duplicates, and both halves come
 * from the same decision: the number is taken inside the transaction that creates the invoice, and the
 * row is locked while that happens.</p>
 *
 * <ul>
 *   <li><strong>No duplicates</strong> — the sequence row is locked {@code FOR UPDATE}, so two
 *       simultaneous requests take different numbers instead of the same one.</li>
 *   <li><strong>No gaps</strong> — a failed invoice rolls the counter back with the rest of the
 *       transaction. A database sequence would keep the number and leave a hole, and a hole in an
 *       invoice series is a question an auditor asks.</li>
 * </ul>
 *
 * <p>A number taken and then not used because the caller changed its mind is a gap too, which is why
 * there is no "reserve" method here: a caller either writes the invoice in the same transaction or does
 * not take a number at all.</p>
 */
@Service
public class InvoiceNumberService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceNumberService.class);

    private final InvoiceNumberSequenceRepository sequences;

    public InvoiceNumberService(InvoiceNumberSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /**
     * Takes the next number of a sequence.
     *
     * <p>Must be called inside the transaction that stores the invoice; otherwise the number is spent
     * without anything to attach it to.</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public String nextNumber(UUID tenantId, String code, LocalDate today) {
        InvoiceNumberSequence sequence = sequences.lockByTenantAndCode(tenantId, code)
                .orElseThrow(() -> ApiException.notFound("number sequence " + code));
        if (!sequence.isActive()) {
            throw ApiException.conflict("Die Nummernfolge " + code + " ist nicht aktiv");
        }
        long number = sequence.takeNext(today);
        sequences.save(sequence);
        String rendered = sequence.format(number, today);
        log.info("Invoice number {} taken from sequence {}", rendered, code);
        return rendered;
    }

    /** The next number without taking it, for a preview. */
    @Transactional(readOnly = true)
    public String peek(UUID tenantId, String code, LocalDate today) {
        InvoiceNumberSequence sequence = sequences.findByTenantIdAndCode(tenantId, code)
                .orElseThrow(() -> ApiException.notFound("number sequence " + code));
        long candidate = sequence.getNextValue();
        if (sequence.getResetPolicy() == InvoiceNumberSequence.ResetPolicy.YEARLY
                && (sequence.getYear() == null || sequence.getYear() != today.getYear())) {
            candidate = 1;
        }
        return sequence.format(candidate, today);
    }

    @Transactional
    public InvoiceNumberSequence createSequence(UUID tenantId, String code, String name, String pattern,
                                                InvoiceNumberSequence.ResetPolicy resetPolicy) {
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Nummernfolge braucht einen Code");
        }
        if (pattern == null || !pattern.contains("{number")) {
            // Without the placeholder every invoice would get the same number, which is the failure
            // this service exists to prevent.
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Das Muster muss den Platzhalter {number} enthalten, sonst erhalten alle Rechnungen "
                            + "dieselbe Nummer");
        }
        sequences.findByTenantIdAndCode(tenantId, code).ifPresent(existing -> {
            throw ApiException.duplicate("Eine Nummernfolge mit diesem Code existiert bereits");
        });

        InvoiceNumberSequence sequence = new InvoiceNumberSequence(UUID.randomUUID(), tenantId,
                code.strip(), name == null || name.isBlank() ? code.strip() : name.strip(),
                pattern.strip());
        if (resetPolicy != null) {
            sequence.setResetPolicy(resetPolicy);
        }
        return sequences.save(sequence);
    }

    @Transactional(readOnly = true)
    public List<InvoiceNumberSequence> list(UUID tenantId) {
        return sequences.findByTenantIdAndActiveTrueOrderByCode(tenantId);
    }

    @Transactional(readOnly = true)
    public Optional<InvoiceNumberSequence> find(UUID tenantId, String code) {
        return sequences.findByTenantIdAndCode(tenantId, code);
    }
}
