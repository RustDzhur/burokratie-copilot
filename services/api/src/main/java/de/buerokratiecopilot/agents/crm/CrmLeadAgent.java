package de.buerokratiecopilot.agents.crm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.contacts.ContactService;

/**
 * {@code crm-lead} — Lead-Erfassung.
 *
 * <p>Turns an inquiry (free text or a form submission) into a contact that carries the lead
 * marker and the source it came from. The contacts module stays the single home of people
 * data: a lead is a contact in the NEW stage, nothing more. Idempotent on the e-mail address
 * and on the external key of the submission, so a re-delivered webhook cannot create doubles.</p>
 */
@Component
public class CrmLeadAgent implements Agent {

    private final ContactService contacts;
    private final CrmStore store;

    public CrmLeadAgent(ContactService contacts, CrmStore store) {
        this.contacts = contacts;
        this.store = store;
    }

    @Override
    public String id() {
        return "crm-lead";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID tenantId = context.tenantId();
        UUID actor = context.actorUserId();
        String name = clean(context.input("name"));
        if (name == null) {
            return AgentResult.failed("Lead-Erfassung: ein Name ist erforderlich (Input 'name').");
        }
        String email = clean(context.input("email"));
        String phone = clean(context.input("phone"));
        String source = context.input("source") == null || context.input("source").isBlank()
                ? "manual" : context.input("source").strip();
        String externalKey = clean(context.input("externalKey"));
        String note = clean(context.input("note"));

        // Idempotenz: derselbe externe Schlüssel oder dieselbe E-Mail darf keinen zweiten
        // Kontakt erzeugen.
        Optional<UUID> byKey = externalKey == null ? Optional.empty()
                : store.findContactByExternalKey(tenantId, externalKey);
        if (byKey.isPresent()) {
            context.addProduced("contact", byKey.get().toString(), "Bestehender Lead");
            return AgentResult.done("Lead existiert bereits (externer Schlüssel).", context.produced(),
                    Map.of("contactId", byKey.get().toString(), "existing", "true",
                            "matchedBy", "externalKey", "source", source));
        }
        if (email != null) {
            Optional<UUID> byEmail = store.findContactByEmail(tenantId, email);
            if (byEmail.isPresent()) {
                context.addProduced("contact", byEmail.get().toString(),
                        "Bestehender Kontakt zu " + email);
                return AgentResult.done("Lead existiert bereits zur E-Mail " + email + ".",
                        context.produced(), Map.of("contactId", byEmail.get().toString(),
                                "existing", "true", "matchedBy", "email", "source", source));
            }
        }

        try {
            Map<String, Object> created = contacts.create(tenantId,
                    new ContactService.Draft(name, parseKind(context.input("kind")), email, phone),
                    actor);
            UUID contactId = (UUID) created.get("id");

            Map<String, Object> lead = new LinkedHashMap<>();
            lead.put("stage", "NEW");
            lead.put("source", source);
            lead.put("externalKey", externalKey);
            if (note != null) {
                lead.put("note", note);
            }
            lead.put("createdAt", context.now().toString());
            lead.put("agent", "crm-lead");
            store.mergeContactMetadata(tenantId, contactId, Map.of("lead", lead));

            context.addProduced("contact", contactId.toString(), "Lead: " + name);
            context.addProduced("lead", "source:" + source, "Quelle: " + source);
            return AgentResult.done("Lead erfasst: " + name + " (Quelle: " + source + ").",
                    context.produced(), Map.of("contactId", contactId.toString(),
                            "created", "true", "source", source, "stage", "NEW"));
        }
        catch (RuntimeException e) {
            return AgentResult.failed("Lead konnte nicht angelegt werden: " + e.getMessage());
        }
    }

    private static ContactService.Kind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return ContactService.Kind.PERSON;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "ORGANISATION", "ORGANIZATION" -> ContactService.Kind.ORGANISATION;
            case "AUTHORITY", "BEHÖRDE", "BEHOERDE" -> ContactService.Kind.AUTHORITY;
            default -> ContactService.Kind.PERSON;
        };
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
