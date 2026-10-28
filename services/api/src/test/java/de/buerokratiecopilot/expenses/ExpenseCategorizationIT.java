package de.buerokratiecopilot.expenses;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.expenses.domain.ExpenseCategory;
import de.buerokratiecopilot.expenses.domain.ExpenseCategoryRule;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * The seeded catalogue, the categorisation of a real document row and the folder it is filed into.
 *
 * <p>This runs against PostgreSQL because the questions are database questions: are the 80+ categories
 * and their rules actually there, does the document row carry the category, and is the folder the one the
 * catalogue template names for the document's year?</p>
 */
@SpringBootTest
@ExtendWith(TenantContextExtension.class)
@ActiveProfiles("test")
class ExpenseCategorizationIT {

    @Autowired
    private ExpenseCategorizationService categorization;
    @Autowired
    private ExpenseCategoryService catalogue;
    @Autowired
    private ExpenseCategoryRepository categories;
    @Autowired
    private ExpenseCategoryRuleRepository rules;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("insert into tenants (id, slug, name, account_type) values (?, ?, 'Test Handwerk', 'COMPANY')",
                tenantId, "ex-" + tenantId.toString().substring(0, 12));
        return tenantId;
    }

    private UUID document(UUID tenantId, String filename, String text, String sender, LocalDate date) {
        return RequestContext.runAs(tenantId, () -> jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status, extracted_text,
                                       sender, document_date)
                values (gen_random_uuid(), ?, ?, ?, 'application/pdf', 100, ?, 'UPLOAD', 'READY',
                        'NOT_REQUIRED', ?, ?, ?)
                returning id
                """, UUID.class, tenantId, filename, filename, hash(), text, sender, date));
    }

    private static String hash() {
        return "%064x".formatted(ThreadLocalRandom.current().nextLong());
    }

    private Document load(UUID tenantId, UUID id) {
        return RequestContext.runAs(tenantId, () -> documents.findById(id).orElseThrow());
    }

    private String categoryCode(UUID tenantId, UUID documentId) {
        return RequestContext.runAs(tenantId, () -> jdbc.query("""
                select c.code from documents d left join expense_categories c on c.id = d.expense_category_id
                where d.id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, documentId));
    }

    private String folder(UUID tenantId, UUID documentId) {
        return RequestContext.runAs(tenantId, () -> jdbc.queryForObject(
                "select folder_path from documents where id = ?", String.class, documentId));
    }

    @Test
    @DisplayName("the seeded catalogue has at least 80 concrete categories, localized names, DATEV accounts and folders")
    void catalogueIsSeeded() {
        List<ExpenseCategoryService.Node> tree = catalogue.tree(false);

        assertThat(tree).hasSizeGreaterThanOrEqualTo(8);
        int count = count(tree);
        assertThat(count).as("seeded categories (roots and sub-categories)").isGreaterThanOrEqualTo(80);

        ExpenseCategoryService.Node material = tree.stream()
                .filter(node -> node.code().equals("material")).findFirst().orElseThrow();
        ExpenseCategoryService.Node goods = material.children().stream()
                .filter(node -> node.code().equals("material.waren")).findFirst().orElseThrow();

        assertThat(goods.name()).containsKeys("de", "en", "ru");
        assertThat(goods.description()).containsKeys("de", "en", "ru");
        assertThat(goods.datevSkr03()).isEqualTo("3400");
        assertThat(goods.datevSkr04()).isEqualTo("5400");
        assertThat(goods.folderPath()).isEqualTo("/Belege/{year}/Material/Waren");
        assertThat(goods.active()).isTrue();

        ExpenseCategoryService.Node privateRoot = tree.stream()
                .filter(node -> node.code().equals("privatentnahme")).findFirst().orElseThrow();
        assertThat(privateRoot.businessExpense()).isFalse();
        assertThat(privateRoot.requiresReview()).isTrue();
        assertThat(privateRoot.warning().get("de").toString()).contains("keine Betriebsausgabe");
    }

    private static int count(List<ExpenseCategoryService.Node> nodes) {
        int total = 0;
        for (ExpenseCategoryService.Node node : nodes) {
            total += 1 + count(node.children());
        }
        return total;
    }

    @Test
    @DisplayName("the rules of a category are seeded and the engine finds a material receipt")
    void materialReceiptIsFiledIntoItsFolder() {
        UUID tenantId = tenant();
        UUID id = document(tenantId, "obi-beleg.pdf",
                "OBI Baumarkt\nZement und Dämmung für die Baustelle\n Gesamt 349,90 €",
                "OBI", LocalDate.of(2026, 3, 15));

        ExpenseCategorizationService.Outcome outcome = RequestContext.runAs(tenantId,
                () -> categorization.categorize(load(tenantId, id)));

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.decision().code()).isEqualTo("material.waren");
        assertThat(outcome.decision().confidence()).isGreaterThan(0.65);
        assertThat(outcome.decision().folderPath()).isEqualTo("/Belege/2026/Material/Waren");
        assertThat(categoryCode(tenantId, id)).isEqualTo("material.waren");
        assertThat(folder(tenantId, id)).isEqualTo("/Belege/2026/Material/Waren");
        assertThat(outcome.decision().evidence()).isNotEmpty();
    }

    @Test
    @DisplayName("a Würth receipt becomes a tool, not material")
    void toolReceiptIsRecognised() {
        UUID tenantId = tenant();
        UUID id = document(tenantId, "wuerth-akkuschrauber.pdf", "Würth\nAkkuschrauber 18 V\n199,00 €",
                "Würth", LocalDate.of(2026, 4, 2));

        ExpenseCategorizationService.Outcome outcome = RequestContext.runAs(tenantId,
                () -> categorization.categorize(load(tenantId, id)));

        assertThat(outcome.decision().code()).isEqualTo("werkzeuge.elektrowerkzeuge");
        assertThat(folder(tenantId, id)).isEqualTo("/Belege/2026/Werkzeuge/Elektrowerkzeuge");
    }

    @Test
    @DisplayName("a fuel receipt and a car repair reach the vehicle folders")
    void vehicleReceiptsAreRecognised() {
        UUID tenantId = tenant();
        UUID fuel = document(tenantId, "shell-tankbeleg.pdf", "Shell Tankstelle\nDiesel\n89,50 €",
                "Shell", LocalDate.of(2026, 5, 4));
        UUID repair = document(tenantId, "atu-reparatur.pdf", "ATU Autowerkstatt\nReparatur Bremsen\n450,00 €",
                "ATU", LocalDate.of(2026, 5, 6));

        RequestContext.runAs(tenantId, () -> categorization.categorize(load(tenantId, fuel)));
        RequestContext.runAs(tenantId, () -> categorization.categorize(load(tenantId, repair)));

        assertThat(categoryCode(tenantId, fuel)).isEqualTo("fahrzeug.kraftstoff");
        assertThat(folder(tenantId, fuel)).isEqualTo("/Belege/2026/Fahrzeug/Kraftstoff");
        assertThat(categoryCode(tenantId, repair)).isEqualTo("fahrzeug.reparatur");
        assertThat(folder(tenantId, repair)).isEqualTo("/Belege/2026/Fahrzeug/Reparatur");
        assertThat(RequestContext.runAs(tenantId, () -> jdbc.queryForObject(
                "select expense_category_source from documents where id = ?", String.class, fuel)))
                .isEqualTo("RULE");
    }

    @Test
    @DisplayName("a private withdrawal is filed, but marked as not business and carries the warning")
    void privateWithdrawalCarriesWarning() {
        UUID tenantId = tenant();
        UUID id = document(tenantId, "privatentnahme.pdf", "Privatentnahme\nBargeld für private Ausgaben\n500,00 €",
                null, LocalDate.of(2026, 6, 1));

        ExpenseCategorizationService.Outcome outcome = RequestContext.runAs(tenantId,
                () -> categorization.categorize(load(tenantId, id)));

        assertThat(outcome.applied()).isTrue();
        assertThat(outcome.decision().code()).isEqualTo("privatentnahme.geld");
        assertThat(outcome.decision().businessExpense()).isFalse();
        assertThat(outcome.decision().requiresReview()).isTrue();
        assertThat(outcome.decision().warning().get("de").toString()).contains("keine Betriebsausgabe");
        assertThat(folder(tenantId, id)).isEqualTo("/Belege/2026/Privatentnahme/Geld");
    }

    @Test
    @DisplayName("a category a person chose survives the rules and the re-sorting")
    void manualChoiceIsNotOverwritten() {
        UUID tenantId = tenant();
        UUID id = document(tenantId, "beleg.pdf", "Würth Akkuschrauber 199,00 €", "Würth",
                LocalDate.of(2026, 7, 1));

        ExpenseCategorizationService.Outcome manual = RequestContext.runAs(tenantId,
                () -> categorization.categorizeNow(id, "werkzeuge.handwerkzeug", null));
        assertThat(manual.applied()).isTrue();
        assertThat(manual.decision().source()).isEqualTo("MANUAL");
        assertThat(folder(tenantId, id)).isEqualTo("/Belege/2026/Werkzeuge/Handwerkzeug");

        // The engine would choose the power tool, but the person's choice wins on a repeated run.
        RequestContext.runAs(tenantId, () -> categorization.categorize(load(tenantId, id)));
        assertThat(categoryCode(tenantId, id)).isEqualTo("werkzeuge.handwerkzeug");
        assertThat(folder(tenantId, id)).isEqualTo("/Belege/2026/Werkzeuge/Handwerkzeug");
    }

    @Test
    @DisplayName("the explicit categorize call applies the best match and can clear a stale rule category")
    void forcedCategorizationAppliesTheBestMatch() {
        UUID tenantId = tenant();
        UUID id = document(tenantId, "unbekannt.pdf", "Irgendein Beleg ohne erkennbaren Bezug", null,
                LocalDate.of(2026, 8, 1));

        ExpenseCategorizationService.Outcome none = RequestContext.runAs(tenantId,
                () -> categorization.categorizeNow(id, null, null));
        assertThat(none.decision()).isNull();
        assertThat(categoryCode(tenantId, id)).isNull();

        ExpenseCategorizationService.Outcome forced = RequestContext.runAs(tenantId,
                () -> categorization.categorizeNow(id, "material.waren", null));
        assertThat(forced.applied()).isTrue();
        assertThat(categoryCode(tenantId, id)).isEqualTo("material.waren");
        assertThat(folder(tenantId, id)).isEqualTo("/Belege/2026/Material/Waren");
    }

    @Test
    @DisplayName("the seeded rules are readable through the catalogue service")
    void rulesAreSeeded() {
        List<ExpenseCategoryRule> material = catalogue.rulesOf("material.waren");

        assertThat(material).isNotEmpty();
        assertThat(material).anyMatch(rule -> rule.getRuleType() == ExpenseCategoryRule.RuleType.SENDER
                && "obi".equalsIgnoreCase(rule.getPattern()));
        assertThat(rules.findByActiveTrueOrderByPriorityDesc()).hasSizeGreaterThan(200);
    }

    @Test
    @DisplayName("every seeded category can be found by its stable code")
    void codesAreStable() {
        ExpenseCategory category = categories.findByCode("fahrzeug.reparatur").orElseThrow();

        assertThat(category.getDatevSkr03()).isEqualTo("4540");
        assertThat(category.getDatevSkr04()).isEqualTo("6540");
        assertThat(category.nameIn("ru")).contains("Ремонт");
        assertThat(category.getFolderPath()).isEqualTo("/Belege/{year}/Fahrzeug/Reparatur");

        // The vehicle tax is a class 7 account in SKR04, not a running vehicle cost: 7685, not 6510.
        ExpenseCategory vehicleTax = categories.findByCode("fahrzeug.kfz_steuer").orElseThrow();
        assertThat(vehicleTax.getDatevSkr03()).isEqualTo("4510");
        assertThat(vehicleTax.getDatevSkr04()).isEqualTo("7685");
    }
}
