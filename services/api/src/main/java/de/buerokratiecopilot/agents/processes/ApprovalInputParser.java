package de.buerokratiecopilot.agents.processes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * Pure input parsing for {@code approval}: actions, amounts, directions and decisions are
 * normalised here so the agent's behaviour is testable without a database.
 *
 * <p>Invalid values are reported as {@code null} — the caller turns that into an honest
 * {@code failed} result instead of guessing a default that could authorise the wrong thing.</p>
 */
public final class ApprovalInputParser {

    private ApprovalInputParser() {
    }

    /** The three things the approval agent does. Unknown values yield {@code null}. */
    public static String parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return "request";
        }
        String action = raw.strip().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "request", "decide", "status" -> action;
            default -> null;
        };
    }

    /** A decimal amount, or {@code null} when absent; invalid text is a hard error, not zero. */
    public static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.strip().replace(',', '.'));
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /** The approval direction, normalised to the three values the authority matrix understands. */
    public static String parseDirection(String raw) {
        if (raw == null || raw.isBlank()) {
            return "ANY";
        }
        String direction = raw.strip().toUpperCase(Locale.ROOT);
        return switch (direction) {
            case "INCOMING", "OUTGOING", "ANY" -> direction;
            default -> null;
        };
    }

    /** {@code true} / {@code false} only; anything else is {@code null} (no silent default). */
    public static Boolean parseApproved(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
    }

    /** An ISO-8601 instant, or {@code null} when absent; invalid text throws for the caller to report. */
    public static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        }
        catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The approval types the schema allows (V6); everything else becomes {@code OTHER}. */
    private static final Set<String> KNOWN_TYPES = Set.of(
            "INVOICE_APPROVAL", "PAYMENT_APPROVAL", "CONTRACT_TERMINATION", "OUTBOUND_MESSAGE",
            "DOCUMENT_DELETION", "DATA_EXPORT", "ROLE_CHANGE", "WORKFLOW_PUBLISH",
            "INTEGRATION_CONNECT", "HIGH_RISK_AI_ACTION", "OTHER");

    public static String normalizeApprovalType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "OTHER";
        }
        String type = raw.strip().toUpperCase(Locale.ROOT);
        return KNOWN_TYPES.contains(type) ? type : "OTHER";
    }
}
