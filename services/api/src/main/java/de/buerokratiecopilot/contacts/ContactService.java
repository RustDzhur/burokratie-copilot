package de.buerokratiecopilot.contacts;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * People and organisations the account deals with (spec §9, table {@code contacts}).
 *
 * <p>Every statement names the tenant explicitly. Row level security would also stop a query that forgot
 * it, but two independent guards are the point of having two, and a query that only works because the
 * second one rescues it is a bug waiting for the day the second guard is changed.</p>
 */
@Service
public class ContactService {

    /** What a caller may filter by: a contact is a person, an organisation, or an authority. */
    public enum Kind { PERSON, ORGANISATION, AUTHORITY }

    public record Draft(String name, Kind kind, String email, String phone) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ContactService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Page<Map<String, Object>> list(UUID tenantId, String query, List<Kind> kinds, Pageable pageable) {
        String term = query == null ? "" : query.strip();
        boolean person = kinds == null || kinds.isEmpty() || kinds.contains(Kind.PERSON);
        boolean organisation = kinds == null || kinds.isEmpty() || kinds.contains(Kind.ORGANISATION);
        boolean authority = kinds == null || kinds.isEmpty() || kinds.contains(Kind.AUTHORITY);

        // A contact is shown under exactly one kind: an authority first, then an organisation, else a person.
        String filter = """
                from contacts c
                where c.tenant_id = ? and c.deleted_at is null
                  and (? = '' or lower(c.display_name) like '%' || lower(?) || '%'
                       or lower(coalesce(c.email::text, '')) like '%' || lower(?) || '%')
                  and (case when c.is_authority then 'AUTHORITY'
                            when c.contact_type = 'ORGANIZATION' then 'ORGANISATION'
                            else 'PERSON' end) in (?, ?, ?)
                """;
        Object[] arguments = {tenantId, term, term, term,
                person ? "PERSON" : "-", organisation ? "ORGANISATION" : "-", authority ? "AUTHORITY" : "-"};

        Long total = jdbc.queryForObject("select count(*) " + filter, Long.class, arguments);
        Object[] paged = java.util.Arrays.copyOf(arguments, arguments.length + 2);
        paged[arguments.length] = pageable.getPageSize();
        paged[arguments.length + 1] = pageable.getOffset();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.display_name, c.email::text as email, c.phone, c.updated_at,
                       case when c.is_authority then 'AUTHORITY'
                            when c.contact_type = 'ORGANIZATION' then 'ORGANISATION'
                            else 'PERSON' end as kind,
                       (select a.city from contact_addresses a where a.contact_id = c.id
                         order by a.created_at limit 1) as city
                """ + filter + " order by c.display_name limit ? offset ?", paged);
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    @Transactional
    public Map<String, Object> create(UUID tenantId, Draft draft, UUID createdBy) {
        if (draft.name() == null || draft.name().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Kontakt braucht einen Namen");
        }
        Kind kind = draft.kind() == null ? Kind.PERSON : draft.kind();
        String email = draft.email() == null || draft.email().isBlank() ? null : draft.email().strip();
        String phone = draft.phone() == null || draft.phone().isBlank() ? null : draft.phone().strip();

        UUID id = jdbc.queryForObject("""
                insert into contacts (id, tenant_id, contact_type, display_name, email, phone, is_authority,
                                      created_by)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class, tenantId, kind == Kind.PERSON ? "PERSON" : "ORGANIZATION",
                draft.name().strip(), email, phone, kind == Kind.AUTHORITY, createdBy);

        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.CASE, "CONTACT_CREATED")
                .tenant(tenantId)
                .entity("contacts", id, draft.name().strip())
                .description("Contact created")
                .build());

        return jdbc.queryForMap("""
                select c.id, c.display_name, c.email::text as email, c.phone, c.updated_at, ? as kind, null as city
                from contacts c where c.id = ? and c.tenant_id = ?
                """, kind.name(), id, tenantId);
    }
}
