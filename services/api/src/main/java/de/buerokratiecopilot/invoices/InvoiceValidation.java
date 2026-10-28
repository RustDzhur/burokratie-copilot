package de.buerokratiecopilot.invoices;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks an incoming invoice before anybody acts on it (spec §14, D-003).
 *
 * <p>Two findings matter enough to be acceptance criteria, and they have different consequences:</p>
 *
 * <p><strong>Arithmetic.</strong> If the line items do not add up to the stated totals, the invoice is
 * flagged for review. The platform does not correct it: a corrected invoice is a document that differs
 * from the paper, and the paper is what gets paid.</p>
 *
 * <p><strong>Bank details.</strong> If the account the invoice asks to be paid into is not the one the
 * supplier was paid into before, that is the single most common form of invoice fraud. It does not
 * make the invoice wrong — suppliers do change banks — but it must never pass without a person looking
 * at it, which is why it blocks automatic posting rather than merely being noted.</p>
 *
 * <p>Everything here is arithmetic and comparison. No rule of tax law is applied, and no threshold is
 * invented: tolerances are passed in by the caller, who knows their own accounting practice.</p>
 */
public final class InvoiceValidation {

    /** Severity of a finding; the order is the order of attention. */
    public enum Severity {
        INFO, WARNING, BLOCKING
    }

    /** One finding. */
    public record Flag(String code, Severity severity, String message, Map<String, Object> details) {

        static Flag of(String code, Severity severity, String message) {
            return new Flag(code, severity, message, Map.of());
        }

