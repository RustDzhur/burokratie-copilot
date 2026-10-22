package de.buerokratiecopilot.parties;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The people (or the organisation) a cabinet belongs to. A person has one, a household one per member, a company its
 * legal entity. The first one is created from what the account already says, so nobody starts with an empty page.
 */
@Service
public class PartyService {

    public record Party(UUID id, String kind, String displayName, String relation, LocalDate birthDate,
                        String aliases, String address, String identifiers, UUID userId, boolean primary,
                        String notes, long documentCount) {
    }

    public record PartyInput(String displayName, String relation, LocalDate birthDate, String aliases,
                             String address, String identifiers, String notes) {
    }

    private static final java.util.Set<String> PERSON_RELATIONS = java.util.Set.of("SELF", "PARTNER", "CHILD",
            "PARENT", "RELATIVE", "OTHER");
    private static final java.util.Set<String> ORGANIZATION_RELATIONS = java.util.Set.of("COMPANY", "BRANCH");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final AddresseeResolver resolver;

    public PartyService(JdbcTemplate jdbc, AuditService audit, AddresseeResolver resolver) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.resolver = resolver;
    }

    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
    }

    /** All parties of the cabinet; creates the first one when there is none. */
    @Transactional
    public List<Party> list() {
        UUID tenantId = tenant();
        Integer count = jdbc.queryForObject("select count(*) from parties where tenant_id = ? and deleted_at is null",
                Integer.class, tenantId);
        if (count != null && count == 0) {
            createFirst(tenantId);
        }
        return jdbc.query("""
                select p.*, (select count(*) from documents d where d.party_id = p.id and d.deleted_at is null) as docs
                from parties p where p.tenant_id = ? and p.deleted_at is null
                order by p.is_primary desc, p.created_at
                """, (rs, i) -> new Party((UUID) rs.getObject("id"), rs.getString("kind"),
                rs.getString("display_name"), rs.getString("relation"),
                rs.getObject("birth_date", LocalDate.class), rs.getString("aliases"), rs.getString("address"),
                rs.getString("identifiers"), (UUID) rs.getObject("user_id"), rs.getBoolean("is_primary"),
                rs.getString("notes"), rs.getLong("docs")), tenantId);
    }

    private void createFirst(UUID tenantId) {
        Map<String, Object> tenant = jdbc.queryForMap("select name, account_type from tenants where id = ?", tenantId);
        boolean company = String.valueOf(tenant.get("account_type")).startsWith("COMPANY")
                || "HIGH_SECURITY_ORGANIZATION".equals(tenant.get("account_type"));
        UUID userId = RequestContext.userId().orElse(null);
        if (company) {
            jdbc.update("""
                    insert into parties (tenant_id, kind, display_name, relation, is_primary)
                    values (?, 'ORGANIZATION', ?, 'COMPANY', true)
                    """, tenantId, String.valueOf(tenant.get("name")));
        }
        else {
            String name = userId == null ? String.valueOf(tenant.get("name"))
                    : jdbc.queryForObject("select display_name from users where id = ?", String.class, userId);
            jdbc.update("""
                    insert into parties (tenant_id, kind, display_name, relation, user_id, is_primary)
                    values (?, 'PERSON', ?, 'SELF', ?, true)
                    """, tenantId, name, userId);
        }
    }

    @Transactional
    public UUID create(PartyInput input) {
        UUID tenantId = tenant();
        String name = requireName(input);
        String kind = isOrganization(tenantId) ? "ORGANIZATION" : "PERSON";
        String relation = relationFor(kind, input.relation());
        UUID id = jdbc.queryForObject("""
                insert into parties (tenant_id, kind, display_name, relation, birth_date, aliases, address, identifiers,
                                     notes)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, tenantId, kind, name, relation, input.birthDate(), clean(input.aliases()),
                clean(input.address()), clean(input.identifiers()), input.notes());
        record("PARTY_CREATED", id, name);
        resolver.resolveUnassigned(tenantId);
        return id;
    }

    @Transactional
    public void update(UUID id, PartyInput input) {
        UUID tenantId = tenant();
        String name = requireName(input);
        String kind = jdbc.query("select kind from parties where id = ? and tenant_id = ? and deleted_at is null",
                (rs, i) -> rs.getString(1), id, tenantId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Party"));
        jdbc.update("""
                update parties set display_name = ?, relation = ?, birth_date = ?, aliases = ?, address = ?,
                                   identifiers = ?, notes = ?
                where id = ? and tenant_id = ?
                """, name, relationFor(kind, input.relation()), input.birthDate(), clean(input.aliases()),
                clean(input.address()), clean(input.identifiers()), input.notes(), id, tenantId);
        record("PARTY_UPDATED", id, name);
        resolver.resolveUnassigned(tenantId);
    }

    /** Removes a party; its documents stay and simply have no addressee any more. The first party cannot go. */
    @Transactional
    public void delete(UUID id) {
        UUID tenantId = tenant();
        Map<String, Object> row = jdbc.queryForList(
                "select display_name, is_primary from parties where id = ? and tenant_id = ? and deleted_at is null",
                id, tenantId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Party"));
        if (Boolean.TRUE.equals(row.get("is_primary"))) {
            throw new ApiException(ErrorCode.CONFLICT, "The main party of the cabinet cannot be removed");
        }
        jdbc.update("update documents set party_id = null where party_id = ? and tenant_id = ?", id, tenantId);
        jdbc.update("update parties set deleted_at = now() where id = ?", id);
        record("PARTY_DELETED", id, String.valueOf(row.get("display_name")));
    }

    /** A person's decision about whom a document is addressed to (or null to clear it). */
    @Transactional

    private boolean isOrganization(UUID tenantId) {
        String type = jdbc.queryForObject("select account_type from tenants where id = ?", String.class, tenantId);
        return type != null && (type.startsWith("COMPANY") || "HIGH_SECURITY_ORGANIZATION".equals(type));
    }

    private static String requireName(PartyInput input) {
        if (input == null || input.displayName() == null || input.displayName().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A name is required");
        }
        return input.displayName().strip();
    }

    private static String relationFor(String kind, String relation) {
        String value = relation == null || relation.isBlank() ? null : relation.strip().toUpperCase(java.util.Locale.ROOT);
        if ("ORGANIZATION".equals(kind)) {
            return value != null && ORGANIZATION_RELATIONS.contains(value) ? value : "COMPANY";
        }
        return value != null && PERSON_RELATIONS.contains(value) ? value : "OTHER";
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private void record(String action, UUID id, String what) {
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.DOCUMENT, action)
                .tenant(RequestContext.tenantId().orElse(null))
                .entity("parties", id, what)
                .description(action + ": " + what)
                .build());
    }
}
