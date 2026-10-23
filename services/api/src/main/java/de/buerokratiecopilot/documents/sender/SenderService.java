package de.buerokratiecopilot.documents.sender;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import de.buerokratiecopilot.documents.domain.Document;

/**
 * Fills in who sent a document: reads the sender from the letter ({@link SenderRecognizer}), and finds the matching contact or creates
 * one, so that the sender can be found again later and the next letter of the same sender is linked to the same contact.
 *
 * <p>What a person typed or confirmed is never overwritten. The name of the person who uploaded the document, and the names of the
 * cabinet's own people, are not taken for a sender: a letter that starts with the recipient's window must not turn the recipient into
 * the sender.</p>
 */
@Service
public class SenderService {

    private static final Logger log = LoggerFactory.getLogger(SenderService.class);

    private final JdbcTemplate jdbc;

    public SenderService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Reads the sender of the document from its text; returns whether the document changed. */
    public boolean apply(Document document) {
        if ("PERSON".equals(document.getSenderSource())) {
            return false;
        }
        boolean fromMail = document.getSender() != null && !document.getSender().isBlank()
                && !"LETTER".equals(document.getSenderSource());
        if (fromMail) {
            // A sender taken from a mail stays as it is; the letter below it is not trusted over the mail's own header.
            if (document.getSenderSource() == null) {
                document.setSenderSource("MAIL");
            }
            return false;
        }
        var recognised = SenderRecognizer.recognise(document.getExtractedText());
        if (recognised.isEmpty()) {
            return false;
        }
        SenderRecognizer.Sender sender = recognised.get();
        if (isOwnName(document, sender.name())) {
            return false;
        }
        try {
            UUID contactId = contactFor(document, sender);
            Map<String, Object> details = new LinkedHashMap<>();
            put(details, "kind", sender.kind().name());
            put(details, "address", sender.addressLine().isEmpty() ? null : sender.addressLine());
            put(details, "street", sender.street());
            put(details, "postalCode", sender.postalCode());
            put(details, "city", sender.city());
            put(details, "phone", sender.phone());
            put(details, "email", sender.email());
            put(details, "website", sender.website());
            put(details, "vatId", sender.vatId());
            put(details, "iban", sender.iban());
            document.setSender(sender.name());
            document.setSenderDetails(details);
            document.setSenderContactId(contactId);
            document.setSenderSource("LETTER");
            return true;
        }
        catch (RuntimeException ex) {
            // The sender is a convenience; a failure here never stops the document.
            log.warn("The sender of document {} could not be saved: {}", document.getId(), ex.getMessage());
            return false;
        }
    }

    private boolean isOwnName(Document document, String name) {
        String normal = normalise(name);
        Integer own = jdbc.queryForObject("""
                select (select count(*) from users u where u.id = ? and lower(u.display_name) = ?)
                     + (select count(*) from contacts c where c.tenant_id = ? and c.deleted_at is null and lower(c.display_name) = ?)
                     + (select count(*) from tenants t where t.id = ? and lower(t.name) = ?)
                """, Integer.class, document.getCreatedBy(), normal, document.getTenantId(), normal, document.getTenantId(), normal);
        return own != null && own > 0;
    }

    /** The contact this sender is: matched by VAT id, e-mail address or name and place; created when there is none. */
    private UUID contactFor(Document document, SenderRecognizer.Sender sender) {
        UUID tenant = document.getTenantId();
        List<UUID> found = List.of();
        if (sender.vatId() != null) {
            found = jdbc.queryForList("select id from contacts where tenant_id = ? and deleted_at is null and replace(upper(vat_id), ' ', '') = ?",
                    UUID.class, tenant, sender.vatId());
        }
        if (found.isEmpty() && sender.email() != null) {
            found = jdbc.queryForList("select id from contacts where tenant_id = ? and deleted_at is null and email = ?",
                    UUID.class, tenant, sender.email());
        }
        if (found.isEmpty()) {
            found = jdbc.queryForList("""
                    select c.id from contacts c
                    where c.tenant_id = ? and c.deleted_at is null
                      and (lower(c.display_name) = ? or lower(coalesce(c.organization_name, '')) = ?)
                      and (?::text is null or not exists (select 1 from contact_addresses a where a.contact_id = c.id and a.postal_code is not null)
                           or exists (select 1 from contact_addresses a where a.contact_id = c.id and a.postal_code = ?))
                    limit 1
                    """, UUID.class, tenant, normalise(sender.name()), normalise(sender.name()), sender.postalCode(), sender.postalCode());
        }
        if (!found.isEmpty()) {
            UUID id = found.getFirst();
            // Only gaps are filled; what is stored stays.
            jdbc.update("""
                    update contacts set phone = coalesce(phone, ?), email = coalesce(email, ?::citext), website = coalesce(website, ?),
                                        vat_id = coalesce(vat_id, ?) where id = ?
                    """, sender.phone(), sender.email(), sender.website(), sender.vatId(), id);
            return id;
        }
        UUID id = UUID.randomUUID();
        boolean person = sender.kind() == SenderRecognizer.Kind.PERSON;
        String[] parts = person ? sender.name().split("\\s+") : new String[0];
        jdbc.update("""
                insert into contacts (id, tenant_id, contact_type, display_name, first_name, last_name, organization_name, vat_id,
                                      email, phone, website, is_authority, created_by, metadata)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::citext, ?, ?, ?, ?, jsonb_build_object('createdFrom', 'document', 'documentId', ?::text))
                """, id, tenant, person ? "PERSON" : "ORGANIZATION", sender.name(),
                person && parts.length > 1 ? String.join(" ", java.util.Arrays.copyOf(parts, parts.length - 1)) : null,
                person && parts.length > 1 ? parts[parts.length - 1] : null,
                person ? null : sender.name(), sender.vatId(), sender.email(), sender.phone(), sender.website(),
                sender.kind() == SenderRecognizer.Kind.AUTHORITY, document.getCreatedBy(), document.getId().toString());
        if (sender.street() != null || sender.postalCode() != null) {
            jdbc.update("""
                    insert into contact_addresses (contact_id, tenant_id, address_type, street, postal_code, city, is_primary)
                    values (?, ?, 'MAIN', ?, ?, ?, true)
                    """, id, tenant, sender.street(), sender.postalCode(), sender.city());
        }
        return id;
    }

    private static void put(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String normalise(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
    }
}
