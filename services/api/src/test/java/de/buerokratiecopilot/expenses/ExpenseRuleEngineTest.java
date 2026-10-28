package de.buerokratiecopilot.expenses;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import de.buerokratiecopilot.expenses.ExpenseRuleEngine.Category;
import de.buerokratiecopilot.expenses.ExpenseRuleEngine.Result;
import de.buerokratiecopilot.expenses.ExpenseRuleEngine.Rule;
import de.buerokratiecopilot.expenses.domain.ExpenseCategoryRule.RuleType;

/**
 * The rule engine against a representative catalogue.
 *
 * <p>The cases are the ones the product promises: a Baumarkt receipt is material, a Würth receipt is a tool,
 * a Shell receipt is fuel, an Autowerkstatt receipt is a car repair, and a private withdrawal is recognised
 * as private. They also cover the traps — "OBI" must not match "mobile", a date must not become an amount,
 * and an investment is separated from a consumable by its amount.</p>
 */
class ExpenseRuleEngineTest {

    private final ExpenseRuleEngine engine = new ExpenseRuleEngine();

    // ------------------------------------------------------------------ fixtures

    private static Category category(String code, String parent, boolean business, boolean review, int order) {
        return new Category(code, parent, business, review, order);
    }

    private static List<Category> categories() {
        return List.of(
                category("anlagevermoegen.maschinen", "anlagevermoegen", true, false, 10),
                category("anlagevermoegen.it_hardware", "anlagevermoegen", true, false, 50),
                category("anlagevermoegen.gwg", "anlagevermoegen", true, false, 80),
                category("material.waren", "material", true, false, 10),
                category("material.rohstoffe", "material", true, false, 20),
                category("material.hilfsstoffe", "material", true, false, 30),
                category("werkzeuge.handwerkzeug", "werkzeuge", true, false, 10),
                category("werkzeuge.elektrowerkzeuge", "werkzeuge", true, false, 20),
                category("werkzeuge.verbrauchsmaterial_werkzeug", "werkzeuge", true, false, 50),
                category("fahrzeug.kraftstoff", "fahrzeug", true, false, 10),
                category("fahrzeug.reparatur", "fahrzeug", true, false, 30),
                category("fahrzeug.versicherung", "fahrzeug", true, false, 50),
                category("fahrzeug.parken", "fahrzeug", true, false, 110),
                category("betriebsausgaben.miete", "betriebsausgaben", true, false, 10),
                category("betriebsausgaben.versicherungen", "betriebsausgaben", true, false, 80),
                category("betriebsausgaben.buero", "betriebsausgaben", true, false, 100),
                category("betriebsausgaben.telefon", "betriebsausgaben", true, false, 110),
                category("betriebsausgaben.internet", "betriebsausgaben", true, false, 120),
                category("betriebsausgaben.reisekosten", "betriebsausgaben", true, false, 140),
                category("betriebsausgaben.uebernachtung", "betriebsausgaben", true, false, 150),
                category("betriebsausgaben.flug", "betriebsausgaben", true, false, 180),
                category("betriebsausgaben.fortbildung", "betriebsausgaben", true, false, 190),
                category("betriebsausgaben.marketing", "betriebsausgaben", true, false, 200),
                category("betriebsausgaben.beitraege", "betriebsausgaben", true, false, 270),
                category("betriebsausgaben.bankgebuehren", "betriebsausgaben", true, false, 290),
                category("betriebsausgaben.bewirtung", "betriebsausgaben", true, false, 320),
                category("betriebsausgaben.geschenke", "betriebsausgaben", true, false, 330),
                category("betriebsausgaben.arbeitskleidung", "betriebsausgaben", true, false, 340),
                category("betriebsausgaben.arbeitssicherheit", "betriebsausgaben", true, false, 350),
                category("betriebsausgaben.entsorgung", "betriebsausgaben", true, false, 360),
                category("betriebsausgaben.reinigung", "betriebsausgaben", true, false, 370),
                category("betriebsausgaben.reparatur_instandhaltung", "betriebsausgaben", true, false, 380),
                category("betriebsausgaben.instandhaltung_gebaeude", "betriebsausgaben", true, false, 390),
                category("loehne.gehaelter", "loehne", true, false, 10),
                category("loehne.minijob", "loehne", true, false, 40),
                category("steuern.umsatzsteuer", "steuern", true, false, 10),
                category("steuern.gewerbesteuer", "steuern", true, false, 20),
                category("privatentnahme.geld", "privatentnahme", false, true, 10),
                category("privatentnahme.steuern", "privatentnahme", false, true, 70),
                category("privatentnahme.lebenshaltung", "privatentnahme", false, true, 80));
    }

