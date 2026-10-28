package de.buerokratiecopilot.agents.marketing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code marketing-sea-campaign} — SEA-Kampagnensteuerung.
 *
 * <p>Builds a reviewable draft campaign from the only keyword sources the platform owns: customer
 * and organisation names plus website domains in the CRM. Budget, keyword set and ad-text variants
 * are deterministic; the agent opens a task for approval.</p>
 *
 * <p>It does not connect to Google Ads, does not read search volume and changes no running campaign.
 * The report names that limitation, so a budget split is not mistaken for a media plan grounded in
 * platform data.</p>
 */
@Component
public class MarketingSeaCampaignAgent implements Agent {

    private static final int MAX_CONTACTS = 500;
    private static final int MAX_KEYWORDS = 20;
    private static final Set<String> STOPWORDS = Set.of(
            "und", "oder", "der", "die", "das", "den", "dem", "des", "ein", "eine", "einer",
            "eines", "für", "fur", "mit", "von", "vom", "bei", "aus", "auf", "zur", "zum",
            "gmbh", "ag", "kg", "ug", "ohg", "gbr", "mbh", "co", "the", "and", "for", "with",
            "www", "com", "de", "net", "org", "eu", "io", "shop", "online", "service", "team",
            "kunden", "kunde", "unternehmen", "firma", "gruppe", "holding", "berlin", "deutschland");

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;

    public MarketingSeaCampaignAgent(JdbcTemplate jdbc, AgentTaskFactory tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @Override
    public String id() {
        return "marketing-sea-campaign";
    }

    /** One keyword candidate with the number of CRM sources that mention it. */
    public record Keyword(String term, int sources) {
    }

    /** One ad-text variant. */
    public record AdVariant(String headline, String description, String callToAction) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(require(context.input("periodFrom")));
            to = LocalDate.parse(require(context.input("periodTo")));
        }
        catch (RuntimeException ex) {
            return AgentResult.skipped("periodFrom und periodTo im Format YYYY-MM-DD sind "
                    + "erforderlich; ohne Zeitraum keine Kampagnenplanung.");
        }
        if (to.isBefore(from)) {
            return AgentResult.failed("Das Ende liegt vor dem Anfang.");
        }
        BigDecimal monthlyBudget = decimal(context.input("monthlyBudget"));
        if (monthlyBudget == null || monthlyBudget.signum() <= 0) {
            return AgentResult.skipped("monthlyBudget fehlt oder ist nicht positiv; ohne Budget "
                    + "lässt sich keine Kampagne planen.");
        }
        BigDecimal targetCpl = decimal(context.input("targetCpl"));
        if (targetCpl != null && targetCpl.signum() <= 0) {
            return AgentResult.failed("targetCpl muss positiv sein.");
        }

        List<Map<String, Object>> contacts = jdbc.queryForList("""
                select display_name, organization_name, website
                from contacts
                where tenant_id = ? and deleted_at is null
                  and (is_customer or organization_name is not null)
                order by display_name limit ?
                """, context.tenantId(), MAX_CONTACTS);
        List<Keyword> keywords = keywords(contacts);
        if (keywords.isEmpty()) {
            return AgentResult.skipped("Keine Keywords aus CRM-Kontakten oder Websites "
                    + "ableitbar; es gibt keine belastbare Grundlage für eine Kampagne.");
        }

        long days = Math.max(1, ChronoUnit.DAYS.between(from, to) + 1);
        BigDecimal dailyBudget = monthlyBudget.divide(BigDecimal.valueOf(days), 2,
                RoundingMode.HALF_UP);
        List<List<Keyword>> groups = partition(keywords, 3);
        List<AdVariant> ads = adVariants(keywords.getFirst().term());

        String title = "SEA-Kampagne prüfen: " + from + " bis " + to;
        String description = description(from, to, monthlyBudget, dailyBudget, targetCpl, keywords,
                groups, ads);
        AgentTaskFactory.Created task = tasks.createIfAbsent(context.tenantId(),
                context.actorUserId(), title, description, Task.Priority.NORMAL,
                "Kampagnenentwurf aus CRM-Keywords; Schaltung und Budgetänderung nur nach "
                        + "menschlicher Freigabe.",
                context.now().plus(5, ChronoUnit.DAYS),
                List.of("marketing", "sea"));

        for (Keyword keyword : keywords.stream().limit(20).toList()) {
            context.addProduced("sea-keyword", keyword.term(),
                    keyword.term() + " (" + keyword.sources() + " Quelle(n))");
        }
        context.addProduced("sea-campaign-draft", from + ":" + to,
                keywords.size() + " Keywords, Tagesbudget " + dailyBudget.toPlainString() + " EUR");
        context.addProduced("task", task.taskId().toString(), title);

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("periodFrom", from.toString());
        meta.put("periodTo", to.toString());
        meta.put("monthlyBudget", monthlyBudget.toPlainString());
        meta.put("dailyBudget", dailyBudget.toPlainString());
        meta.put("targetCpl", targetCpl == null ? "" : targetCpl.toPlainString());
        meta.put("keywords", String.valueOf(keywords.size()));
        meta.put("adVariants", String.valueOf(ads.size()));
        meta.put("taskId", task.taskId().toString());
        meta.put("taskCreated", String.valueOf(task.created()));
        meta.put("maxDailyBudgetChangePercent", "20");
        meta.put("ready", "true");
        meta.put("limitation", "Keine Google-Ads-Anbindung, keine Suchvolumen- oder "
                + "Wettbewerbsdaten; Keywords stammen aus CRM-Namen und Website-Domains. "
                + "Laufende Kampagnen werden nicht geändert.");

