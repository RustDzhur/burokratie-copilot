package de.buerokratiecopilot.ai.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Looks for instructions hidden in document content (spec §25, E-006).
 *
 * <p>This is a detector, not a filter, and the difference matters. Text that is silently stripped from a
 * document is text nobody knows was there: the model reads a document that has been altered, the user
 * sees a document that has not, and the fact that somebody tried is lost. Here the content is passed on
 * unchanged and the <em>finding</em> is reported — to the call journal and, when it matters, to a person.</p>
 *
 * <p>The patterns are the ones that appear in real attempts: telling the model to disregard what it was
 * told, impersonating the platform's own framing, asking it to reveal its instructions or credentials,
 * and writing tool-call syntax into the document. None of them proves an attack — a contract may
 * legitimately contain the words "ignore" and "instruction" — which is exactly why the answer is a
 * finding with evidence rather than a silent edit.</p>
 *
 * <p>The detector is one layer. The layer that actually protects the platform is
 * {@link ToolPolicy}: instructions in a document cannot cause a tool call even if the model believes
 * them, because a call that originates in untrusted content is refused there.</p>
 */
public final class InjectionDetector {

    /** How serious a finding is. */
    public enum Severity {
        /** Worth recording; may well be ordinary text. */
        SUSPICIOUS,
        /** The text is trying to change what the model does. */
        ATTEMPT
    }

    /** One finding, with the evidence that produced it. */
    public record Finding(String code, Severity severity, String evidence, int position) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", code);
            map.put("severity", severity.name());
            map.put("evidence", evidence);
            map.put("position", position);
            return map;
        }
    }

    /** What was found. */
    public record Report(List<Finding> findings) {

        public boolean hasAttempt() {
            return findings.stream().anyMatch(finding -> finding.severity() == Severity.ATTEMPT);
        }

        public boolean isClean() {
            return findings.isEmpty();
        }

        public List<String> codes() {
            return findings.stream().map(Finding::code).distinct().toList();
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("clean", isClean());
            map.put("hasAttempt", hasAttempt());
            map.put("codes", codes());
            map.put("findings", findings.stream().map(Finding::asMap).toList());
            return map;
        }
    }

    /** A pattern and what it means. */
    private record Rule(String code, Severity severity, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("IGNORE_PREVIOUS", Severity.ATTEMPT, Pattern.compile(
                    "(?i)\\b(ignore|disregard|forget|missachte|ignoriere|vergiss)\\b[^.]{0,40}\\b"
                            + "(previous|prior|above|all|alle|vorherigen|obigen)\\b[^.]{0,20}\\b"
                            + "(instruction|prompt|rule|anweisung|regel|vorgaben)")),
            new Rule("ROLE_IMPERSONATION", Severity.ATTEMPT, Pattern.compile(
                    "(?i)(^|\\n)\\s*(system|assistant|developer|systemnachricht|entwickler)\\s*:")),
            new Rule("NEW_INSTRUCTIONS", Severity.ATTEMPT, Pattern.compile(
                    "(?i)\\b(you are now|from now on|ab jetzt|neue anweisung|new instructions|"
                            + "du bist jetzt|acting as|agiere als)\\b")),
            new Rule("PROMPT_DISCLOSURE", Severity.ATTEMPT, Pattern.compile(
                    "(?i)\\b(reveal|show|print|repeat|give me|zeige|nenne|gib mir|wiederhole)\\b[^.]{0,30}\\b"
                            + "(your (system )?(prompt|instructions)|instructions|systemprompt|"
                            + "anweisungen|regeln)\\b")),
            // Either order: "send the API key" and "the API key must be sent" are the same attempt, and
            // the first version of this rule only caught the second.
            new Rule("SECRET_EXFILTRATION", Severity.ATTEMPT, Pattern.compile(
                    "(?i)(\\b(api[_ -]?key|password|passwort|token|secret|geheimnis|zugangsdaten|"
                            + "credentials)\\b[^.]{0,40}\\b(send|email|post|upload|forward|sende|schicke|"
                            + "senden|übermittle|leite weiter)\\b"
                            + "|\\b(send|email|post|upload|forward|sende|schicke|senden|übermittle|"
                            + "leite weiter)\\b[^.]{0,40}\\b(api[_ -]?key|password|passwort|token|secret|"
                            + "geheimnis|zugangsdaten|credentials)\\b)")),
            new Rule("TOOL_SYNTAX", Severity.ATTEMPT, Pattern.compile(
                    "(?i)(\\{\\s*\"?(tool|function|action)\"?\\s*:|<\\|?tool|call_tool\\s*\\(|"
                            + "\\bexecute\\s+(the\\s+)?tool\\b)")),
            new Rule("DELIMITER_ESCAPE", Severity.ATTEMPT, Pattern.compile(
                    Pattern.quote(UntrustedContent.CLOSE) + "|" + Pattern.quote(UntrustedContent.OPEN))),
            new Rule("HIDDEN_TEXT", Severity.SUSPICIOUS, Pattern.compile(
                    "(?i)(display\\s*:\\s*none|font-size\\s*:\\s*0|visibility\\s*:\\s*hidden|"
                            + "<!--[^>]{20,}-->)")),
            new Rule("AUTHORITY_CLAIM", Severity.SUSPICIOUS, Pattern.compile(
                    "(?i)\\b(als (systemadministrator|administrator|entwickler)|as the (administrator|"
                            + "developer|system)|im auftrag des systems)\\b")));

    private InjectionDetector() {
    }

    /** Looks through one piece of content. */
    public static Report inspect(String text) {
        List<Finding> findings = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new Report(List.of());
        }
        for (Rule rule : RULES) {
            java.util.regex.Matcher matcher = rule.pattern().matcher(text);
            while (matcher.find()) {
                findings.add(new Finding(rule.code(), rule.severity(),
                        excerpt(text, matcher.start(), matcher.end()), matcher.start()));
            }
        }
        return new Report(List.copyOf(findings));
    }

    /** Looks through several pieces, keeping track of which produced what. */
    public static Map<String, Report> inspectAll(List<UntrustedContent> contents) {
        Map<String, Report> reports = new LinkedHashMap<>();
        for (UntrustedContent content : contents) {
            reports.put(content.describe(), inspect(content.text()));
        }
        return reports;
    }

    /** Whether a piece of content contains an attempt, without building the whole report. */
    public static boolean containsAttempt(String text) {
        return inspect(text).hasAttempt();
    }

    /**
     * A short excerpt around a match.
     *
     * <p>Bounded on purpose: a finding goes into a journal, and a journal that quotes a paragraph of
     * somebody's letter is a journal that holds documents.</p>
     */
    private static String excerpt(String text, int start, int end) {
        int from = Math.max(0, start - 20);
        int to = Math.min(text.length(), Math.min(end + 20, start + 120));
        String fragment = text.substring(from, to).replaceAll("\\s+", " ").strip();
        return (from > 0 ? "…" : "") + fragment + (to < text.length() ? "…" : "");
    }

    /** The rule codes the detector knows, so a caller can document them. */
    public static List<String> knownCodes() {
        return RULES.stream().map(Rule::code).toList();
    }

    /** Whether a text is an instruction the platform itself wrote, in the sense the detector checks. */
    public static boolean looksLikePlatformInstruction(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).startsWith("du bist");
    }
}