    private static void keyword(List<Rule> rules, String code, String pattern, double weight) {
        rules.add(new Rule(code, RuleType.KEYWORD, pattern, null, null, weight, 100, List.of()));
    }

    private static void sender(List<Rule> rules, String code, String pattern, double weight) {
        rules.add(new Rule(code, RuleType.SENDER, pattern, null, null, weight, 200, List.of()));
    }

    private static void filename(List<Rule> rules, String code, String pattern, double weight) {
        rules.add(new Rule(code, RuleType.FILENAME, pattern, null, null, weight, 100, List.of()));
    }

    private static void amountAtLeast(List<Rule> rules, String code, String amount, double weight) {
        rules.add(new Rule(code, RuleType.AMOUNT_AT_LEAST, null, new BigDecimal(amount), null, weight, 40,
                List.of()));
    }

    private static void amountBetween(List<Rule> rules, String code, String min, String max, double weight) {
        rules.add(new Rule(code, RuleType.AMOUNT_BETWEEN, null, new BigDecimal(min), new BigDecimal(max),
                weight, 40, List.of()));
    }

    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();
        // Anlagevermögen
        keyword(rules, "anlagevermoegen.maschinen", "maschine", 0.6);
        keyword(rules, "anlagevermoegen.maschinen", "maschinen", 0.6);
        keyword(rules, "anlagevermoegen.maschinen", "investition", 0.4);
        keyword(rules, "anlagevermoegen.maschinen", "станок", 0.8);
        amountAtLeast(rules, "anlagevermoegen.maschinen", "800", 0.3);
        keyword(rules, "anlagevermoegen.it_hardware", "laptop", 0.8);
        keyword(rules, "anlagevermoegen.it_hardware", "notebook", 0.75);
        keyword(rules, "anlagevermoegen.it_hardware", "hardware", 0.7);
        amountAtLeast(rules, "anlagevermoegen.it_hardware", "800", 0.25);
        keyword(rules, "anlagevermoegen.gwg", "geringwertiges wirtschaftsgut", 0.95);
        amountBetween(rules, "anlagevermoegen.gwg", "250", "800", 0.25);

        // Material
        keyword(rules, "material.waren", "baumarkt", 0.8);
        keyword(rules, "material.waren", "zement", 0.8);
        keyword(rules, "material.waren", "baustoff", 0.8);
        sender(rules, "material.waren", "obi", 0.85);
        sender(rules, "material.waren", "bauhaus", 0.85);
        keyword(rules, "material.rohstoffe", "stahl", 0.6);
        keyword(rules, "material.rohstoffe", "profil", 0.4);
        keyword(rules, "material.rohstoffe", "rohstoff", 0.9);
        keyword(rules, "material.hilfsstoffe", "schraube", 0.65);
        keyword(rules, "material.hilfsstoffe", "dübel", 0.7);
        keyword(rules, "material.hilfsstoffe", "hilfsstoff", 0.9);

        // Werkzeuge
        keyword(rules, "werkzeuge.handwerkzeug", "schraubenset", 0.7);
        keyword(rules, "werkzeuge.handwerkzeug", "handwerkzeug", 0.95);
        sender(rules, "werkzeuge.handwerkzeug", "würth", 0.9);
        filename(rules, "werkzeuge.handwerkzeug", "werkzeug", 0.4);
        keyword(rules, "werkzeuge.elektrowerkzeuge", "akkuschrauber", 0.95);
        keyword(rules, "werkzeuge.elektrowerkzeuge", "bohrmaschine", 0.85);
        sender(rules, "werkzeuge.elektrowerkzeuge", "makita", 0.95);
        keyword(rules, "werkzeuge.verbrauchsmaterial_werkzeug", "trennscheibe", 0.9);
        keyword(rules, "werkzeuge.verbrauchsmaterial_werkzeug", "sägeblatt", 0.9);

