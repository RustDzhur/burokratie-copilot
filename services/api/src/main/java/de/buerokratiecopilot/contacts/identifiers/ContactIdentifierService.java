package de.buerokratiecopilot.contacts.identifiers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/** The identifiers of contacts: what a contact is recognised by. */
@Service
public class ContactIdentifierService {

    private static final Logger log = LoggerFactory.getLogger(ContactIdentifierService.class);

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ContactIdentifierService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId, UUID contactId) {
        requireContact(tenantId, contactId);
        return jdbc.queryForList("""
                select id, kind, value, normalized_value, created_at from contact_identifiers
                where tenant_id = ? and contact_id = ? order by kind, created_at
                """, tenantId, contactId);
    }

    /**
     * Adds an identifier to a contact. The same identifier on another contact is refused and the answer names that contact:
     * two entries for one party are a reason to merge them (a person decides), not to keep both.
     */
    @Transactional
    public Map<String, Object> add(UUID tenantId, UUID contactId, IdentifierKind kind, String raw, UUID userId) {
        requireContact(tenantId, contactId);
        if (kind == null || raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Art und Wert der Kennung sind erforderlich");
        }
        String normalized = IdentifierNormalizer.normalize(kind, raw);
        if (kind == IdentifierKind.IBAN && !IdentifierNormalizer.isValidIban(normalized)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die IBAN ist ungültig (Prüfziffern stimmen nicht)");
        }
        if (kind == IdentifierKind.VAT_ID && !IdentifierNormalizer.looksLikeVatId(normalized)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die USt-IdNr. hat kein gültiges Format");
        }
        if (normalized.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Wert der Kennung ist leer");
        }
        Optional<Map<String, Object>> existing = jdbc.queryForList("""
                select i.id, i.contact_id, c.display_name from contact_identifiers i
                join contacts c on c.id = i.contact_id
                where i.tenant_id = ? and i.kind = ? and i.normalized_value = ?
                """, tenantId, kind.name(), normalized).stream().findFirst();
        if (existing.isPresent()) {
            if (contactId.equals(existing.get().get("contact_id"))) {
                return jdbc.queryForMap("select id, kind, value, normalized_value, created_at from contact_identifiers where id = ?",
                        existing.get().get("id"));
            }
            throw ApiException.conflict("Diese Kennung gehört bereits zum Kontakt \"" + existing.get().get("display_name")
                    + "\" (" + existing.get().get("contact_id") + "). Gehören beide zusammen, führen Sie die Kontakte zusammen.");
        }
        UUID id = jdbc.queryForObject("""
                insert into contact_identifiers (tenant_id, contact_id, kind, value, normalized_value, created_by)
                values (?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, tenantId, contactId, kind.name(), raw.strip(), normalized, userId);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CASE, "CONTACT_IDENTIFIER_ADDED")
                .tenant(tenantId).entity("contacts", contactId, null)
                .description("Identifier " + kind + " added").build());
        return jdbc.queryForMap("select id, kind, value, normalized_value, created_at from contact_identifiers where id = ?", id);
    }

    @Transactional
    public void remove(UUID tenantId, UUID contactId, UUID identifierId) {
        int removed = jdbc.update("delete from contact_identifiers where id = ? and contact_id = ? and tenant_id = ?",
                identifierId, contactId, tenantId);
        if (removed == 0) {
            throw ApiException.notFound("Kennung");
        }
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CASE, "CONTACT_IDENTIFIER_REMOVED")
                .tenant(tenantId).entity("contacts", contactId, null).description("Identifier removed").build());
    }

    /** The contact an identifier belongs to, if any. */
    @Transactional(readOnly = true)
    public Optional<UUID> findContact(UUID tenantId, IdentifierKind kind, String raw) {
        String normalized = IdentifierNormalizer.normalize(kind, raw);
        return jdbc.queryForList("""
                select i.contact_id from contact_identifiers i join contacts c on c.id = i.contact_id
                where i.tenant_id = ? and i.kind = ? and i.normalized_value = ? and c.deleted_at is null
                """, UUID.class, tenantId, kind.name(), normalized).stream().findFirst();
    }

    /** What a new contact already carries (e-mail, phone) becomes identifiers; a clash is logged, not fatal. */
    @Transactional
    public void register(UUID tenantId, UUID contactId, String email, String phone, UUID userId) {
        tryAdd(tenantId, contactId, IdentifierKind.EMAIL, email, userId);
        tryAdd(tenantId, contactId, IdentifierKind.PHONE, phone, userId);
    }

    private void tryAdd(UUID tenantId, UUID contactId, IdentifierKind kind, String value, UUID userId) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            add(tenantId, contactId, kind, value, userId);
        }
        catch (ApiException ex) {
            log.info("Identifier {} of contact {} was not registered: {}", kind, contactId, ex.getMessage());
        }
    }

    private void requireContact(UUID tenantId, UUID contactId) {
        Integer found = jdbc.queryForObject(
                "select count(*) from contacts where id = ? and tenant_id = ? and deleted_at is null", Integer.class,
                contactId, tenantId);
        if (found == null || found == 0) {
            throw ApiException.notFound("Kontakt");
        }
    }
}
