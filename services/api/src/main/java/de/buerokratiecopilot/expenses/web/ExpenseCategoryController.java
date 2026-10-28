package de.buerokratiecopilot.expenses.web;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.expenses.ExpenseCategoryService;
import de.buerokratiecopilot.expenses.domain.ExpenseCategory;
import de.buerokratiecopilot.expenses.domain.ExpenseCategoryRule;
import de.buerokratiecopilot.shared.context.CurrentUser;

/**
 * The expense catalogue (spec §6, §20; task: ELSTER/DATEV categories).
 *
 * <p>Reading is part of the document screens — the category dropdown must come from the database, not from
 * a list in the frontend. Writing is the administrator's extension point: a trade adds a category, corrects
 * a DATEV account or adds the sender of a supplier nobody knew, without a release.</p>
 */
@RestController
@RequestMapping("/api/v1/expense-categories")
@Tag(name = "Expense categories", description = "The ELSTER/DATEV expense catalogue and its recognition rules")
public class ExpenseCategoryController {

    private static final String READ = "hasAnyAuthority('documents.view','taxonomy.manage')";
    private static final String MANAGE = "hasAnyAuthority('taxonomy.manage','documents.manage')";

    private final ExpenseCategoryService catalogue;

    public ExpenseCategoryController(ExpenseCategoryService catalogue) {
        this.catalogue = catalogue;
    }

    /** What a caller sends to create or change a category. The code is immutable after creation. */
    public record CategoryRequest(String code, String parentCode, String nameDe, String nameEn, String nameRu,
                                  String descriptionDe, String descriptionEn, String descriptionRu,
                                  String datevSkr03, String datevSkr04, String folderPath, Integer sortOrder,
                                  Boolean active, Boolean businessExpense, Boolean requiresReview,
                                  String warningDe, String warningEn, String warningRu) {
    }