        // Fahrzeug
        keyword(rules, "fahrzeug.kraftstoff", "diesel", 0.9);
        keyword(rules, "fahrzeug.kraftstoff", "benzin", 0.9);
        keyword(rules, "fahrzeug.kraftstoff", "tankstelle", 0.9);
        keyword(rules, "fahrzeug.kraftstoff", "super e10", 0.9);
        sender(rules, "fahrzeug.kraftstoff", "shell", 0.95);
        sender(rules, "fahrzeug.kraftstoff", "aral", 0.95);
        keyword(rules, "fahrzeug.reparatur", "kfz-reparatur", 0.95);
        keyword(rules, "fahrzeug.reparatur", "werkstattrechnung", 0.9);
        keyword(rules, "fahrzeug.reparatur", "reparatur auto", 0.9);
        sender(rules, "fahrzeug.reparatur", "atu", 0.95);
        sender(rules, "fahrzeug.reparatur", "autohaus", 0.5);
        keyword(rules, "fahrzeug.versicherung", "kfz-versicherung", 0.95);
        sender(rules, "fahrzeug.versicherung", "huk", 0.85);
        keyword(rules, "fahrzeug.parken", "parkhaus", 0.9);
        keyword(rules, "fahrzeug.parken", "parkgebühr", 0.9);

        // Betriebsausgaben
        keyword(rules, "betriebsausgaben.miete", "miete", 0.5);
        keyword(rules, "betriebsausgaben.miete", "geschäftsraummiete", 0.95);
        sender(rules, "betriebsausgaben.miete", "hausverwaltung", 0.85);
        keyword(rules, "betriebsausgaben.versicherungen", "versicherung", 0.6);
        keyword(rules, "betriebsausgaben.versicherungen", "betriebshaftpflicht", 0.95);
        keyword(rules, "betriebsausgaben.buero", "bürobedarf", 0.95);
        keyword(rules, "betriebsausgaben.buero", "büromaterial", 0.95);
        keyword(rules, "betriebsausgaben.buero", "toner", 0.8);
        keyword(rules, "betriebsausgaben.telefon", "telefonrechnung", 0.95);
        keyword(rules, "betriebsausgaben.telefon", "mobilfunk", 0.9);
        sender(rules, "betriebsausgaben.telefon", "telekom", 0.85);
        sender(rules, "betriebsausgaben.telefon", "vodafone", 0.85);
        sender(rules, "betriebsausgaben.telefon", "1&1", 0.85);
        keyword(rules, "betriebsausgaben.internet", "dsl", 0.8);
        keyword(rules, "betriebsausgaben.internet", "internet", 0.75);
        keyword(rules, "betriebsausgaben.internet", "router", 0.6);
        sender(rules, "betriebsausgaben.internet", "1&1", 0.7);
        keyword(rules, "betriebsausgaben.reisekosten", "reisekosten", 0.9);
        keyword(rules, "betriebsausgaben.reisekosten", "dienstreise", 0.9);
        sender(rules, "betriebsausgaben.reisekosten", "booking.com", 0.4);
        sender(rules, "betriebsausgaben.reisekosten", "lufthansa", 0.4);
        keyword(rules, "betriebsausgaben.uebernachtung", "übernachtung", 0.9);
        keyword(rules, "betriebsausgaben.uebernachtung", "hotel", 0.7);
        sender(rules, "betriebsausgaben.uebernachtung", "booking.com", 0.9);
        keyword(rules, "betriebsausgaben.flug", "flugticket", 0.95);
        keyword(rules, "betriebsausgaben.flug", "flug", 0.7);
        sender(rules, "betriebsausgaben.flug", "lufthansa", 0.9);
        keyword(rules, "betriebsausgaben.fortbildung", "fortbildung", 0.9);
        keyword(rules, "betriebsausgaben.fortbildung", "seminar", 0.8);
        sender(rules, "betriebsausgaben.fortbildung", "ihk", 0.8);
        sender(rules, "betriebsausgaben.beitraege", "ihk", 0.9);
        keyword(rules, "betriebsausgaben.marketing", "werbung", 0.85);
        keyword(rules, "betriebsausgaben.marketing", "google ads", 0.9);
        sender(rules, "betriebsausgaben.marketing", "google", 0.85);
        keyword(rules, "betriebsausgaben.bankgebuehren", "kontoführung", 0.9);
        keyword(rules, "betriebsausgaben.bankgebuehren", "bankgebühr", 0.95);
        sender(rules, "betriebsausgaben.bankgebuehren", "sparkasse", 0.8);
        keyword(rules, "betriebsausgaben.bewirtung", "bewirtungskosten", 0.95);
        keyword(rules, "betriebsausgaben.bewirtung", "geschäftsessen", 0.9);
        keyword(rules, "betriebsausgaben.geschenke", "kundengeschenk", 0.9);
        keyword(rules, "betriebsausgaben.geschenke", "geschenk", 0.8);
        keyword(rules, "betriebsausgaben.arbeitskleidung", "arbeitskleidung", 0.95);
        keyword(rules, "betriebsausgaben.arbeitskleidung", "sicherheitsschuhe", 0.9);
        keyword(rules, "betriebsausgaben.arbeitssicherheit", "schutzbrille", 0.9);
        keyword(rules, "betriebsausgaben.arbeitssicherheit", "gehörschutz", 0.9);
        keyword(rules, "betriebsausgaben.entsorgung", "entsorgung", 0.9);
        keyword(rules, "betriebsausgaben.entsorgung", "müllabfuhr", 0.9);
        keyword(rules, "betriebsausgaben.reinigung", "gebäudereinigung", 0.95);
        keyword(rules, "betriebsausgaben.reinigung", "reinigung", 0.8);
        keyword(rules, "betriebsausgaben.reparatur_instandhaltung", "reparatur", 0.6);
        keyword(rules, "betriebsausgaben.reparatur_instandhaltung", "instandhaltung", 0.7);
        keyword(rules, "betriebsausgaben.instandhaltung_gebaeude", "dachreparatur", 0.95);
        keyword(rules, "betriebsausgaben.instandhaltung_gebaeude", "malerarbeiten", 0.9);

