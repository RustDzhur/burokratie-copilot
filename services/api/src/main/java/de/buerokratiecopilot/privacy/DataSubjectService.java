package de.buerokratiecopilot.privacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Requests of a data subject about a contact (HD-GDPR-01): find what is held, hand out a copy, correct, delete within limits.
 *
 * <p>"Within limits" is meant literally. Erasing a contact removes the person from the contact record and from its
 * identifiers, but the business records that mention the contact (invoices, cases, documents, correspondence)
 * are not deleted by this: whether and how long they must be kept is the controller's decision, and the platform does not
 * invent a period. They are listed in the answer under {@code retained}, each with its count, so the person who handles the
 * request can decide on them one by one. A document under legal hold stops the erasure altogether.</p>
 *
 * <p>The record of a request holds counts and field names, never a second copy of the person's data.</p>
 */
@Service
public class DataSubjectService {

    /** The fields of a contact that a correction may change. */
    public static final Set<String> CORRECTABLE = Set.of("display_name", "first_name", "last_name", "organization_name", "email",
            "phone", "mobile", "website", "vat_id", "tax_number", "preferred_language", "notes");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public DataSubjectService(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    private void requireContact(UUID tenantId, UUID contactId) {
        Integer found = jdbc.queryForObject("select count(*) from contacts where id = ? and tenant_id = ? and deleted_at is null",
                Integer.class, contactId, tenantId);
        if (found == null || found == 0) {
            throw ApiException.notFound("Contact");
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> search(UUID tenantId, String query) {
        if (query == null || query.strip().length() < 2) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Suchbegriff muss mindestens zwei Zeichen haben");
        }
        String like = "%" + query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.queryForList("""
                select c.id, c.display_name, c.contact_type, c.email::text as email
                from contacts c where c.tenant_id = ? and c.deleted_at is null
                  and (c.display_name ilike ? or c.email::text ilike ? or c.organization_name ilike ?
                       or exists (select 1 from contact_identifiers i where i.contact_id = c.id and i.value ilike ?))
                order by c.display_name limit 25
                """, tenantId, like, like, like, like);
    }

    /** What is held about a contact, by kind, as counts; the data itself is in {@link #export}. */
    @Transactional
    public Map<String, Object> overview(UUID tenantId, UUID userId, UUID contactId) {
        requireContact(tenantId, contactId);
        Map<String, Object> counts = counts(tenantId, contactId);
        record(tenantId, userId, contactId, "ACCESS", null, counts);
        return counts;
    }

    private Map<String, Object> counts(UUID tenantId, UUID contactId) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("identifiers", count("select count(*) from contact_identifiers where tenant_id = ? and contact_id = ?", tenantId, contactId));
        counts.put("documents", count("""
                select count(*) from document_matches m join documents d on d.id = m.document_id
                where m.tenant_id = ? and m.match_type = 'CONTACT' and m.target_id = ? and m.status <> 'REJECTED' and d.deleted_at is null
                """, tenantId, contactId));
        counts.put("documentsUnderLegalHold", count("""
                select count(*) from document_matches m join documents d on d.id = m.document_id
                where m.tenant_id = ? and m.match_type = 'CONTACT' and m.target_id = ? and m.status <> 'REJECTED'
                  and d.deleted_at is null and d.legal_hold
                """, tenantId, contactId));
        counts.put("invoices", count("select count(*) from invoices where tenant_id = ? and contact_id = ? and deleted_at is null", tenantId, contactId));
        counts.put("cases", count("select count(*) from cases where tenant_id = ? and counterparty_contact_id = ? and deleted_at is null", tenantId, contactId));
        counts.put("mailMessages", count("""
                select count(*) from mail_messages m where m.tenant_id = ? and exists (
                    select 1 from contacts c where c.id = ? and c.email is not null
                      and (m.from_address = c.email or m.to_addresses @> to_jsonb(c.email::text)))
                """, tenantId, contactId));
        return counts;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    /** The copy handed to the person: the contact record and the records that refer to it. */
    @Transactional
    public Map<String, Object> export(UUID tenantId, UUID userId, UUID contactId) {
        requireContact(tenantId, contactId);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("contact", jdbc.queryForMap("""
                select id, contact_type, display_name, first_name, last_name, organization_name, legal_form, vat_id, tax_number,
                       email::text as email, phone, mobile, fax, website, preferred_language, notes, created_at, updated_at
                from contacts where id = ? and tenant_id = ?
                """, contactId, tenantId));
        bundle.put("identifiers", jdbc.queryForList("select kind, value, created_at from contact_identifiers where tenant_id = ? and contact_id = ? order by kind",
                tenantId, contactId));
        bundle.put("documents", jdbc.queryForList("""
                select d.id, d.original_filename, d.received_at, d.document_date from document_matches m join documents d on d.id = m.document_id
                where m.tenant_id = ? and m.match_type = 'CONTACT' and m.target_id = ? and m.status <> 'REJECTED' and d.deleted_at is null
                order by d.received_at
                """, tenantId, contactId));
        bundle.put("invoices", jdbc.queryForList("""
                select id, invoice_number, direction, issue_date, due_date, gross_amount, currency, payment_status
                from invoices where tenant_id = ? and contact_id = ? and deleted_at is null order by issue_date
                """, tenantId, contactId));
        bundle.put("cases", jdbc.queryForList("select id, reference, title, status, opened_at from cases where tenant_id = ? and counterparty_contact_id = ? and deleted_at is null order by opened_at",
                tenantId, contactId));
        bundle.put("exportedAt", java.time.Instant.now().toString());
        Map<String, Object> counts = counts(tenantId, contactId);
        record(tenantId, userId, contactId, "EXPORT", null, counts);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.EXPORT, "DATA_SUBJECT_EXPORT")
                .entity("Contact", contactId, null).description("Auskunftskopie zu einem Kontakt erstellt").build());
        return bundle;
    }

