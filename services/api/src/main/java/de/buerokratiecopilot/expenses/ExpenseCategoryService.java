package de.buerokratiecopilot.expenses;

import java.math.BigDecimal;
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

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.expenses.domain.ExpenseCategory;
import de.buerokratiecopilot.expenses.domain.ExpenseCategoryRule;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Reads and administers the expense catalogue.
 *
 * <p>Reading is open to everybody who may see documents: the catalogue is what a document screen offers in
 * a dropdown, and hiding it would only move the hard-coded list back into the frontend. Writing needs
 * {@code taxonomy.manage} or {@code documents.manage} — the same permission that administers the document
 * types, because both decide where a document ends up.</p>
 *
 * <p>Two rules protect the tree. A code never changes: documents and rules carry it, so renaming it would
 * separate them from their category. A category never becomes its own ancestor: the parent is therefore
 * fixed after creation and a cycle cannot be built through the API.</p>
 */
@Service
public class ExpenseCategoryService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseCategoryService.class);

    private final ExpenseCategoryRepository categories;
    private final ExpenseCategoryRuleRepository rules;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ExpenseCategoryService(ExpenseCategoryRepository categories, ExpenseCategoryRuleRepository rules,
                                  JdbcTemplate jdbc, AuditService audit) {
        this.categories = categories;
        this.rules = rules;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** What a caller sends to create or change a category; every field except the code may be null on update. */
    public record Draft(String code, String parentCode, Map<String, String> name,
                        Map<String, String> description, String datevSkr03, String datevSkr04,
                        String folderPath, Integer sortOrder, Boolean active, Boolean businessExpense,
                        Boolean requiresReview, Map<String, String> warning) {
    }

    /** What a caller sends to add a rule. */
    public record RuleDraft(String ruleType, String pattern, BigDecimal amountMin, BigDecimal amountMax,
                            Double weight, Integer priority, List<String> requiresAll) {
    }

    /** A node of the tree as the API returns it. */
    public record Node(UUID id, String code, String parentCode, Map<String, Object> name,
                       Map<String, Object> description, String datevSkr03, String datevSkr04,
                       String folderPath, int sortOrder, boolean active, boolean businessExpense,
                       boolean requiresReview, Map<String, Object> warning, List<Node> children) {
    }

    /** The categories the schema allows for DATEV charts; exposed so the UI does not hard-code it. */
    public static final List<String> DATEV_CHARTS = List.of("SKR03", "SKR04");

    public static final List<String> RULE_TYPES = List.of("KEYWORD", "REGEX", "SENDER", "FILENAME",
            "AMOUNT_AT_LEAST", "AMOUNT_AT_MOST", "AMOUNT_BETWEEN");

    /** The whole catalogue as a tree, ordered by sort order and code. */
    @Transactional(readOnly = true)
    public List<Node> tree(boolean includeInactive) {
        List<ExpenseCategory> all = includeInactive
                ? categories.findAllByOrderBySortOrderAscCodeAsc()
                : categories.findByActiveTrueOrderBySortOrderAscCodeAsc();
        Map<UUID, String> codeById = new LinkedHashMap<>();
        for (ExpenseCategory category : all) {
            codeById.put(category.getId(), category.getCode());
        }
        Map<UUID, List<ExpenseCategory>> byParent = new LinkedHashMap<>();
        List<ExpenseCategory> roots = new ArrayList<>();
        for (ExpenseCategory category : all) {
            if (category.getParentId() == null || !codeById.containsKey(category.getParentId())) {
                // A child whose parent is inactive is shown as a root rather than disappearing from the
                // catalogue: an administrator has to be able to see and correct it.
                roots.add(category);
            }
            else {
                byParent.computeIfAbsent(category.getParentId(), key -> new ArrayList<>()).add(category);
            }
        }
        List<Node> result = new ArrayList<>(roots.size());
        for (ExpenseCategory root : roots) {
            result.add(node(root, codeById, byParent));
        }
        return List.copyOf(result);
    }

    /** The same catalogue without nesting, for a flat selector. */
    @Transactional(readOnly = true)
    public List<Node> flat(boolean includeInactive) {
        List<ExpenseCategory> all = includeInactive
                ? categories.findAllByOrderBySortOrderAscCodeAsc()
                : categories.findByActiveTrueOrderBySortOrderAscCodeAsc();
        Map<UUID, String> codeById = new LinkedHashMap<>();
        for (ExpenseCategory category : all) {
            codeById.put(category.getId(), category.getCode());
        }
        return all.stream().map(category -> node(category, codeById, Map.of())).toList();
    }

    @Transactional(readOnly = true)
    public ExpenseCategory require(String code) {
        String normalized;
        try {
            normalized = ExpenseCategory.requireCode(code);
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, ex.getMessage());
        }
        return categories.findByCode(normalized)
                .orElseThrow(() -> ApiException.notFound("Expense category"));
    }

    /** Creates a category. Platform-wide, like the document types: the vocabulary is shared. */
    @Transactional
    public ExpenseCategory create(Draft draft, UUID actor) {
        requireManage(actor);
        String code;
        try {
            code = ExpenseCategory.requireCode(draft.code());
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, ex.getMessage());
        }
        if (categories.existsByCode(code)) {
            throw ApiException.duplicate("Diese Kategorie gibt es bereits: " + code);
        }
        UUID parentId = null;
        if (draft.parentCode() != null && !draft.parentCode().isBlank()) {
            parentId = require(draft.parentCode()).getId();
        }
        Map<String, String> name = draft.name();
        if (name == null || name.get("de") == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein deutscher Name ist erforderlich");
        }
        ExpenseCategory category = ExpenseCategory.of(code, parentId, name, draft.description(),
                draft.datevSkr03(), draft.datevSkr04(), draft.folderPath(),
                draft.sortOrder() == null ? 100 : draft.sortOrder(),
                draft.businessExpense() == null || draft.businessExpense(),
                draft.requiresReview() != null && draft.requiresReview(), draft.warning());
        if (draft.active() != null) {
            category.setActive(draft.active());
        }
        categories.save(category);
        audit(actor, "EXPENSE_CATEGORY_CREATED", category, Map.of("code", code));
        log.info("Expense category {} created by {}", code, actor);
        return category;
    }

    /** Changes everything except the code and the parent (see the class comment). */
    @Transactional
    public ExpenseCategory update(String code, Draft draft, UUID actor) {
        requireManage(actor);
        ExpenseCategory category = require(code);
        if (draft.name() != null && draft.name().get("de") != null) {
            category.setName(new LinkedHashMap<>(draft.name()));
        }
        if (draft.description() != null) {
            category.setDescription(new LinkedHashMap<>(draft.description()));
        }
        if (draft.datevSkr03() != null) {
            category.setDatevSkr03(draft.datevSkr03());
        }
        if (draft.datevSkr04() != null) {
            category.setDatevSkr04(draft.datevSkr04());
        }
        if (draft.folderPath() != null) {
            category.setFolderPath(draft.folderPath());
        }
        if (draft.sortOrder() != null) {
            category.setSortOrder(draft.sortOrder());
        }
        if (draft.active() != null) {
            category.setActive(draft.active());
        }
        if (draft.businessExpense() != null) {
            category.setBusinessExpense(draft.businessExpense());
        }
        if (draft.requiresReview() != null) {
            category.setRequiresReview(draft.requiresReview());
        }
        if (draft.warning() != null) {
            category.setWarning(new LinkedHashMap<>(draft.warning()));
        }
        categories.save(category);
        audit(actor, "EXPENSE_CATEGORY_UPDATED", category, Map.of("code", category.getCode()));
        log.info("Expense category {} changed by {}", category.getCode(), actor);
        return category;
    }

    @Transactional(readOnly = true)
    public List<ExpenseCategoryRule> rulesOf(String code) {
        ExpenseCategory category = require(code);
        return rules.findByCategoryIdOrderByPriorityDescPatternAsc(category.getId());
    }

    /** Adds a recognition rule; and a rule that nobody can read later is refused early rather than at 3 a.m. */
    @Transactional
    public ExpenseCategoryRule addRule(String categoryCode, RuleDraft draft, UUID actor) {
        requireManage(actor);
        ExpenseCategory category = require(categoryCode);
        ExpenseCategoryRule.RuleType type = parseRuleType(draft.ruleType());
        double weight = draft.weight() == null ? 0.5 : draft.weight();
        try {
            if (type == ExpenseCategoryRule.RuleType.REGEX) {
                java.util.regex.Pattern.compile(draft.pattern());
            }
            ExpenseCategoryRule rule = ExpenseCategoryRule.of(category.getId(), type, draft.pattern(),
                    draft.amountMin(), draft.amountMax(), weight,
                    draft.priority() == null ? 100 : draft.priority(), draft.requiresAll(),
                    Map.of("de", "Von einem Administrator angelegt"));
            rules.save(rule);
            audit(actor, "EXPENSE_CATEGORY_RULE_ADDED", category, Map.of("rule", rule.getId().toString()));
            log.info("Rule {} ({}) added to expense category {} by {}", rule.getId(), type,
                    category.getCode(), actor);
            return rule;
        }
        catch (java.util.regex.PatternSyntaxException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der reguläre Ausdruck ist nicht gültig: " + ex.getDescription());
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    ex.getMessage() == null ? "Die Regel ist nicht gültig" : ex.getMessage());
        }
    }

    /** Switches a rule off or on. A rule that is off does not file anything. */
    @Transactional
    public ExpenseCategoryRule setRuleActive(UUID ruleId, boolean active, UUID actor) {
        requireManage(actor);
        ExpenseCategoryRule rule = rules.findById(ruleId)
                .orElseThrow(() -> ApiException.notFound("Expense category rule"));
        rule.setActive(active);
        rules.save(rule);
        audit(actor, "EXPENSE_CATEGORY_RULE_CHANGED", null,
                Map.of("rule", ruleId.toString(), "active", active));
        return rule;
    }

    public boolean mayManage(UUID userId) {
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
                  and p.code in ('taxonomy.manage', 'documents.manage')
                """, Integer.class, currentTenant(), userId);
        return count != null && count > 0;
    }

    /** Refuses a caller who may not administer the catalogue, for callers other than the endpoint too. */
    public void requireManage(UUID actor) {
        if (!mayManage(actor)) {
            throw ApiException.forbidden("Für die Pflege der Ausgabenkategorien fehlt die Berechtigung "
                    + "taxonomy.manage");
        }
    }

    private UUID currentTenant() {
        return de.buerokratiecopilot.shared.context.RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
    }

    private ExpenseCategoryRule.RuleType parseRuleType(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Regelart ist erforderlich");
        }
        try {
            return ExpenseCategoryRule.RuleType.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Regelart: " + value);
        }
    }

    private Node node(ExpenseCategory category, Map<UUID, String> codeById,
                      Map<UUID, List<ExpenseCategory>> byParent) {
        List<Node> children = new ArrayList<>();
        for (ExpenseCategory child : byParent.getOrDefault(category.getId(), List.of())) {
            children.add(node(child, codeById, byParent));
        }
        return new Node(category.getId(), category.getCode(),
                category.getParentId() == null ? null : codeById.get(category.getParentId()),
                category.getName(), category.getDescription(), category.getDatevSkr03(),
                category.getDatevSkr04(), category.getFolderPath(), category.getSortOrder(),
                category.isActive(), category.isBusinessExpense(), category.isRequiresReview(),
                category.getWarning(), List.copyOf(children));
    }

    private void audit(UUID actor, String action, ExpenseCategory category, Map<String, Object> changes) {
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.ADMIN, action)
                .tenant(currentTenant())
                .actor(AuditEvent.ActorType.USER, actor, null)
                .entity("expense_categories", category == null ? null : category.getId(),
                        category == null ? null : category.getCode())
                .description("Ausgabenkategorie geändert")
                .changes(changes)
                .build());
    }
}