        // Löhne / Steuern
        keyword(rules, "loehne.gehaelter", "gehalt", 0.7);
        keyword(rules, "loehne.gehaelter", "monatsgehalt", 0.9);
        keyword(rules, "loehne.minijob", "minijob", 0.95);
        keyword(rules, "loehne.minijob", "450 euro job", 0.9);
        keyword(rules, "steuern.umsatzsteuer", "umsatzsteuer", 0.85);
        keyword(rules, "steuern.umsatzsteuer", "ust-voranmeldung", 0.95);
        sender(rules, "steuern.umsatzsteuer", "finanzamt", 0.8);
        keyword(rules, "steuern.gewerbesteuer", "gewerbesteuer", 0.95);

        // Privatentnahme
        keyword(rules, "privatentnahme.geld", "privatentnahme", 0.95);
        keyword(rules, "privatentnahme.steuern", "einkommensteuerbescheid", 0.95);
        sender(rules, "privatentnahme.steuern", "finanzamt", 0.7);
        keyword(rules, "privatentnahme.lebenshaltung", "lebensmittel", 0.7);
        sender(rules, "privatentnahme.lebenshaltung", "rewe", 0.7);
        return rules;
    }

    private Result categorize(String text, String filename, String sender) {
        return engine.categorize(text, filename, sender, categories(), rules());
    }

    private Result categorize(String text) {
        return categorize(text, "beleg.pdf", null);
    }

    // ------------------------------------------------------------------ cases

    record Case(String text, String filename, String sender, String expected) {
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("Baumarkt OBI Schrauben 24,99 €", "beleg.pdf", "OBI", "material.waren"),
                new Case("Bauhaus Zement 39,90 EUR", "beleg.pdf", "Bauhaus", "material.waren"),
                new Case("Würth Schraubenset 89,00 €", "beleg.pdf", "Würth", "werkzeuge.handwerkzeug"),
                new Case("Makita Akkuschrauber 199,00 €", "beleg.pdf", null, "werkzeuge.elektrowerkzeuge"),
                new Case("Shell Tankstelle Diesel 89,50 €", "beleg.pdf", "Shell", "fahrzeug.kraftstoff"),
                new Case("Aral Super E10 61,20 €", "beleg.pdf", "Aral", "fahrzeug.kraftstoff"),
                new Case("Werkstattrechnung Reparatur 450,00 €", "beleg.pdf", "ATU", "fahrzeug.reparatur"),
                new Case("Kfz-Reparatur Bremsen 320,00 €", "rechnung.pdf", null, "fahrzeug.reparatur"),
                new Case("HUK Kfz-Versicherung Beitrag 512,00 €", "beleg.pdf", "HUK", "fahrzeug.versicherung"),
                new Case("Telekom Telefonrechnung Mobilfunk 49,90 €", "beleg.pdf", "Telekom", "betriebsausgaben.telefon"),
                new Case("Vodafone Mobilfunk 39,99 €", "beleg.pdf", "Vodafone", "betriebsausgaben.telefon"),
                new Case("1&1 DSL Router 39,99 €", "beleg.pdf", "1&1", "betriebsausgaben.internet"),
                new Case("Investition Maschinen 12.500,00 €", "beleg.pdf", null, "anlagevermoegen.maschinen"),
                new Case("Laptop 1.200,00 €", "beleg.pdf", null, "anlagevermoegen.it_hardware"),
                new Case("Geringwertiges Wirtschaftsgut 480,00 €", "beleg.pdf", null, "anlagevermoegen.gwg"),
                new Case("Privatentnahme 500,00 €", "beleg.pdf", null, "privatentnahme.geld"),
                new Case("Einkommensteuerbescheid 2025", "beleg.pdf", null, "privatentnahme.steuern"),
                new Case("Bewirtungskosten Geschäftsessen 87,00 €", "beleg.pdf", null, "betriebsausgaben.bewirtung"),
                new Case("Kundengeschenk 45,00 €", "beleg.pdf", null, "betriebsausgaben.geschenke"),
                new Case("Bürobedarf Toner 79,00 €", "beleg.pdf", null, "betriebsausgaben.buero"),
                new Case("Arbeitskleidung Sicherheitsschuhe 129,00 €", "beleg.pdf", null, "betriebsausgaben.arbeitskleidung"),
                new Case("Schutzbrille Gehörschutz 39,00 €", "beleg.pdf", null, "betriebsausgaben.arbeitssicherheit"),
                new Case("Müllabfuhr Entsorgung 120,00 €", "beleg.pdf", null, "betriebsausgaben.entsorgung"),
                new Case("Gebäudereinigung 250,00 €", "beleg.pdf", null, "betriebsausgaben.reinigung"),
                new Case("Dachreparatur 1.800,00 €", "beleg.pdf", null, "betriebsausgaben.instandhaltung_gebaeude"),
                new Case("Miete Gewerberaum 950,00 €", "beleg.pdf", "Hausverwaltung Müller", "betriebsausgaben.miete"),
                new Case("Hotel Übernachtung 129,00 €", "beleg.pdf", "booking.com", "betriebsausgaben.uebernachtung"),
                new Case("Lufthansa Flugticket 249,00 €", "beleg.pdf", "Lufthansa", "betriebsausgaben.flug"),
                new Case("IHK Seminar Fortbildung 390,00 €", "beleg.pdf", "IHK", "betriebsausgaben.fortbildung"),
                new Case("Google Ads Werbung 150,00 €", "beleg.pdf", "Google", "betriebsausgaben.marketing"),
                new Case("Sparkasse Kontoführung 12,90 €", "beleg.pdf", "Sparkasse", "betriebsausgaben.bankgebuehren"),
                new Case("Monatsgehalt Mai 2026", "beleg.pdf", null, "loehne.gehaelter"),
                new Case("Minijob 450 Euro Job", "beleg.pdf", null, "loehne.minijob"),
                new Case("Umsatzsteuer-Voranmeldung", "beleg.pdf", "Finanzamt", "steuern.umsatzsteuer"),
                new Case("REWE Lebensmittel 63,40 €", "beleg.pdf", "REWE", "privatentnahme.lebenshaltung"),
                new Case("Schrauben Dübel 12,50 €", "beleg.pdf", null, "material.hilfsstoffe"),
                new Case("Stahlprofile 340,00 €", "beleg.pdf", null, "material.rohstoffe"),
                new Case("Trennscheibe 8,90 €", "beleg.pdf", null, "werkzeuge.verbrauchsmaterial_werkzeug"),
                new Case("Parkhaus 4,50 €", "beleg.pdf", null, "fahrzeug.parken"),
                new Case("Dienstreise Reisekosten 210,00 €", "beleg.pdf", null, "betriebsausgaben.reisekosten"),
                new Case("Gewerbesteuer Vorauszahlung", "beleg.pdf", null, "steuern.gewerbesteuer"),
                new Case("", "Werkzeug-Würth.pdf", "Würth", "werkzeuge.handwerkzeug"),
                new Case("Telefonrechnung 29,99 €", "beleg.pdf", null, "betriebsausgaben.telefon"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("a receipt reaches the category a bookkeeper would choose")
    void choosesTheCategory(Case testCase) {
        Result result = categorize(testCase.text(), testCase.filename(), testCase.sender());

        assertThat(result.isEmpty()).as("no rule matched: " + testCase.text()).isFalse();
        assertThat(result.best().categoryCode()).isEqualTo(testCase.expected());
    }

    @Test
    @DisplayName("a letter without an expense hint is not categorised at all")
    void unknownTextHasNoCategory() {
        assertThat(categorize("Sehr geehrte Damen und Herren, anbei die Unterlagen.").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a short shop name does not match inside a longer word")
    void shortTermsRespectWordBoundaries() {
        assertThat(ExpenseRuleEngine.containsTerm("mobile telefonie", "obi")).isFalse();
        assertThat(ExpenseRuleEngine.containsTerm("baumarkt obi berlin", "obi")).isTrue();
        assertThat(ExpenseRuleEngine.containsTerm("admin", "dm")).isFalse();
    }

    @Test
    @DisplayName("two hints reinforce each other; one weak hint stays below the threshold")
    void weightsAreCombinedAsProbabilisticOr() {
        assertThat(ExpenseRuleEngine.combine(new double[] {0.6, 0.6}))
                .isCloseTo(0.84, org.assertj.core.data.Offset.offset(0.001));
        assertThat(ExpenseRuleEngine.combine(new double[] {0.2})).isLessThan(0.35);
        assertThat(ExpenseRuleEngine.combine(new double[] {0.9, 0.9, 0.9})).isGreaterThan(0.99);
    }


    @Test
    @DisplayName("a keyword must not match inside another word: Spachtel is not a Pacht")
    void keywordsRespectTheWordStart() {
        assertThat(ExpenseRuleEngine.containsTerm("quadratspachtel 200mm", "pacht")).isFalse();
        assertThat(ExpenseRuleEngine.containsTerm("spachtel edelstahl", "pacht")).isFalse();
        assertThat(ExpenseRuleEngine.containsTerm("pachtzins quartal", "pacht")).isTrue();
        assertThat(ExpenseRuleEngine.containsTerm("pachtvertrag halle 3", "pacht")).isTrue();
        assertThat(ExpenseRuleEngine.containsTerm("die pacht betraegt 500", "pacht")).isTrue();
        assertThat(ExpenseRuleEngine.containsTerm("handwerkzeug koffer", "werkzeug")).isFalse();
        assertThat(ExpenseRuleEngine.containsTerm("werkzeugkoffer", "werkzeug")).isTrue();
    }

    @Test
    @DisplayName("a hardware receipt (POCO) is not filed as a lease")
    void hardwareReceiptIsNotLease() {
        List<Rule> rules = new ArrayList<>();
        keyword(rules, "betriebsausgaben.pacht", "pacht", 0.9);
        keyword(rules, "werkzeuge.verbrauchsmaterial_werkzeug", "farbroller", 0.9);
        keyword(rules, "werkzeuge.verbrauchsmaterial_werkzeug", "spachtel", 0.85);
        String receipt = """
                POCO Einrichtungsmaerkte GmbH
                Ronden-Ruhrstab 85x100mm 4,49 A
                Farb-Ersatzwalzen 10cm 2,99 B
                Quadratspachtel 200mm 4,99 A
                Farbroller 25cm Orangefaden 6,79 A
                Spachtel Edelstahl 40mm 1,99 A
                Flachkreppband 48mm 6,98 B
                Nettobetrag 27,24
                """;
        Result result = engine.categorize(receipt, "docs0006.jpg", null, categories(), rules);

        assertThat(result.best()).isNotNull();
        assertThat(result.best().categoryCode()).isEqualTo("werkzeuge.verbrauchsmaterial_werkzeug");
        assertThat(result.ranked())
                .noneMatch(match -> match.categoryCode().equals("betriebsausgaben.pacht"));
    }

    @Test
    @DisplayName("a rule that requires further terms only counts when all of them are present")
    void requiresAllIsHonoured() {
        Rule guarded = new Rule("material.waren", RuleType.KEYWORD, "lieferung", null, null, 0.8, 100,
                List.of("baustelle"));
        Rule guard = new Rule("material.waren", RuleType.KEYWORD, "baustelle", null, null, 0.5, 100, List.of());
        Result without = engine.categorize("Lieferung 100,00 €", "beleg.pdf", null, categories(),
                List.of(guarded));
        Result with = engine.categorize("Lieferung 100,00 € auf die Baustelle", "beleg.pdf", null,
                categories(), List.of(guarded, guard));

        assertThat(without.isEmpty()).isTrue();
        assertThat(with.best().score()).isGreaterThan(0.5);
    }
}
