package de.buerokratiecopilot.agents.marketing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code marketing-newsletter} — Newsletter-Kampagne.
 *
 * <p>Assembles a send-ready <em>draft</em>: recipient list from the CRM contacts matching the
 * segment, subject variants, content blocks and the planned send time. The list is checked for
 * duplicates and missing addresses. Consent is not assumed: the contact record has no double-opt-in
 * proof, so the run states that explicitly and files a consent-check task. Nothing is sent.</p>
 */
@Component
public class NewsletterAgent implements Agent {

    private static final int MAX_RECIPIENTS = 2000;

    private final JdbcTemplate jdbc;
    private final AgentWorkspace workspace;

    public NewsletterAgent(JdbcTemplate jdbc, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "marketing-newsletter";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String campaign = AgentInputs.clean(context.input("campaignName"));
        String segment = AgentInputs.clean(context.input("audienceSegment"));
        if (campaign == null || segment == null) {
            return AgentResult.skipped("Newsletter: 'campaignName' und 'audienceSegment' sind "
                    + "erforderlich.");
        }
        Instant sendAt = AgentInputs.instant(context.input("sendAt"));
        if (context.input("sendAt") != null && !context.input("sendAt").isBlank() && sendAt == null) {
            return AgentResult.failed("Newsletter: 'sendAt' ist kein gültiger Zeitpunkt.");
        }
        if (sendAt == null) {
            sendAt = context.now().plus(Duration.ofDays(3));
        }
        boolean consentConfirmed = AgentInputs.bool(context.input("consentConfirmed"), false);

        List<Map<String, Object>> contacts = jdbc.queryForList("""
                select id, display_name, email::text as email, is_customer, metadata::text as metadata
                from contacts
                where tenant_id = ? and deleted_at is null and email is not null
                order by display_name limit ?
                """, context.tenantId(), MAX_RECIPIENTS);
        List<Recipient> recipients = selectRecipients(contacts, segment);
        if (recipients.isEmpty()) {
            return AgentResult.skipped("Newsletter: im Segment '" + segment
                    + "' gibt es keine Kontakte mit E-Mail-Adresse.");
        }
        List<String> subjects = subjectVariants(context.input("subjectVariants"), campaign);
        List<String> blocks = contentBlocks(context.input("contentBlocks"), campaign, segment);
        String sourceKey = "marketing-newsletter:" + AgentInputs.key(campaign, segment,
                String.join("|", subjects), String.join("|", blocks));
        String html = html(campaign, segment, sendAt, subjects, blocks, recipients, consentConfirmed);
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "Newsletter: " + campaign, html,
                Map.of("campaign", campaign, "segment", segment, "recipients", recipients.size(),
                        "sendAt", sendAt.toString(), "consentConfirmed", consentConfirmed),
                List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(),
                    "Newsletter-Entwurf: " + campaign);
        }
        String approvalTitle = "Newsletter freigeben: " + campaign;
        AgentWorkspace.TaskRef approval = workspace.ensureTask(context, sourceKey + ":approval",
                approvalTitle,
                recipients.size() + " Empfänger im Segment „" + segment + "“, Versand geplant "
                        + sendAt + ". Der Agent versendet nichts; Freigabe und Versand sind "
                        + "menschliche Schritte.",
                Task.Priority.NORMAL, sendAt.minus(Duration.ofDays(1)),
                List.of("marketing", "newsletter"),
                Map.of("campaign", campaign, "recipients", recipients.size()));
        if (approval.created()) {
            context.addProduced("task", approval.id().toString(), approvalTitle);
        }
        boolean consentTask = false;
        if (!consentConfirmed) {
            AgentWorkspace.TaskRef check = workspace.ensureTask(context, sourceKey + ":consent",
                    "Newsletter-Einwilligungen prüfen: " + campaign,
                    "Für die " + recipients.size() + " Kontakte im Segment „" + segment
                            + "“ liegt kein Double-Opt-in-Nachweis in den Kontaktdaten. Vor dem "
                            + "Versand Einwilligung und Abmeldungen prüfen. Kein Versand durch den "
                            + "Agenten.",
                    Task.Priority.HIGH, sendAt.minus(Duration.ofDays(2)),
                    List.of("marketing", "newsletter", "consent"),
                    Map.of("campaign", campaign, "recipients", recipients.size()));
            consentTask = check.created();
            if (consentTask) {
                context.addProduced("task", check.id().toString(), "Einwilligungen prüfen: " + campaign);
            }
        }
        for (Recipient recipient : recipients.stream().limit(10).toList()) {
            context.addProduced("newsletter-recipient", recipient.id() == null ? recipient.email()
                    : recipient.id().toString(), recipient.name() + " <" + recipient.email() + ">");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("campaign", campaign);
        meta.put("segment", segment);
        meta.put("recipients", String.valueOf(recipients.size()));
        meta.put("sendAt", sendAt.toString());
        meta.put("consentVerified", String.valueOf(consentConfirmed));
        meta.put("consentTaskCreated", String.valueOf(consentTask));
        meta.put("sent", "false");
        return AgentResult.done("Newsletter-Entwurf „" + campaign + "“ für " + recipients.size()
                + " Empfänger zusammengestellt, " + subjects.size() + " Betreffzeilen-Variante(n); "
                + (consentConfirmed ? "Einwilligungen laut Angabe bestätigt"
                        : "Einwilligungen sind vor dem Versand zu prüfen")
                + ". Versand nicht ausgeführt.", context.produced(), meta);
    }

    /** Filters contacts by the segment vocabulary the CRM data actually supports. */
    public static List<Recipient> selectRecipients(List<Map<String, Object>> contacts, String segment) {
        String normalized = segment == null ? "" : segment.strip().toLowerCase(Locale.ROOT);
        List<Recipient> recipients = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> contact : contacts) {
            String email = contact.get("email") == null ? null : String.valueOf(contact.get("email")).strip();
            if (email == null || email.isEmpty() || !seen.add(email.toLowerCase(Locale.ROOT))) {
                continue;
            }
            boolean customer = Boolean.TRUE.equals(contact.get("is_customer"))
                    || "true".equalsIgnoreCase(String.valueOf(contact.get("is_customer")));
            String metadata = contact.get("metadata") == null ? "{}"
                    : String.valueOf(contact.get("metadata"));
            boolean matches = switch (normalized) {
                case "alle", "all", "gesamt", "alle kontakte" -> true;
                case "kunden", "kunde", "customers", "customer" -> customer;
                case "leads", "lead", "interessenten" -> metadata.contains("\"lead\"");
                default -> metadata.toLowerCase(Locale.ROOT)
                        .contains("\"" + normalized + "\"");
            };
            if (!matches) {
                continue;
            }
            recipients.add(new Recipient((java.util.UUID) contact.get("id"),
                    contact.get("display_name") == null ? email
                            : String.valueOf(contact.get("display_name")), email, customer));
        }
        return List.copyOf(recipients);
    }

    static List<String> subjectVariants(String raw, String campaign) {
        List<String> given = AgentInputs.list(raw);
        if (!given.isEmpty()) {
            return given.stream().limit(5).toList();
        }
        return List.of(campaign, campaign + ": das Wichtigste im Überblick",
                "Neu für Sie: " + campaign);
    }

    static List<String> contentBlocks(String raw, String campaign, String segment) {
        List<String> given = AgentInputs.list(raw);
        if (!given.isEmpty()) {
            return given.stream().limit(12).toList();
        }
        return List.of("Aufhänger: " + campaign + " für " + segment,
                "Kerninhalt: Nutzen und konkrete nächste Schritte",
                "Hinweis: Quellen und Angebote vor dem Versand prüfen",
                "Abmeldung und Impressum");
    }

    private static String html(String campaign, String segment, Instant sendAt,
                               List<String> subjects, List<String> blocks,
                               List<Recipient> recipients, boolean consentConfirmed) {
        StringBuilder html = new StringBuilder("<h1>Newsletter-Entwurf: ")
                .append(AgentInputs.html(campaign)).append("</h1>")
                .append("<p>Segment: ").append(AgentInputs.html(segment))
                .append("<br>Geplanter Versand: ").append(sendAt)
                .append("<br>Empfänger: ").append(recipients.size())
                .append("<br>Einwilligung bestätigt: ").append(consentConfirmed ? "ja" : "nein — vor Versand prüfen")
                .append("</p><h2>Betreffzeilen-Varianten</h2><ul>");
        for (String subject : subjects) {
            html.append("<li>").append(AgentInputs.html(subject)).append("</li>");
        }
        html.append("</ul><h2>Inhaltsblöcke</h2><ol>");
        for (String block : blocks) {
            html.append("<li>").append(AgentInputs.html(block)).append("</li>");
        }
        html.append("</ol><h2>Empfängerliste (Auszug)</h2><ul>");
        for (Recipient recipient : recipients.stream().limit(50).toList()) {
            html.append("<li>").append(AgentInputs.html(recipient.name())).append(" &lt;")
                    .append(AgentInputs.html(recipient.email())).append("&gt;</li>");
        }
        html.append("</ul><p><em>Entwurf, nicht versendet. Double-Opt-in, Abmeldungen und "
                + "Einwilligungen sind vor dem Versand zu prüfen; der Agent hat keinen Versandkanal.</em></p>");
        return html.toString();
    }

    /** One selected contact. */
    public record Recipient(java.util.UUID id, String name, String email, boolean customer) {
    }
}
