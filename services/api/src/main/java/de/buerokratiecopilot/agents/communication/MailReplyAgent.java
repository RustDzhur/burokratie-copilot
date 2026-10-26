package de.buerokratiecopilot.agents.communication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.mail.MailListService;
import de.buerokratiecopilot.mail.inbound.MailCategoriser;
import de.buerokratiecopilot.mail.outbound.OutboundMessageService;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.templates.TemplateService;

/**
 * {@code mail-reply} — Antwort-Assistenz.
 *
 * <p>Prepares a reply <em>draft</em> for an incoming mail. It never sends: the result is an
 * {@code outbound_messages} row with status {@code DRAFT} that a person has to release
 * ({@link OutboundMessageService#draft} guarantees this). The body comes from a published document
 * template when one is named or matches the mail's category (the templates module renders it and
 * refuses half-filled letters), otherwise from the caller's {@code draftText}, otherwise from a
 * rule-based German acknowledgment text.
 *
 * <p>Idempotent: the draft carries the key {@code mail-reply:<mailId>:<source>}, and
 * {@code OutboundMessageService} returns the existing draft on a re-run instead of writing a
 * second one.</p>
 */
@Component
public class MailReplyAgent implements Agent {

    /** Mail category → term searched in published template names. */
    private static final Map<String, String> CATEGORY_TO_TEMPLATE_TERM = Map.of(
            "INVOICES", "Mahnung",
            "CONTRACTS", "Vertrag",
            "RESPONSES", "Antwort",
            "NOTIFICATIONS", "Eingang",
            "INCOMING_DOCUMENTS", "Eingang",
            "OTHER", "Antwort");

    private final MailListService mailListService;
    private final TemplateService templateService;
    private final OutboundMessageService outboundMessageService;

    public MailReplyAgent(MailListService mailListService, TemplateService templateService,
                          OutboundMessageService outboundMessageService) {
        this.mailListService = mailListService;
        this.templateService = templateService;
        this.outboundMessageService = outboundMessageService;
    }

    @Override
    public String id() {
        return "mail-reply";
    }

    @Override
    public AgentResult run(AgentContext context) {
        String mailIdRaw = context.input("mailId");
        UUID mailId;
        try {
            mailId = UUID.fromString(mailIdRaw == null ? "" : mailIdRaw.strip());
        }
        catch (IllegalArgumentException ex) {
            return AgentResult.failed("mailId fehlt oder ist keine UUID.");
        }

        Map<String, Object> mail;
        try {
            mail = mailListService.detail(context.tenantId(), mailId);
        }
        catch (ApiException ex) {
            return AgentResult.failed("E-Mail nicht gefunden: " + ex.getMessage());
        }

        if ("DRAFT".equals(String.valueOf(mail.get("direction")))) {
            return AgentResult.skipped("Die Nachricht ist ein Entwurf, keine eingegangene E-Mail.");
        }
        String originalSubject = String.valueOf(mail.getOrDefault("subject", ""));
        String fromAddress = mail.get("from_address") == null ? null
                : String.valueOf(mail.get("from_address"));

        String recipient = context.input("to");
        if (isBlank(recipient)) {
            recipient = fromAddress;
        }
        if (isBlank(recipient)) {
            return AgentResult.skipped("Kein Empfänger: die E-Mail hat keinen Absender und 'to' fehlt.");
        }

        UUID caseId = parseUuid(context.input("caseId"));
        UUID templateId = resolveTemplate(context, mail);
        String source = templateId == null ? "acknowledgment" : templateId.toString();

        String subject;
        String bodyText;
        if (templateId != null) {
            Map<String, String> values = fieldValues(context, mail);
            TemplateService.Generated generated;
            try {
                generated = templateService.generate(context.tenantId(), templateId, values);
            }
            catch (ApiException ex) {
                return AgentResult.failed("Vorlage nicht ausfüllbar: " + ex.getMessage());
            }
            bodyText = generated.body();
            subject = isBlank(generated.subject()) ? replySubject(originalSubject) : generated.subject();
        }
        else {
            subject = replySubject(originalSubject);
            bodyText = buildAcknowledgment(context, mail);
        }

        UUID draftId = outboundMessageService.draft(new OutboundMessageService.DraftRequest(
                context.tenantId(), caseId, "EMAIL", subject, bodyText, null, null,
                List.of(recipient.strip()), List.of(), mailId,
                "mail-reply:" + mailId + ":" + source, context.actorUserId()));

        context.addProduced("mail_draft", draftId.toString(),
                "Antwort-Entwurf auf E-Mail " + mailId);
        return AgentResult.done(
                "Antwort-Entwurf angelegt (wird nicht versendet): „" + subject + "“",
                context.produced(),
                Map.of("draftId", draftId.toString(),
                        "templateId", templateId == null ? "" : templateId.toString(),
                        "mailId", mailId.toString()));
    }