    @Transactional
    public Map<String, Object> rectify(UUID tenantId, UUID userId, UUID contactId, Map<String, String> fields, String reason) {
        requireContact(tenantId, contactId);
        if (fields == null || fields.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Es wurde kein Feld zur Berichtigung angegeben");
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!CORRECTABLE.contains(entry.getKey())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Dieses Feld kann nicht berichtigt werden: " + entry.getKey());
            }
            if ("display_name".equals(entry.getKey()) && (entry.getValue() == null || entry.getValue().isBlank())) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Anzeigename darf nicht leer sein");
            }
            // The column name comes from the closed set above, never from the caller.
            jdbc.update("update contacts set " + entry.getKey() + " = ?, updated_at = now() where id = ? and tenant_id = ?",
                    entry.getValue() == null || entry.getValue().isBlank() ? null : entry.getValue(), contactId, tenantId);
            changed.add(entry.getKey());
        }
        Map<String, Object> result = Map.of("changedFields", changed);
        record(tenantId, userId, contactId, "RECTIFICATION", reason, result);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.SETTINGS, "DATA_SUBJECT_RECTIFICATION")
                .entity("Contact", contactId, null).description("Kontaktdaten berichtigt: " + String.join(", ", changed)).build());
        return result;
    }

    @Transactional
    public Map<String, Object> erase(UUID tenantId, UUID userId, UUID contactId, String reason) {
        requireContact(tenantId, contactId);
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ohne Begründung wird nichts gelöscht");
        }
        Map<String, Object> counts = counts(tenantId, contactId);
        if (((Number) counts.get("documentsUnderLegalHold")).intValue() > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Mindestens ein verknüpftes Dokument steht unter Legal Hold; der Kontakt wird nicht gelöscht");
        }
        jdbc.update("delete from contact_identifiers where tenant_id = ? and contact_id = ?", tenantId, contactId);
        jdbc.update("""
                update contacts set display_name = 'Gelöschte Person', first_name = null, last_name = null, organization_name = null,
                       legal_form = null, vat_id = null, tax_number = null, email = null, phone = null, mobile = null, fax = null,
                       website = null, notes = null, metadata = '{}'::jsonb, deleted_at = now(), updated_at = now()
                where id = ? and tenant_id = ?
                """, contactId, tenantId);
        Map<String, Object> retained = new LinkedHashMap<>();
        for (String kind : List.of("documents", "invoices", "cases", "mailMessages")) {
            int n = ((Number) counts.get(kind)).intValue();
            if (n > 0) {
                retained.put(kind, n);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("erased", List.of("Stammdaten", "Kennungen"));
        result.put("retained", retained);
        result.put("retainedReason", "Geschäftsunterlagen werden hierdurch nicht gelöscht; ob und wie lange sie aufzubewahren sind, entscheidet der Verantwortliche");
        record(tenantId, userId, contactId, "ERASURE", reason, result);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.DELETION, "DATA_SUBJECT_ERASURE")
                .entity("Contact", contactId, null).description("Kontakt anonymisiert; Geschäftsunterlagen bleiben bestehen").build());
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> requests(UUID tenantId, UUID contactId) {
        return jdbc.queryForList("""
                select id, kind, reason, handled_by, result::text as result, created_at
                from data_subject_requests where tenant_id = ? and contact_id = ? order by created_at desc
                """, tenantId, contactId);
    }

    private void record(UUID tenantId, UUID userId, UUID contactId, String kind, String reason, Map<String, Object> result) {
        try {
            jdbc.update("insert into data_subject_requests (tenant_id, contact_id, kind, reason, handled_by, result) values (?, ?, ?, ?, ?, ?::jsonb)",
                    tenantId, contactId, kind, reason, userId, json.writeValueAsString(result));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise a request result", ex);
        }
    }
}