        public Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", code);
            row.put("severity", severity.name());
            row.put("message", message);
            row.put("details", details);
            return row;
        }
    }

    /** The invoice as far as validation is concerned. */
    public record Subject(String invoiceNumber, String currency, BigDecimal netAmount,
                          BigDecimal taxAmount, BigDecimal grossAmount, List<Line> lines,
                          String iban, String knownSupplierIban, boolean supplierIbanKnown,
                          java.time.LocalDate issueDate, java.time.LocalDate dueDate,
                          String supplierName) {

        /** One line item. */
        public record Line(int lineNumber, BigDecimal netAmount, BigDecimal taxAmount,
                           BigDecimal grossAmount, String description) {
        }
    }

    /**
     * The outcome.
     *
     * @param state what the invoice is, in one word, for the interface
     */
    public record Result(String state, List<Flag> flags, boolean blocksAutomaticPosting,
                         Map<String, BigDecimal> computedTotals) {

        public List<Flag> blocking() {
            return flags.stream().filter(flag -> flag.severity() == Severity.BLOCKING).toList();
        }

        public Map<String, Object> asState() {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("state", this.state);
            state.put("blocksAutomaticPosting", blocksAutomaticPosting);
            state.put("checkedAt", java.time.Instant.now().toString());
            state.put("flags", flags.stream().map(Flag::asMap).toList());
            state.put("computedTotals", computedTotals);
            return state;
        }
    }

    /** Currency amounts are compared to the cent; anything smaller is rounding, not a discrepancy. */
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private InvoiceValidation() {
    }

    /**
     * Validates an invoice.
     *
     * @param tolerance how far the stated totals may differ from the computed ones before it counts as
     *                  a discrepancy; passed in because the acceptable rounding is an accounting
     *                  decision, not a platform one
     */
    public static Result validate(Subject subject, BigDecimal tolerance) {
        BigDecimal allowed = tolerance == null ? TOLERANCE : tolerance.abs();
        List<Flag> flags = new ArrayList<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();

        checkRequiredFields(subject, flags);
        checkArithmetic(subject, allowed, flags, totals);
        checkBankDetails(subject, flags);
        checkDates(subject, flags);

        boolean blocks = flags.stream().anyMatch(flag -> flag.severity() == Severity.BLOCKING);
        boolean hasWarnings = flags.stream().anyMatch(flag -> flag.severity() != Severity.INFO);

        String state;
        if (blocks) {
            state = "BLOCKED";
        }
        else if (hasWarnings) {
            state = "NEEDS_REVIEW";
        }
        else {
            state = "OK";
        }
        return new Result(state, List.copyOf(flags), blocks, totals);
    }

    /** Mandatory data: without these the invoice cannot be recorded at all. */
    private static void checkRequiredFields(Subject subject, List<Flag> flags) {
        if (isBlank(subject.invoiceNumber())) {
            flags.add(Flag.of("MISSING_INVOICE_NUMBER", Severity.BLOCKING,
                    "Die Rechnung hat keine Rechnungsnummer"));
        }
        if (isBlank(subject.currency())) {
            flags.add(Flag.of("MISSING_CURRENCY", Severity.BLOCKING,
                    "Die Währung fehlt; ein Betrag ohne Währung ist nicht eindeutig"));
        }
        if (subject.grossAmount() == null && subject.netAmount() == null) {
            flags.add(Flag.of("MISSING_TOTAL", Severity.BLOCKING,
                    "Weder Nettobetrag noch Bruttobetrag sind angegeben"));
        }
        if (subject.lines() == null || subject.lines().isEmpty()) {
            // Not blocking on its own: a summary invoice without lines is legitimate in some trades.
            // It is recorded because it makes every arithmetic check impossible.
            flags.add(Flag.of("NO_LINE_ITEMS", Severity.WARNING,
                    "Die Rechnung hat keine Positionen; die Summen lassen sich nicht nachrechnen"));
        }
    }

    /**
     * Recomputes the totals from the lines and compares them with what the invoice states.
     *
     * <p>The comparison is reported, never repaired. A discrepancy means the platform and the paper
     * disagree, and that is a question for a person.</p>
     */
    private static void checkArithmetic(Subject subject, BigDecimal allowed, List<Flag> flags,
                                        Map<String, BigDecimal> totals) {
        List<Subject.Line> lines = subject.lines() == null ? List.of() : subject.lines();
        if (lines.isEmpty()) {
            return;
        }

        BigDecimal net = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        for (Subject.Line line : lines) {
            net = net.add(orZero(line.netAmount()));
            tax = tax.add(orZero(line.taxAmount()));
            gross = gross.add(orZero(line.grossAmount()));
        }
        totals.put("netFromLines", net.setScale(2, RoundingMode.HALF_UP));
        totals.put("taxFromLines", tax.setScale(2, RoundingMode.HALF_UP));
        totals.put("grossFromLines", gross.setScale(2, RoundingMode.HALF_UP));

        compare(lines, "net", net, subject.netAmount(), allowed, flags);
        compare(lines, "tax", tax, subject.taxAmount(), allowed, flags);
        compare(lines, "gross", gross, subject.grossAmount(), allowed, flags);
    }

    private static void compare(List<Subject.Line> lines, String what, BigDecimal computed,
                                BigDecimal stated, BigDecimal allowed, List<Flag> flags) {
        if (stated == null) {
            return;
        }
        BigDecimal difference = computed.subtract(stated).abs();
        if (difference.compareTo(allowed) <= 0) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fromLines", computed.setScale(2, RoundingMode.HALF_UP).toPlainString());
        details.put("stated", stated.setScale(2, RoundingMode.HALF_UP).toPlainString());
        details.put("difference", difference.setScale(2, RoundingMode.HALF_UP).toPlainString());
        details.put("lineCount", lines.size());

        flags.add(new Flag("TOTAL_MISMATCH_" + what.toUpperCase(java.util.Locale.ROOT),
                Severity.BLOCKING,
                "Die Summe der Positionen (%s) stimmt nicht mit dem angegebenen Betrag (%s) überein"
                        .formatted(details.get("fromLines"), details.get("stated")),
                details));
    }

    /**
     * Compares the account on the invoice with the one the supplier was paid into before.
     *
     * <p>Only a <em>change</em> is suspicious. A supplier seen for the first time produces an INFO
     * entry, because there is nothing to compare against — inventing suspicion there would train users
     * to ignore the warning that matters.</p>
     */
    private static void checkBankDetails(Subject subject, List<Flag> flags) {
        if (isBlank(subject.iban())) {
            flags.add(Flag.of("MISSING_IBAN", Severity.WARNING,
                    "Die Rechnung nennt keine Bankverbindung"));
            return;
        }
        String normalized = subject.iban().replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
        if (!subject.supplierIbanKnown()) {
            flags.add(new Flag("IBAN_FIRST_SEEN", Severity.INFO,
                    "Erste Bankverbindung dieses Lieferanten; es gibt nichts zu vergleichen",
                    Map.of("iban", normalized)));
            return;
        }
        String known = subject.knownSupplierIban() == null ? ""
                : subject.knownSupplierIban().replaceAll("\\s", "").toUpperCase(java.util.Locale.ROOT);
        if (!normalized.equals(known)) {
            // Blocking on purpose: this is how invoice fraud works, and a warning that can be clicked
            // away is not a control.
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("ibanOnInvoice", normalized);
            details.put("knownIban", known);
            details.put("supplier", subject.supplierName() == null ? "" : subject.supplierName());
            flags.add(new Flag("IBAN_CHANGED", Severity.BLOCKING,
                    "Die Bankverbindung weicht von der bisher bekannten ab; die Rechnung darf nicht "
                            + "automatisch gebucht werden",
                    details));
        }
    }

    private static void checkDates(Subject subject, List<Flag> flags) {
        if (subject.issueDate() == null) {
            flags.add(Flag.of("MISSING_ISSUE_DATE", Severity.WARNING,
                    "Kein Rechnungsdatum angegeben"));
        }
        if (subject.issueDate() != null && subject.dueDate() != null
                && subject.dueDate().isBefore(subject.issueDate())) {
            flags.add(new Flag("DUE_BEFORE_ISSUE", Severity.WARNING,
                    "Das Zahlungsziel liegt vor dem Rechnungsdatum",
                    Map.of("issueDate", subject.issueDate().toString(),
                            "dueDate", subject.dueDate().toString())));
        }
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
