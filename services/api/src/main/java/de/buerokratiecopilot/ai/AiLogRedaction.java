package de.buerokratiecopilot.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Keeps document content out of the logs (spec §25, E-005 acceptance).
 *
 * <p>The acceptance criterion says the logs contain no document content, and it cannot be met by
 * remembering not to log it. Every prompt in this platform is built from a case file — a contract, an
 * invoice, a letter from an authority — so anything that logs a prompt logs somebody's affairs.</p>
 *
 * <p>What may be logged is what is needed to investigate a failure: which job, which model, how long,
 * how much, and whether it worked. What may not is anything a person wrote. This class is the only way
 * the orchestrator writes about a request, so a future caller cannot accidentally do otherwise.</p>
 */
public final class AiLogRedaction {

    /** Anything that looks like a key, a token or a bearer credential is removed wherever it appears. */
    private static final List<Pattern> SECRETS = List.of(
            Pattern.compile("(?i)(sk|pk|api[_-]?key|bearer|token)[-_:= ]+[A-Za-z0-9_\\-]{8,}"),
            Pattern.compile("(?i)authorization:\\s*\\S+"));

    /** The shape of a prompt, so an accidental interpolation is recognisable. */
    private static final Pattern LONG_FREE_TEXT = Pattern.compile("(?s).{200,}");

    private AiLogRedaction() {
    }

    /**
     * A log-safe description of a request.
     *
     * <p>Carries the identifiers and the metadata and never the input. The input is reduced to its length
     * and a hash, which is enough to tell two requests apart when investigating and useless for reading
     * anybody's post.</p>
     */
    public static Map<String, Object> describe(AiProvider.Request request) {
        Map<String, Object> safe = new LinkedHashMap<>();
        safe.put("jobType", request.jobType());
        safe.put("model", request.model());
        safe.put("instructionVersion", request.instructionVersion());
        safe.put("inputLength", request.input() == null ? 0 : request.input().length());
        safe.put("inputHash", request.instructionHash());
        safe.put("timeoutSeconds", request.timeoutSeconds());
        return safe;
    }

    /**
     * Removes anything that looks like a secret or like somebody's writing.
     *
     * @return text safe to write to a log line
     */
    public static String redact(String text) {
        if (text == null) {
            return "";
        }
        String redacted = text;
        for (Pattern pattern : SECRETS) {
            redacted = pattern.matcher(redacted).replaceAll("[entfernt]");
        }
        if (LONG_FREE_TEXT.matcher(redacted).matches()) {
            // A long free text in a log line is a document, whatever it was called.
            return "[Inhalt entfernt: " + redacted.length() + " Zeichen]";
        }
        return redacted;
    }

    /** Whether a value may be written to a log line at all. */
    public static boolean isSafeToLog(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        if (LONG_FREE_TEXT.matcher(value).matches()) {
            return false;
        }
        return SECRETS.stream().noneMatch(pattern -> pattern.matcher(value).find());
    }

    /**
     * Checks that a completed log record really carries no content.
     *
     * <p>Used by the test that verifies the acceptance criterion, and by the orchestrator before it writes
     * a run record: a check that only the test performs cannot protect production.</p>
     */
    public static List<String> offendingFields(Map<String, Object> record, String forbiddenContent) {
        List<String> offenders = new java.util.ArrayList<>();
        if (forbiddenContent == null || forbiddenContent.isBlank()) {
            return offenders;
        }
        record.forEach((key, value) -> {
            String text = value == null ? "" : String.valueOf(value);
            if (text.contains(forbiddenContent) || text.length() > 200) {
                offenders.add(key);
            }
        });
        return offenders;
    }
}
