package de.buerokratiecopilot.documents.taxonomy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The interface a customer's administrator uses to change the taxonomy (spec §6, B-008b).
 *
 * <p>The taxonomy was always data — {@code document_types} and {@code document_type_rules} — but the only
 * way to change it was SQL or a migration, which means the person who owns the classification had to ask
 * somebody who owns the database. This is the missing way in.</p>
 *
 * <p><strong>A document type is not a tenant's to change.</strong> {@code document_types} has no tenant
 * column, and that is not an oversight: a type is a shared vocabulary, and if every tenant could rename
 * "Eingangsrechnung" the word would stop meaning anything across the platform. Types are therefore
 * administered centrally and only the rules are per tenant. Saying so here matters, because the opposite
 * would have been the easy guess.</p>
 *
 * <p><strong>A rule that has not been verified does not run.</strong> A keyword rule that matches too
 * broadly does not fail; it quietly files documents under the wrong type, and the mistake surfaces much
 * later as a stack of mis-filed papers. A regular expression is therefore created <em>inactive</em> and
 * takes effect when a second person has looked at it — which is what {@code requires_verification} means
 * for the thresholds of the taxonomy.</p>
 *
 * <p><strong>Every change is recorded.</strong> Who changed which rule, and to what. Classification decides
 * where a document ends up, and a change to it with no record is a change nobody can find when the papers
 * are in the wrong place.</p>
 */
