package de.buerokratiecopilot.agents.communication;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.mail.inbound.MailCategoriser;

/**
 * {@code mail-sort} — E-Mail-Sortierung.
 *
 * <p>Classifies an incoming mail with the production rule engine
 * ({@link MailCategoriser}: subject, beginning of the body, sender, attachment names), maps the
 * category to a processing channel and suggests the case the mail belongs to — either the case id the
 * caller passes in (verified against the database) or a case whose reference is quoted in the subject
 * or body (e.g. {@code V-2026-0042}).
 *
 * <p>The classification is a label only: nothing is filed or deleted here, a suspicious mail is
 * reported, not hidden. Deterministic and explainable — no external AI in the pilot.</p>
 */
@Component
public class MailSortAgent implements Agent {

    /** Case references the platform itself generates, as quoted in correspondence. */
    private static final Pattern CASE_REFERENCE = Pattern.compile("\\b(V-\\d{4}-\\d{1,6})\\b");

    /** Category → processing channel, mirroring the {@code config.channels} of the manifest. */
    private static final Map<String, String> CATEGORY_TO_CHANNEL = Map.of(
            "SUSPICIOUS", "security",
            "INVOICES", "finance",
            "CONTRACTS", "legal",
            "RESPONSES", "cases",
            "INCOMING_DOCUMENTS", "documents",
            "NOTIFICATIONS", "info",
            "ADVERTISEMENT", "ignore",
            "OTHER", "manual");

    private final JdbcTemplate jdbc;

    public MailSortAgent(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "mail-sort";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String subject = context.input("subject");
        String bodyText = context.input("bodyText");
        String from = context.input("from");
        String attachmentNames = context.input("attachmentNames");
        boolean isReply = Boolean.parseBoolean(context.input("isReply"));

        if (isBlank(subject) && isBlank(bodyText) && isBlank(from)) {
            return AgentResult.skipped(
                    "Keine Maildaten vorhanden: Betreff, Text und Absender fehlen.");
        }

        List<String> names = splitList(attachmentNames);
        MailCategoriser.Result classified = MailCategoriser.classify(subject, bodyText, from,
                names, isReply);

        List<AgentResult.Produced> produced = new ArrayList<>();
        produced.add(new AgentResult.Produced("classification", classified.category(),
                "Kategorie: " + classified.category()));
        produced.add(new AgentResult.Produced("suggestion", channelFor(classified.category()),
                "Kanal-Vorschlag: " + channelFor(classified.category())));

        Map<String, String> meta = new java.util.LinkedHashMap<>();
        meta.put("category", classified.category());
        meta.put("confidence", String.valueOf(classified.confidence()));
        meta.put("suspicious", String.valueOf(classified.suspicious()));
        meta.put("channel", channelFor(classified.category()));

        UUID suggestedCase = suggestCase(context.tenantId(), subject, bodyText, context.input("caseId"));
        if (suggestedCase != null) {
            produced.add(new AgentResult.Produced("suggestion", suggestedCase.toString(),
                    "Vorgang-Vorschlag: " + suggestedCase));
            meta.put("suggestedCaseId", suggestedCase.toString());
        }

        String summary = "E-Mail klassifiziert als " + classified.category() + " → Kanal "
                + meta.get("channel") + (suggestedCase == null ? "" : ", Vorgang vorgeschlagen");
        if (classified.suspicious()) {
            summary = "Verdächtige E-Mail erkannt (" + classified.category()
                    + "). Die Nachricht wird nicht automatisch weiterverarbeitet.";
        }
        return AgentResult.done(summary, produced, meta);
    }

    /**
     * The case the mail belongs to: the caller's {@code caseId} when it exists in this tenant,
     * otherwise a case whose reference the mail itself quotes.
     */
    private UUID suggestCase(UUID tenantId, String subject, String bodyText, String explicitCaseId) {
        if (!isBlank(explicitCaseId)) {
            try {
                UUID caseId = UUID.fromString(explicitCaseId.strip());
                if (caseExists(tenantId, caseId)) {
                    return caseId;
                }
            }
            catch (IllegalArgumentException ignored) {
                // Not a UUID: fall through to the reference search.
            }
        }
        String haystack = (subject == null ? "" : subject) + " " + (bodyText == null ? "" : bodyText);
        Matcher matcher = CASE_REFERENCE.matcher(haystack);
        while (matcher.find()) {
            UUID caseId = caseIdByReference(tenantId, matcher.group(1));
            if (caseId != null) {
                return caseId;
            }
        }
        return null;
    }

    private boolean caseExists(UUID tenantId, UUID caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id from cases where id = ? and tenant_id = ? and deleted_at is null
                """, caseId, tenantId);
        return !rows.isEmpty();
    }

    private UUID caseIdByReference(UUID tenantId, String reference) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id from cases
                where tenant_id = ? and reference = ? and deleted_at is null
                limit 1
                """, tenantId, reference);
        if (rows.isEmpty()) {
            return null;
        }
        Object id = rows.getFirst().get("id");
        return id instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(id));
    }

    private static String channelFor(String category) {
        return CATEGORY_TO_CHANNEL.getOrDefault(category, "manual");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<String> splitList(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : commaSeparated.split(",")) {
            if (!part.isBlank()) {
                items.add(part.strip().toLowerCase(Locale.ROOT));
            }
        }
        return items;
    }
}