    /** What a caller sends to add a recognition rule. */
    public record RuleRequest(String ruleType, String pattern, BigDecimal amountMin, BigDecimal amountMax,
                              Double weight, Integer priority, List<String> requiresAll) {
    }

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "The catalogue as a tree (or flat with ?flat=true)")
    public List<Map<String, Object>> tree(@RequestParam(defaultValue = "false") boolean flat,
                                          @RequestParam(defaultValue = "false") boolean includeInactive) {
        if (flat) {
            return catalogue.flat(includeInactive).stream().map(ExpenseCategoryController::asMap).toList();
        }
        return catalogue.tree(includeInactive).stream().map(ExpenseCategoryController::asMap).toList();
    }

    /** The values the API accepts, so an interface can offer them instead of hard-coding them. */
    @GetMapping("/catalogue")
    @PreAuthorize(READ)
    @Operation(summary = "The rule types, DATEV charts and the folder template placeholders")
    public Map<String, Object> catalogue() {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("ruleTypes", ExpenseCategoryService.RULE_TYPES);
        answer.put("datevCharts", ExpenseCategoryService.DATEV_CHARTS);
        answer.put("folderPlaceholders", List.of("{year}", "{month}"));
        answer.put("note", "Kategorien, Konten und Regeln stammen aus der Datenbank, nicht aus der Oberfläche.");
        return answer;
    }

    @GetMapping("/{code}")
    @PreAuthorize(READ)
    @Operation(summary = "One category with its DATEV accounts and the folder template")
    public Map<String, Object> one(@PathVariable String code) {
        return asMap(nodeOf(catalogue.require(code)));
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    @Operation(summary = "Creates a category; needs taxonomy.manage or documents.manage")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CategoryRequest request) {
        UUID actor = CurrentUser.required();
        ExpenseCategory created = catalogue.create(draft(request), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(asMap(nodeOf(created)));
    }

    @PutMapping("/{code}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Changes a category; the code and the parent are not among the fields")
    public Map<String, Object> update(@PathVariable String code, @RequestBody CategoryRequest request) {
        UUID actor = CurrentUser.required();
        return asMap(nodeOf(catalogue.update(code, draft(request), actor)));
    }

    @GetMapping("/{code}/rules")
    @PreAuthorize(READ)
    @Operation(summary = "The recognition rules of a category")
    public List<Map<String, Object>> rules(@PathVariable String code) {
        return catalogue.rulesOf(code).stream().map(ExpenseCategoryController::ruleMap).toList();
    }

    @PostMapping("/{code}/rules")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Adds a recognition rule: keyword, sender, file name or amount threshold")
    public ResponseEntity<Map<String, Object>> addRule(@PathVariable String code,
                                                       @RequestBody RuleRequest request) {
        UUID actor = CurrentUser.required();
        ExpenseCategoryRule rule = catalogue.addRule(code, new ExpenseCategoryService.RuleDraft(
                request.ruleType(), request.pattern(), request.amountMin(), request.amountMax(),
                request.weight(), request.priority(), request.requiresAll()), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ruleMap(rule));
    }

    @PutMapping("/rules/{ruleId}/active")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Switches a rule off or on")
    public Map<String, Object> ruleActive(@PathVariable UUID ruleId, @RequestParam boolean active) {
        return ruleMap(catalogue.setRuleActive(ruleId, active, CurrentUser.required()));
    }

    // ------------------------------------------------------------------ mapping

    private static ExpenseCategoryService.Draft draft(CategoryRequest request) {
        return new ExpenseCategoryService.Draft(request.code(), request.parentCode(),
                names(request.nameDe(), request.nameEn(), request.nameRu()),
                names(request.descriptionDe(), request.descriptionEn(), request.descriptionRu()),
                request.datevSkr03(), request.datevSkr04(), request.folderPath(), request.sortOrder(),
                request.active(), request.businessExpense(), request.requiresReview(),
                names(request.warningDe(), request.warningEn(), request.warningRu()));
    }

    private static Map<String, String> names(String de, String en, String ru) {
        if (de == null && en == null && ru == null) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (de != null) {
            values.put("de", de);
        }
        if (en != null) {
            values.put("en", en);
        }
        if (ru != null) {
            values.put("ru", ru);
        }
        return values;
    }

    private static ExpenseCategoryService.Node nodeOf(ExpenseCategory category) {
        return new ExpenseCategoryService.Node(category.getId(), category.getCode(), null,
                category.getName(), category.getDescription(), category.getDatevSkr03(),
                category.getDatevSkr04(), category.getFolderPath(), category.getSortOrder(),
                category.isActive(), category.isBusinessExpense(), category.isRequiresReview(),
                category.getWarning(), List.of());
    }

    private static Map<String, Object> asMap(ExpenseCategoryService.Node node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", node.id() == null ? null : node.id().toString());
        map.put("code", node.code());
        map.put("parentCode", node.parentCode());
        map.put("name", node.name());
        map.put("description", node.description());
        Map<String, Object> datev = new LinkedHashMap<>();
        datev.put("skr03", node.datevSkr03());
        datev.put("skr04", node.datevSkr04());
        map.put("datev", datev);
        map.put("folderPath", node.folderPath());
        map.put("sortOrder", node.sortOrder());
        map.put("isActive", node.active());
        map.put("isBusinessExpense", node.businessExpense());
        map.put("requiresReview", node.requiresReview());
        map.put("warning", node.warning());
        map.put("children", node.children().stream().map(ExpenseCategoryController::asMap).toList());
        return map;
    }

    private static Map<String, Object> ruleMap(ExpenseCategoryRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rule.getId().toString());
        map.put("categoryId", rule.getCategoryId().toString());
        map.put("ruleType", rule.getRuleType().name());
        map.put("pattern", rule.getPattern());
        map.put("amountMin", rule.getAmountMin());
        map.put("amountMax", rule.getAmountMax());
        map.put("weight", rule.weightAsDouble());
        map.put("priority", rule.getPriority());
        map.put("requiresAll", rule.getRequiresAll());
        map.put("description", rule.getDescription());
        map.put("isActive", rule.isActive());
        return map;
    }
}
