package de.buerokratiecopilot.expenses;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.expenses.domain.ExpenseCategory;
import de.buerokratiecopilot.expenses.domain.ExpenseCategoryRule;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * Files an expense document: chooses the category from the rules and derives the archive folder.
 *
 * <p>Three decisions, each with a reason:</p>
 * <ul>
 *   <li><strong>The category is set from the review threshold upward.</strong> A suggestion above it is
 *       stored and shown, but no folder is created from a guess that a person has not seen.</li>
 *   <li><strong>The folder is set only when the category reaches the acceptance threshold.</strong> The
 *       archive is the product: a wrong drawer is worse than a drawer that is still empty.</li>
 *   <li><strong>A person's decision is never overwritten.</strong> The source says MANUAL, and neither the
 *       pipeline nor a re-sort changes it. Only an explicit {@code categorize} call with a category does.</li>
 * </ul>
 *
 * <p>What the rules may look at is the text read from the document, the sender and the file name, plus the
 * amounts in the text. In the pilot OCR is off ({@code BC_ALLOW_NO_OCR=true}); the file name and the sender
 * then carry a larger share of the evidence, which is exactly what the FILENAME and SENDER rules are for.
 * No external service is called.</p>
 */
@Service
public class ExpenseCategorizationService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseCategorizationService.class);

    /** Starting values; both can be changed per tenant through the settings service. */
    static final double DEFAULT_ACCEPT_THRESHOLD = 0.65;
    static final double DEFAULT_REVIEW_THRESHOLD = 0.35;

    /** The document source flags; kept as strings because the database checks them and the history is JSON. */
    public static final String SOURCE_RULE = "RULE";
    public static final String SOURCE_MANUAL = "MANUAL";

    private final ExpenseRuleEngine engine;
    private final ExpenseCategoryRepository categories;
    private final ExpenseCategoryRuleRepository rules;
    private final DocumentRepository documents;
    private final SettingsService settings;
    private final AuditService audit;

    public ExpenseCategorizationService(ExpenseRuleEngine engine, ExpenseCategoryRepository categories,
                                        ExpenseCategoryRuleRepository rules, DocumentRepository documents,
                                        SettingsService settings, AuditService audit) {
        this.engine = engine;
        this.categories = categories;
        this.rules = rules;
        this.documents = documents;
        this.settings = settings;
        this.audit = audit;
    }

    /** What the engine decided, in the shape the API and the document DTO expose. */
    public record Decision(UUID categoryId, String code, Map<String, Object> name,
                           Map<String, Object> description, Map<String, Object> warning,
                           String datevSkr03, String datevSkr04, String folderTemplate, String folderPath,
                           double confidence, boolean businessExpense, boolean requiresReview,
                           String source, String rationale, List<String> evidence,
                           List<BigDecimal> amounts) {
    }

    /**
     * Result of one run.
     *
     * @param applied   the category was stored and the folder was created
     * @param suggested the category was stored as a suggestion, without a folder
     * @param manual    a person had already chosen the category and it was left alone
     */
    public record Outcome(boolean applied, boolean suggested, boolean manual, Decision decision,
                          String message) {
    }

    private static Outcome none(String message) {
        return new Outcome(false, false, false, null, message);
    }

    /** Runs the rules for a document and stores the result. Does not overwrite a person's decision. */
    @Transactional
    public Outcome categorize(Document document) {
        return apply(document, false, false);
    }

    /**
     * Re-runs the rules for a stored document and removes a category a rule set earlier when the rules no
     * longer stand behind it. A person's decision stays.
     */
    @Transactional
    public Outcome refresh(Document document) {
        return apply(document, true, false);
    }

    /**
     * The explicit call behind {@code POST /documents/{id}/categorize}.
     *
     * @param categoryCode a category chosen by a person, or {@code null} to re-run the rules and apply the
     *                     best match even below the acceptance threshold
     */
    @Transactional
    public Outcome categorizeNow(UUID documentId, String categoryCode, UUID actor) {
        UUID tenantId = RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(de.buerokratiecopilot.shared.error.ErrorCode.FORBIDDEN,
                        "No tenant in context"));
        Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId)
                .orElseThrow(() -> ApiException.notFound("Document"));

        if (categoryCode != null && !categoryCode.isBlank()) {
            ExpenseCategory category = categories.findByCode(categoryCode.strip())
                    .filter(ExpenseCategory::isActive)
                    .orElseThrow(() -> ApiException.notFound("Expense category"));
            assign(document, category, 1.0, SOURCE_MANUAL, "Von einer Person gewählt", List.of(), List.of());
            documents.save(document);
            auditManual(document, category, actor);
            log.info("Document {} categorised manually as {} by {}", documentId, category.getCode(), actor);
            return new Outcome(true, false, true, decision(document, category, 1.0, SOURCE_MANUAL,
                    "Von einer Person gewählt", List.of(), List.of(), true), "Kategorie gespeichert");
        }
        Outcome outcome = apply(document, true, true);
        if (outcome.decision() != null && outcome.decision().source().equals(SOURCE_RULE)) {
            auditManual(document, categories.findById(outcome.decision().categoryId()).orElse(null), actor);
        }
        return outcome;
    }

    private Outcome apply(Document document, boolean refresh, boolean force) {
        if (document == null) {
            return none("Kein Dokument");
        }
        if (SOURCE_MANUAL.equals(document.getExpenseCategorySource()) && document.getExpenseCategoryId() != null) {
            ExpenseCategory category = categories.findById(document.getExpenseCategoryId()).orElse(null);
            return new Outcome(false, false, true,
                    category == null ? null : decision(document, category, 1.0, SOURCE_MANUAL,
                            document.getExpenseCategoryRationale(), List.of(), List.of(), true),
                    "Die Kategorie wurde von einer Person gewählt und bleibt");
        }

        List<ExpenseCategory> catalogue = categories.findByActiveTrueOrderBySortOrderAscCodeAsc();
        List<ExpenseCategoryRule> configured = rules.findByActiveTrueOrderByPriorityDesc();
        if (catalogue.isEmpty() || configured.isEmpty()) {
            return none("Der Kategorienkatalog ist leer");
        }

        Map<UUID, String> codeById = new LinkedHashMap<>();
        Map<String, ExpenseCategory> byCode = new LinkedHashMap<>();
        for (ExpenseCategory category : catalogue) {
            codeById.put(category.getId(), category.getCode());
            byCode.put(category.getCode(), category);
        }
        List<ExpenseRuleEngine.Category> engineCategories = new ArrayList<>(catalogue.size());
        for (ExpenseCategory category : catalogue) {
            engineCategories.add(new ExpenseRuleEngine.Category(category.getCode(),
                    category.getParentId() == null ? null : codeById.get(category.getParentId()),
                    category.isBusinessExpense(), category.isRequiresReview(), category.getSortOrder()));
        }
        List<ExpenseRuleEngine.Rule> engineRules = new ArrayList<>(configured.size());
        for (ExpenseCategoryRule rule : configured) {
            String code = codeById.get(rule.getCategoryId());
            if (code == null) {
                continue; // a rule for a deactivated category; the database keeps it for reactivation
            }
            engineRules.add(new ExpenseRuleEngine.Rule(code, rule.getRuleType(), rule.getPattern(),
                    rule.getAmountMin(), rule.getAmountMax(), rule.weightAsDouble(), rule.getPriority(),
                    rule.getRequiresAll()));
        }

        ExpenseRuleEngine.Result result = engine.categorize(document.getExtractedText(),
                document.getOriginalFilename(), document.getSender(), engineCategories, engineRules);
        ExpenseRuleEngine.Match best = result.best();
        if (best == null) {
            if (refresh && SOURCE_RULE.equals(document.getExpenseCategorySource())) {
                clear(document);
                return none("Keine Regel passt; die frühere Kategorie wurde entfernt");
            }
            return none("Keine Regel passt");
        }

        double accept = threshold("expense.auto_accept_threshold", DEFAULT_ACCEPT_THRESHOLD);
        double review = Math.min(accept, threshold("expense.review_threshold", DEFAULT_REVIEW_THRESHOLD));
        boolean accepted = force || best.score() >= accept;
        boolean shown = accepted || best.score() >= review;
        ExpenseCategory category = byCode.get(best.categoryCode());
        if (category == null) {
            return none("Die erkannte Kategorie ist nicht mehr aktiv");
        }
        List<String> evidence = best.evidence().stream().map(ExpenseRuleEngine.Evidence::describe).toList();
        String rationale = "Erkannt aus: " + String.join(", ", evidence)
                + " (Sicherheit %.2f)".formatted(best.score());

        // A collection node (no EÜR line) would take the receipt out of every report: the amount would sit
        // in a folder nobody can map to line 27/51/... . Such a hit stays a suggestion, even when forced.
        boolean reportable = category.getEuerLine() != null && category.getFolderPath() != null;
        if (!reportable) {
            accepted = false;
            if (shown) {
                rationale = rationale + "; Sammelkategorie ohne EÜR-Zeile — Ablage erst nach Wahl einer Unterkategorie";
            }
        }

        if (!shown) {
            if (refresh && SOURCE_RULE.equals(document.getExpenseCategorySource())) {
                clear(document);
                return none("Die Sicherheit %.2f liegt unter der Vorschlagsschwelle; keine Ablage"
                        .formatted(best.score()));
            }
            return none("Die Sicherheit %.2f ist zu niedrig".formatted(best.score()));
        }

        // A suggested category is stored, but it does not create a folder: the archive must not receive a
        // drawer based on a guess a person has not confirmed.
        assign(document, category, best.score(), SOURCE_RULE, rationale, evidence, result.amounts());
        if (!accepted) {
            document.setFolderPath(null);
        }
        documents.save(document);

        Decision decision = decision(document, category, best.score(), SOURCE_RULE, rationale, evidence,
                result.amounts(), accepted);
        return new Outcome(accepted, !accepted, false, decision,
                accepted ? "Beleg abgelegt" : "Kategorie vorgeschlagen; die Ablage erfolgt nach Bestätigung");
    }

    private void assign(Document document, ExpenseCategory category, double confidence, String source,
                        String rationale, List<String> evidence, List<BigDecimal> amounts) {
        document.setExpenseCategoryId(category.getId());
        document.setExpenseCategoryConfidence(BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP));
        document.setExpenseCategorySource(source);
        document.setExpenseCategoryRationale(rationale);
        document.setCategorizedAt(Instant.now());
        // Always overwrite: a category without a template must not inherit the folder of the category
        // the document had before. The caller clears it again for a mere suggestion.
        document.setFolderPath(folderFor(category, document));
    }

    private void clear(Document document) {
        document.setExpenseCategoryId(null);
        document.setFolderPath(null);
        document.setExpenseCategoryConfidence(null);
        document.setExpenseCategorySource(null);
        document.setExpenseCategoryRationale(null);
        document.setCategorizedAt(null);
        documents.save(document);
    }

    private Decision decision(Document document, ExpenseCategory category, double confidence, String source,
                              String rationale, List<String> evidence, List<BigDecimal> amounts,
                              boolean folderApplied) {
        return new Decision(category.getId(), category.getCode(), category.getName(),
                category.getDescription(), category.getWarning(), category.getDatevSkr03(),
                category.getDatevSkr04(), category.getFolderPath(),
                folderApplied ? document.getFolderPath() : null, confidence, category.isBusinessExpense(),
                category.isRequiresReview(), source, rationale, evidence, amounts);
    }

    private double threshold(String key, double fallback) {
        double value = settings.getDouble(key, fallback);
        return Math.max(0.01, Math.min(1.0, value));
    }

    private void auditManual(Document document, ExpenseCategory category, UUID actor) {
        if (category == null) {
            return;
        }
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("expenseCategory", category.getCode());
        changes.put("folderPath", document.getFolderPath());
        changes.put("source", document.getExpenseCategorySource());
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.DOCUMENT, "DOCUMENT_CATEGORIZED")
                .tenant(document.getTenantId())
                .actor(AuditEvent.ActorType.USER, actor, null)
                .entity("documents", document.getId(), document.getOriginalFilename())
                .description("Belegkategorie '%s' gesetzt".formatted(category.getCode()))
                .changes(changes)
                .build());
    }

    /**
     * The folder a category files into, for the year and month of the document.
     *
     * <p>The template lives in the catalogue ({@code /Belege/{year}/Material/Waren}), because a Handwerk
     * business organises its folder tree differently from a office. A category without a template creates
     * no folder, and the document stays in the inbox — that is better than inventing a path.</p>
     */
    public static String folderFor(ExpenseCategory category, Document document) {
        return resolveFolder(category == null ? null : category.getFolderPath(),
                document == null ? null : document.getDocumentDate(),
                document == null ? null : document.getReceivedAt());
    }

    static String resolveFolder(String template, LocalDate documentDate, Instant receivedAt) {
        if (template == null || template.isBlank()) {
            return null;
        }
        LocalDate date = documentDate;
        if (date == null && receivedAt != null) {
            date = LocalDate.ofInstant(receivedAt, ZoneId.systemDefault());
        }
        if (date == null) {
            date = LocalDate.now();
        }
        return template.replace("{year}", String.valueOf(date.getYear()))
                .replace("{month}", "%02d".formatted(date.getMonthValue()));
    }
}
