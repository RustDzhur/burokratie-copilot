package de.buerokratiecopilot.accounts;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which modules an account type may use (spec §3.1, H-001).
 *
 * <p>The specification says that the account type determines the available modules, settings, roles and
 * restrictions — and it does <strong>not</strong> say which modules each type gets. That gap is the whole
 * design problem here: a mapping invented by this class would become a rule nobody agreed to, and it
 * would be wrong for somebody on the first day.</p>
 *
 * <p>So the catalogue does two separate things.</p>
 *
 * <p><strong>It names the modules.</strong> That is a fact about the software: these are the parts the
 * platform has, and a module that does not exist cannot be granted.</p>
 *
 * <p><strong>It offers a default per account type, which is configuration and not law.</strong> The
 * defaults follow from what the type plainly means — a private person does not run a company's accounts
 * payable, and a company does not keep a household's insurance policies — and every one of them can be
 * changed per tenant. The class is named a catalogue rather than a rule set for that reason.</p>
 *
 * <p>What is not negotiable is the other half of the acceptance criterion: the check happens on the
 * backend. A module that is not available is refused by the API, and the navigation simply reflects what
 * the API would already refuse — it is not what enforces it.</p>
 */
public final class ModuleCatalogue {

    /** The modules the platform has. */
    public enum Module {
        DOCUMENTS("Dokumente"),
        CASES("Vorgänge"),
        TASKS("Aufgaben"),
        CALENDAR("Kalender und Fristen"),
        MAIL("Postfach"),
        CONTRACTS("Verträge"),
        FINANCE("Finanzen"),
        ACCOUNTING_EXPORT("Export für die Buchhaltung"),
        DEVICES("Geräte und Scanner"),
        REPORTS("Auswertungen"),
        ADMINISTRATION("Verwaltung"),
        SECURITY("Erhöhte Sicherheitsanforderungen");

        private final String label;

        Module(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * The modules that follow from what an account type plainly means, as a starting point.
     *
     * <p>Deliberately conservative in one direction only: everything that is clearly part of the type is
     * in, and nothing is added because it might be useful. An operator adds what they need; the platform
     * does not decide on their behalf.</p>
     */
    private static final Map<String, Set<Module>> DEFAULTS = defaults();

    private static Map<String, Set<Module>> defaults() {
        Map<String, Set<Module>> defaults = new LinkedHashMap<>();
        // Everyday correspondence: documents, cases, deadlines, the mailbox.
        Set<Module> personal = Set.of(Module.DOCUMENTS, Module.CASES, Module.TASKS, Module.CALENDAR,
                Module.MAIL, Module.CONTRACTS, Module.REPORTS);
        defaults.put("PRIVATE_PERSON", personal);
        // A household also keeps what is shared, and keeps it apart from what is not.
        defaults.put("FAMILY_HOUSEHOLD", Set.of(Module.DOCUMENTS, Module.CASES, Module.TASKS,
                Module.CALENDAR, Module.MAIL, Module.CONTRACTS, Module.REPORTS));
        // One person who invoices: the personal set plus their own books.
        defaults.put("FREELANCER", Set.of(Module.DOCUMENTS, Module.CASES, Module.TASKS, Module.CALENDAR,
                Module.MAIL, Module.CONTRACTS, Module.FINANCE, Module.ACCOUNTING_EXPORT, Module.REPORTS));
        Set<Module> company = Set.of(Module.DOCUMENTS, Module.CASES, Module.TASKS, Module.CALENDAR,
                Module.MAIL, Module.CONTRACTS, Module.FINANCE, Module.ACCOUNTING_EXPORT, Module.DEVICES,
                Module.REPORTS);
        defaults.put("COMPANY", company);
        // A team works inside an organisation; the administration of it stays with the organisation.
        defaults.put("COMPANY_TEAM", Set.of(Module.DOCUMENTS, Module.CASES, Module.TASKS, Module.CALENDAR,
                Module.MAIL, Module.CONTRACTS, Module.DEVICES, Module.REPORTS));
        // Somebody acting for a client, so the case work and no books of their own.
        defaults.put("EXTERNAL_REPRESENTATIVE", Set.of(Module.DOCUMENTS, Module.CASES, Module.TASKS,
                Module.CALENDAR, Module.MAIL, Module.CONTRACTS, Module.REPORTS));
        // Everything, plus the module that adds the stricter requirements.
        defaults.put("HIGH_SECURITY_ORGANIZATION", Set.of(Module.DOCUMENTS, Module.CASES, Module.TASKS,
                Module.CALENDAR, Module.MAIL, Module.CONTRACTS, Module.FINANCE, Module.ACCOUNTING_EXPORT,
                Module.DEVICES, Module.REPORTS, Module.ADMINISTRATION, Module.SECURITY));
        return Map.copyOf(defaults);
    }

    private ModuleCatalogue() {
    }

    /** The account types the schema knows. */
    public static List<String> accountTypes() {
        return List.of("PRIVATE_PERSON", "FAMILY_HOUSEHOLD", "FREELANCER", "COMPANY", "COMPANY_TEAM",
                "EXTERNAL_REPRESENTATIVE", "HIGH_SECURITY_ORGANIZATION");
    }

    /** The modules of an account type without any tenant-specific change. */
    public static Set<Module> defaultsFor(String accountType) {
        if (accountType == null) {
            return Set.of();
        }
        return DEFAULTS.getOrDefault(accountType.toUpperCase(java.util.Locale.ROOT), Set.of());
    }

    /** Whether an account type exists at all. */
    public static boolean isKnownAccountType(String accountType) {
        return accountType != null
                && accountTypes().contains(accountType.toUpperCase(java.util.Locale.ROOT));
    }

    /** A module by its key, or {@code null} when the platform has no such module. */
    public static Module byKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String name = key.strip().toUpperCase(java.util.Locale.ROOT);
        if (name.startsWith("MODULE.")) {
            name = name.substring("MODULE.".length());
        }
        try {
            return Module.valueOf(name);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** The key of a module as configuration and storage use it. */
    public static String keyOf(Module module) {
        return "module." + module.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Every module with its label, for a navigation that reflects what the API allows. */
    public static Map<String, String> allModules() {
        Map<String, String> modules = new LinkedHashMap<>();
        for (Module module : Module.values()) {
            modules.put(keyOf(module), module.label());
        }
        return modules;
    }

    /** The defaults, for an operator asking what the platform starts from. */
    public static Map<String, List<String>> describeDefaults() {
        Map<String, List<String>> described = new LinkedHashMap<>();
        DEFAULTS.forEach((type, modules) -> described.put(type,
                modules.stream().map(ModuleCatalogue::keyOf).sorted().toList()));
        return described;
    }

    /** The account types that share a module, for an operator asking who gets what. */
    public static Set<String> accountTypesWith(Module module) {
        Set<String> types = new LinkedHashSet<>();
        DEFAULTS.forEach((type, modules) -> {
            if (modules.contains(module)) {
                types.add(type);
            }
        });
        return types;
    }
}
