package de.buerokratiecopilot.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.contacts.identifiers.ContactIdentifierService;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.util.JsonColumns;

/**
 * The profile of a contact (task BM-UI-01): who they are, what they are recognised by, and everything the memory holds about
 * them - documents, invoices, cases, open findings. Each part shows only to a reader who may see that kind of
 * thing, and the cases only those the reader may open.
 */
@Service
public class ContactProfileService {

    private final JdbcTemplate jdbc;
    private final ContactIdentifierService identifiers;

    public ContactProfileService(JdbcTemplate jdbc, ContactIdentifierService identifiers) {
        this.jdbc = jdbc;
        this.identifiers = identifiers;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> profile(UUID tenantId, UUID userId, UUID contactId) {
        List<Map<String, Object>> contact = jdbc.queryForList("""
                select id, display_name, organization_name, contact_type, email::text as email, phone, vat_id, tax_number,
                       is_customer, is_supplier, created_at
                from contacts where id = ? and tenant_id = ? and deleted_at is null
                """, contactId, tenantId);
        if (contact.isEmpty()) {
            throw ApiException.notFound("contact");
        }
        Set<String> held = ReaderRights.held();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("contact", contact.get(0));
        profile.put("identifiers", identifiers.list(tenantId, contactId));

        profile.put("documents", held.contains("documents.view") ? JsonColumns.parse(jdbc.queryForList("""
                select m.id as relationship_id, m.status, m.score, m.evidence, d.id as document_id, d.original_filename as name,
                       d.processing_status, d.received_at
                from document_matches m join documents d on d.id = m.document_id
                where m.tenant_id = ? and m.match_type = 'CONTACT' and m.target_id = ? and m.status <> 'REJECTED' and d.deleted_at is null
                order by d.received_at desc nulls last limit 50
                """, tenantId, contactId)) : List.of());

        profile.put("invoices", held.contains("finance.view") ? jdbc.queryForList("""
                select id, invoice_number, direction, gross_amount, currency, status, payment_status, issue_date, due_date
                from invoices where tenant_id = ? and contact_id = ? and deleted_at is null order by coalesce(issue_date, created_at::date) desc limit 50
                """, tenantId, contactId) : List.of());

        boolean all = ReaderRights.seesEveryCase();
        profile.put("cases", held.contains("cases.view") ? jdbc.queryForList("""
                select c.id, c.reference, c.title, c.status from cases c
                where c.tenant_id = ? and c.counterparty_contact_id = ? and c.deleted_at is null
                  and (? or c.owner_user_id = ? or c.responsible_user_id = ?
                       or exists (select 1 from case_participants p where p.case_id = c.id and p.user_id = ? and p.revoked_at is null
                                  and (p.expires_at is null or p.expires_at > now())))
                order by c.opened_at desc limit 50
                """, tenantId, contactId, all, userId, userId, userId) : List.of());

        profile.put("openFindings", held.contains("radar.view") ? jdbc.queryForList("""
                select id, title, severity, status from radar_findings
                where tenant_id = ? and related_contact_id = ? and status not in ('RESOLVED', 'DISMISSED')
                order by case severity when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end limit 20
                """, tenantId, contactId) : List.of());
        return profile;
    }
}