    /** The template named in the inputs, otherwise the best published template for the category. */
    private UUID resolveTemplate(AgentContext context, Map<String, Object> mail) {
        String code = context.input("templateCode");
        String term = code;
        if (isBlank(term)) {
            MailCategoriser.Result classified = MailCategoriser.classify(
                    String.valueOf(mail.getOrDefault("subject", "")),
                    String.valueOf(mail.getOrDefault("body", "")),
                    mail.get("from_address") == null ? null : String.valueOf(mail.get("from_address")),
                    List.of(), false);
            term = CATEGORY_TO_TEMPLATE_TERM.getOrDefault(classified.category(), "Antwort");
        }
        try {
            Page<Map<String, Object>> candidates = templateService.list(context.tenantId(),
                    term == null ? "" : term.strip(), List.of("PUBLISHED"), null,
                    PageRequest.of(0, 10));
            for (Map<String, Object> row : candidates.getContent()) {
                if (isBlank(code) || code.strip().equalsIgnoreCase(String.valueOf(row.get("code")))) {
                    Object id = row.get("id");
                    return id instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(id));
                }
            }
        }
        catch (ApiException ex) {
            // No matching template is a normal state; the acknowledgment draft takes over.
        }
        return null;
    }

    /** The values the template fields may draw from: {@code field.*} inputs plus the mail itself. */
    private Map<String, String> fieldValues(AgentContext context, Map<String, Object> mail) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("Betreff", String.valueOf(mail.getOrDefault("subject", "")));
        values.put("Absender", mail.get("from_name") == null
                ? String.valueOf(mail.getOrDefault("from_address", ""))
                : String.valueOf(mail.get("from_name")));
        values.put("Inhalt", String.valueOf(mail.getOrDefault("body", "")));
        context.inputs().forEach((key, value) -> {
            if (key.startsWith("field.") && value != null) {
                values.put(key.substring("field.".length()), value);
            }
        });
        return values;
    }

    /** A rule-based acknowledgment for the case that no template exists or fits. */
    private String buildAcknowledgment(AgentContext context, Map<String, Object> mail) {
        String draftText = context.input("draftText");
        if (!isBlank(draftText)) {
            return draftText.strip();
        }
        String subject = String.valueOf(mail.getOrDefault("subject", ""));
        return """
                Sehr geehrte Damen und Herren,

                vielen Dank für Ihre Nachricht%s Wir haben sie erhalten und bearbeiten
                Ihr Anliegen. Sie erhalten von uns eine Antwort, sobald der Vorgang geprüft ist.

                Mit freundlichen Grüßen
                """
                .formatted(isBlank(subject) ? "." : " „" + subject.strip() + "“.");
    }

    private static String replySubject(String original) {
        String subject = original == null ? "" : original.strip();
        if (subject.matches("(?i)^(aw|re|antwort)\\s*:.*")) {
            return subject;
        }
        return "AW: " + (subject.isBlank() ? "Ihre Nachricht" : subject);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