        return AgentResult.done("SEA-Kampagnenentwurf für " + from + " bis " + to + ": "
                        + keywords.size() + " Keywords aus dem CRM, Tagesbudget "
                        + dailyBudget.toPlainString() + " EUR, " + ads.size()
                        + " Anzeigenvarianten. Zur Prüfung vorgelegt, nichts geschaltet.",
                context.produced(), meta);
    }

    /**
     * Extracts keyword candidates from CRM rows. Visible for tests: names, organisation names and
     * website domains are tokenised; generic legal forms, TLDs and stopwords are dropped.
     */
    public static List<Keyword> keywords(List<Map<String, Object>> contacts) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> contact : contacts) {
            Set<String> seen = new LinkedHashSet<>();
            addTokens(seen, string(contact.get("organization_name")));
            addTokens(seen, string(contact.get("display_name")));
            addDomainTokens(seen, string(contact.get("website")));
            for (String token : seen) {
                counts.merge(token, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= 1)
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_KEYWORDS)
                .map(entry -> new Keyword(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static void addTokens(Set<String> target, String value) {
        if (value == null) {
            return;
        }
        for (String raw : value.toLowerCase(Locale.GERMAN).split("[^a-zäöüß0-9]+")) {
            String token = raw.strip();
            if (token.length() >= 3 && token.length() <= 30 && !STOPWORDS.contains(token)
                    && !token.matches("\\d+")) {
                target.add(token);
            }
        }
    }

    private static void addDomainTokens(Set<String> target, String website) {
        if (website == null || website.isBlank()) {
            return;
        }
        String domain = website.toLowerCase(Locale.ROOT)
                .replaceFirst("^[a-z]+://", "")
                .replaceFirst("^www\\.", "")
                .replaceAll("/.*$", "")
                .replaceAll(":.*$", "");
        for (String part : domain.split("\\.")) {
            if (part.length() >= 3 && !STOPWORDS.contains(part) && !part.matches("\\d+")) {
                target.add(part);
            }
        }
    }

    /** Splits keywords into at most {@code groups} roughly equal ad groups. */
    private static List<List<Keyword>> partition(List<Keyword> keywords, int groups) {
        List<List<Keyword>> result = new ArrayList<>();
        int size = Math.max(1, (int) Math.ceil(keywords.size() / (double) groups));
        for (int start = 0; start < keywords.size(); start += size) {
            result.add(keywords.subList(start, Math.min(keywords.size(), start + size)));
        }
        return result;
    }

    private static List<AdVariant> adVariants(String keyword) {
        String term = keyword.length() > 22 ? keyword.substring(0, 22) : keyword;
        return List.of(
                new AdVariant(term + " – jetzt anfragen",
                        "Passende Lösung für " + term + ". Jetzt beraten lassen.",
                        "Mehr erfahren"),
                new AdVariant(term + " vom Fachteam",
                        "Ihre Anforderung zu " + term + " – transparent und persönlich.",
                        "Angebot anfordern"),
                new AdVariant("Ihr Thema: " + term,
                        "Wir prüfen Ihren Bedarf zu " + term + " und melden uns.",
                        "Beratung starten"));
    }

    private static String description(LocalDate from, LocalDate to, BigDecimal monthlyBudget,
                                      BigDecimal dailyBudget, BigDecimal targetCpl,
                                      List<Keyword> keywords, List<List<Keyword>> groups,
                                      List<AdVariant> ads) {
        StringBuilder text = new StringBuilder();
        text.append("SEA-Kampagnenentwurf (nicht geschaltet)\n")
                .append("Zeitraum: ").append(from).append(" bis ").append(to).append('\n')
                .append("Monatsbudget: ").append(monthlyBudget.toPlainString()).append(" EUR\n")
                .append("Tagesbudget: ").append(dailyBudget.toPlainString()).append(" EUR\n");
        if (targetCpl != null) {
            text.append("Ziel-CPL: ").append(targetCpl.toPlainString()).append(" EUR\n");
        }
        text.append("\nKeyword-Gruppen:\n");
        int index = 1;
        for (List<Keyword> group : groups) {
            text.append(index++).append(". ")
                    .append(group.stream().map(Keyword::term)
                            .reduce((left, right) -> left + ", " + right).orElse(""))
                    .append('\n');
        }
        text.append("\nAnzeigenvarianten:\n");
        for (AdVariant ad : ads) {
            text.append("- ").append(ad.headline()).append(" | ").append(ad.description())
                    .append(" | ").append(ad.callToAction()).append('\n');
        }
        text.append("\nLimitation: keine Google-Ads-Anbindung, keine Suchvolumina. ")
                .append("Nichts wird automatisch geschaltet; Budgetänderungen nur nach Freigabe.")
                .append('\n');
        return text.toString();
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.strip().replace(',', '.'));
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing");
        }
        return value.strip();
    }

    private static String string(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