@Service
public class TaxonomyAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(TaxonomyAdminService.class);

    /** The categories the schema allows. */
    public static final List<String> CATEGORIES = List.of("FINANCE", "CONTRACTS", "GOVERNMENT", "HOUSING",
            "EMPLOYMENT", "FAMILY_EDUCATION_HEALTH", "LEGAL", "OTHER");

    /** The rule types the schema allows. */
    public static final List<String> RULE_TYPES = List.of("KEYWORD", "REGEX", "SENDER_DOMAIN", "FILENAME");

    /**
     * The types whose pattern can be wrong in a way that is invisible.
     *
     * <p>A regular expression that matches too much does not report an error; it files things in the wrong
     * place. Those are the ones that wait for a second pair of eyes.</p>
     */
    private static final List<String> NEEDS_VERIFICATION = List.of("REGEX");

    private final JdbcTemplate jdbc;

    public TaxonomyAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A document type. */
    public record DocumentType(UUID id, String code, String category, boolean active,
                               boolean requiresReview, int sortOrder) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id.toString());
            map.put("code", code);
            map.put("category", category);
            map.put("isActive", active);
            map.put("requiresReview", requiresReview);
            map.put("sortOrder", sortOrder);
            return map;
        }
    }

    /** A classification rule. */
    public record Rule(UUID id, UUID documentTypeId, UUID tenantId, String ruleType, String pattern,
                       double weight, boolean requiresAll, boolean active, boolean verified) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id.toString());
            map.put("documentTypeId", documentTypeId.toString());
            map.put("tenantId", tenantId == null ? null : tenantId.toString());
            map.put("ruleType", ruleType);
            map.put("pattern", pattern);
            map.put("weight", weight);
            map.put("requiresAll", requiresAll);
            map.put("active", active);
            map.put("verified", verified);
            map.put("note", verified ? null : "Diese Regel greift erst nach der Prüfung durch eine zweite "
                    + "Person.");
            return map;
        }
    }

    /** The types of the platform, which are shared and not a tenant's to rename. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTypes(boolean onlyActive) {
        return jdbc.queryForList("""
                select id, code, category, name ->> 'de' as name_de, is_active, requires_review, sort_order,
                       parent_id
                from document_types
                where (?::boolean = false or is_active)
                order by sort_order, code
                """, onlyActive);
    }

    /**
     * Creates a document type.
     *
     * <p>Platform-wide, so this is an administrative act rather than a tenant's setting.</p>
     */
    @Transactional
    public DocumentType createType(String code, String category, String nameDe, String descriptionDe,
                                   UUID parentId, boolean requiresReview, int sortOrder, UUID actor) {
        String normalized = requireCode(code);
        String categoryValue = requireCategory(category);

        Integer existing = jdbc.queryForObject("select count(*) from document_types where code = ?",
                Integer.class, normalized);
        if (existing != null && existing > 0) {
            throw ApiException.duplicate("Diesen Dokumenttyp gibt es bereits: " + normalized);
        }
        if (parentId != null) {
            requireType(parentId);
        }

        UUID id = jdbc.queryForObject("""
                insert into document_types (id, code, category, name, description, parent_id, is_active,
                                            requires_review, sort_order)
                values (gen_random_uuid(), ?, ?, ?::jsonb, ?::jsonb, ?, true, ?, ?)
                returning id
                """, UUID.class, normalized, categoryValue, json(nameDe == null ? normalized : nameDe),
                json(descriptionDe), parentId, requiresReview, sortOrder);

        audit(tenantOf(actor), actor, "DOCUMENT_TYPE_CREATED", id,
                "{\"code\":\"" + normalized + "\",\"category\":\"" + categoryValue + "\"}");
        LOG.info("Document type {} ({}) created by {}", normalized, categoryValue, actor);
        return new DocumentType(id, normalized, categoryValue, true, requiresReview, sortOrder);
    }

    /** Changes what may be changed about a type. The code is not among it: documents refer to it. */
    @Transactional
    public DocumentType updateType(UUID typeId, Boolean active, Boolean requiresReview, Integer sortOrder,
                                   String nameDe, String descriptionDe, UUID actor) {
        Map<String, Object> before = requireType(typeId);

        jdbc.update("""
                update document_types set
                    is_active = coalesce(?, is_active),
                    requires_review = coalesce(?, requires_review),
                    sort_order = coalesce(?, sort_order),
                    name = coalesce(?::jsonb, name),
                    description = coalesce(?::jsonb, description),
                    updated_at = now()
                where id = ?
                """, active, requiresReview, sortOrder, nameDe == null ? null : json(nameDe),
                descriptionDe == null ? null : json(descriptionDe), typeId);

        audit(tenantOf(actor), actor, "DOCUMENT_TYPE_UPDATED", typeId,
                "{\"active\":" + active + ",\"requiresReview\":" + requiresReview + "}");
        Map<String, Object> after = requireType(typeId);
        LOG.info("Document type {} changed from {} to {} by {}", before.get("code"), before.get("is_active"),
                after.get("is_active"), actor);
        return new DocumentType(typeId, String.valueOf(after.get("code")),
                String.valueOf(after.get("category")), Boolean.TRUE.equals(after.get("is_active")),
                Boolean.TRUE.equals(after.get("requires_review")),
                ((Number) after.get("sort_order")).intValue());
    }

    /**
     * The rules that apply to a type for a tenant.
     *
     * <p>Both the tenant's own rules and the platform-wide ones are returned, because both decide where a
     * document ends up.</p>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> rulesOf(UUID typeId, UUID tenantId) {
        requireType(typeId);
        return jdbc.queryForList("""
                select id, tenant_id, rule_type, pattern, weight, requires_all, is_active, created_by,
                       created_at
                from document_type_rules
                where document_type_id = ? and (tenant_id = ? or tenant_id is null)
                order by rule_type, created_at
                """, typeId, tenantId);
    }

    /**
     * Adds a classification rule.
     *
     * <p>A rule that needs verification is created inactive and says so. A regular expression that matches
     * too broadly does not report an error — it files documents in the wrong place, and that is discovered
     * long afterwards.</p>
     */
    @Transactional
    public Rule addRule(UUID tenantId, UUID typeId, String ruleType, String pattern, double weight,
                        boolean requiresAll, UUID actor) {
        requireType(typeId);
        String type = ruleType == null ? "" : ruleType.toUpperCase(java.util.Locale.ROOT);
        if (!RULE_TYPES.contains(type)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Regelart: " + ruleType);
        }
        String cleaned = pattern == null ? "" : pattern.strip();
        if (cleaned.length() < 2) {
            // The schema says the same; saying it here gives the caller a sentence instead of a violation.
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ein Muster braucht mindestens zwei Zeichen");
        }
        if (weight <= 0 || weight > 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Das Gewicht liegt zwischen 0 (ausschließlich) und 1, nicht " + weight);
        }
        if ("REGEX".equals(type)) {
            try {
                java.util.regex.Pattern.compile(cleaned);
            }
            catch (java.util.regex.PatternSyntaxException ex) {
                // Refused now rather than at classification time, when nobody would connect the two.
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Der reguläre Ausdruck ist nicht gültig: " + ex.getDescription());
            }
        }

        boolean needsVerification = NEEDS_VERIFICATION.contains(type);
        UUID id = jdbc.queryForObject("""
                insert into document_type_rules (id, document_type_id, tenant_id, rule_type, pattern, weight,
                                                 requires_all, is_active, created_by)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                returning id
                """, UUID.class, typeId, tenantId, type, cleaned, java.math.BigDecimal.valueOf(weight),
                String.valueOf(requiresAll), !needsVerification, actor);

        audit(tenantId, actor, "DOCUMENT_TYPE_RULE_ADDED", typeId,
                "{\"rule\":\"" + id + "\",\"ruleType\":\"" + type + "\",\"active\":"
                        + !needsVerification + "}");
        LOG.info("Rule {} ({}) added to type {} for tenant {}, active={}", id, type, typeId, tenantId,
                !needsVerification);
        return new Rule(id, typeId, tenantId, type, cleaned, weight, requiresAll, !needsVerification,
                !needsVerification);
    }

    /**
     * Confirms a rule that was waiting for a second person, and switches it on.
     *
     * <p>Refused when the same person who wrote it tries to confirm it: a rule that one person both wrote
     * and approved is a rule nobody checked.</p>
     */
    @Transactional
    public Rule verifyRule(UUID tenantId, UUID ruleId, UUID verifier) {
        Map<String, Object> rule = requireRule(tenantId, ruleId);

        Object author = rule.get("created_by");
        if (author != null && author.equals(verifier)) {
            throw ApiException.forbidden("Niemand bestätigt die eigene Regel; eine Regel, die dieselbe "
                    + "Person geschrieben und freigegeben hat, hat niemand geprüft");
        }

        jdbc.update("""
                update document_type_rules set is_active = true, updated_at = now() where id = ?
                """, ruleId);

        audit((UUID) rule.get("tenant_id"), verifier, "DOCUMENT_TYPE_RULE_VERIFIED",
                (UUID) rule.get("document_type_id"), "{\"rule\":\"" + ruleId + "\"}");
        LOG.info("Rule {} verified by {}", ruleId, verifier);
        return new Rule(ruleId, (UUID) rule.get("document_type_id"), (UUID) rule.get("tenant_id"),
                String.valueOf(rule.get("rule_type")), String.valueOf(rule.get("pattern")),
                ((Number) rule.get("weight")).doubleValue(),
                "true".equalsIgnoreCase(String.valueOf(rule.get("requires_all"))), true, true);
    }

    /** Switches a rule off, or on again. A rule that is off does not classify anything. */
    @Transactional
    public void setRuleActive(UUID tenantId, UUID ruleId, boolean active, UUID actor) {
        Map<String, Object> rule = requireRule(tenantId, ruleId);
        jdbc.update("update document_type_rules set is_active = ?, updated_at = now() where id = ?",
                active, ruleId);
        audit((UUID) rule.get("tenant_id"), actor, active ? "DOCUMENT_TYPE_RULE_ENABLED"
                : "DOCUMENT_TYPE_RULE_DISABLED", (UUID) rule.get("document_type_id"),
                "{\"rule\":\"" + ruleId + "\"}");
    }

    /** Removes a rule. */
    @Transactional
    public void deleteRule(UUID tenantId, UUID ruleId, UUID actor) {
        Map<String, Object> rule = requireRule(tenantId, ruleId);
        jdbc.update("delete from document_type_rules where id = ?", ruleId);
        audit((UUID) rule.get("tenant_id"), actor, "DOCUMENT_TYPE_RULE_DELETED",
                (UUID) rule.get("document_type_id"), "{\"rule\":\"" + ruleId + "\"}");
    }

    /**
     * Whether a person may administer the taxonomy.
     *
     * <p>The permission is the schema's own; this method only asks the same question the endpoint does.</p>
     */
    @Transactional(readOnly = true)
    public boolean mayManage(UUID tenantId, UUID userId) {
        if (userId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from memberships m
                join membership_roles mr on mr.membership_id = m.id
                join role_permissions rp on rp.role_id = mr.role_id
                join permissions p on p.id = rp.permission_id
                where m.tenant_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                  and (mr.expires_at is null or mr.expires_at > now())
                  and (p.code = 'taxonomy.manage' or p.code = 'documents.manage')
                """, Integer.class, tenantId, userId);
        return count != null && count > 0;
    }

    /** Refuses when a person may not administer the taxonomy. */
    @Transactional(readOnly = true)
    public void requireManage(UUID tenantId, UUID userId) {
        if (!mayManage(tenantId, userId)) {
            throw ApiException.forbidden("Für die Pflege der Dokumenttypen fehlt die Berechtigung "
                    + "taxonomy.manage");
        }
    }

    /** The rules waiting for a second person. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> awaitingVerification(UUID tenantId) {
        return jdbc.queryForList("""
                select r.id, r.document_type_id, t.code as type_code, r.rule_type, r.pattern, r.created_by,
                       r.created_at
                from document_type_rules r join document_types t on t.id = r.document_type_id
                where r.tenant_id = ? and r.is_active = false
                order by r.created_at
                """, tenantId);
    }

    /** The changes made to the taxonomy, which is how a mis-filed document is traced back. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(UUID tenantId, int limit) {
        return jdbc.queryForList("""
                select action, entity_id, actor_user_id, changes, created_at from audit_events
                where (tenant_id = ? or tenant_id is null) and action like 'DOCUMENT_TYPE%'
                order by created_at desc limit ?
                """, tenantId, Math.max(1, Math.min(limit, 200)));
    }

    // ------------------------------------------------------------------ internals

    private String requireCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Dokumenttyp braucht einen Code");
        }
        String normalized = code.strip().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        if (!normalized.matches("^[a-z0-9]([a-z0-9_]{1,60}[a-z0-9])$")) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Code darf Kleinbuchstaben, Ziffern und Unterstriche enthalten");
        }
        return normalized;
    }

    private String requireCategory(String category) {
        if (category == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Kategorie ist erforderlich");
        }
        String value = category.toUpperCase(java.util.Locale.ROOT);
        if (!CATEGORIES.contains(value)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Kategorie: " + category);
        }
        return value;
    }

    private Map<String, Object> requireType(UUID typeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, category, is_active, requires_review, sort_order from document_types
                where id = ?
                """, typeId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("document type");
        }
        return rows.getFirst();
    }

    private Map<String, Object> requireRule(UUID tenantId, UUID ruleId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, document_type_id, tenant_id, rule_type, pattern, weight,
                       requires_all::text as requires_all, is_active, created_by
                from document_type_rules where id = ? and tenant_id = ?
                """, ruleId, tenantId);
        if (rows.isEmpty()) {
            // A rule of another tenant is not found rather than refused, which is the rule everywhere else.
            throw ApiException.notFound("document type rule");
        }
        return rows.getFirst();
    }

    private static String json(String value) {
        return "{\"de\":\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "'")) + "\"}";
    }

    /**
     * The tenant a person acts in.
     *
     * <p>An audit row with no tenant is invisible under row level security: the record exists and nobody
     * can find it, which is the opposite of what an audit trail is for. A change to the shared catalogue is
     * therefore recorded against the tenant of the administrator who made it.</p>
     */
    private UUID tenantOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        List<UUID> tenants = jdbc.query("""
                select tenant_id from memberships where user_id = ? and status = 'ACTIVE'
                order by joined_at limit 1
                """, (rs, row) -> rs.getObject("tenant_id", UUID.class), userId);
        return tenants.isEmpty() ? null : tenants.getFirst();
    }

    private void audit(UUID tenantId, UUID actor, String action, UUID entityId, String changes) {
        jdbc.update("""
                insert into audit_events (id, tenant_id, actor_user_id, actor_type, category, action,
                                          entity_type, entity_id, description, changes, result, severity)
                values (gen_random_uuid(), ?, ?, 'USER', 'ADMIN', ?, 'document_type', ?, ?, ?::jsonb,
                        'SUCCESS', 'NOTICE')
                """, tenantId, actor, action, entityId, action, changes);
    }
}
