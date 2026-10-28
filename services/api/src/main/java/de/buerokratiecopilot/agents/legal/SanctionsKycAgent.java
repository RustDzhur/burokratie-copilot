package de.buerokratiecopilot.agents.legal;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentWorkspace;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code legal-sanctions-kyc} — Sanktions- und KYC-Prüfung.
 *
 * <p>Compares a business partner with the sanctions/PEP entries supplied as JSON using normalised
 * name similarity and country. A hit above the threshold is a <em>review hint</em>, never a block:
 * the agent files a KYC review task and a report, and the responsible person decides. Without a
 * supplied list the run is an honest skip — there is no list in the pilot to check against.</p>
 */
@Component
public class SanctionsKycAgent implements Agent {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentWorkspace workspace;

    public SanctionsKycAgent(JdbcTemplate jdbc, ObjectMapper mapper, AgentWorkspace workspace) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.workspace = workspace;
    }

    @Override
    public String id() {
        return "legal-sanctions-kyc";
    }

    @Override
    public AgentResult run(AgentContext context) {
        UUID contactId = AgentInputs.uuid(context.input("contactId"));
        if (contactId == null) {
            return AgentResult.skipped("Sanktions-/KYC-Prüfung: 'contactId' fehlt oder ist keine "
                    + "gültige UUID.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select c.id, c.display_name, c.organization_name, c.first_name, c.last_name,
                       ca.country_code
                from contacts c
                left join contact_addresses ca on ca.contact_id = c.id and ca.is_primary
                where c.tenant_id = ? and c.id = ? and c.deleted_at is null
                limit 1
                """, context.tenantId(), contactId);
        if (rows.isEmpty()) {
            return AgentResult.skipped("Sanktions-/KYC-Prüfung: Kontakt " + contactId
                    + " ist im Mandanten nicht vorhanden.");
        }
        Map<String, Object> contact = rows.getFirst();
        String name = AgentInputs.blankTo(context.input("name"), nameOf(contact));
        String country = AgentInputs.blankTo(context.input("country"), text(contact.get("country_code")));
        String rawCandidates = context.input("candidates");
        if (rawCandidates == null || rawCandidates.isBlank()) {
            return AgentResult.skipped("Sanktions-/KYC-Prüfung: keine Sanktions-/PEP-Liste übergeben "
                    + "('candidates'). Ohne Listeneinträge ist kein Abgleich möglich.");
        }
        List<Map<String, Object>> candidateRows;
        try {
            candidateRows = mapper.readValue(rawCandidates, new TypeReference<List<Map<String, Object>>>() {
            });
        }
        catch (Exception ex) {
            return AgentResult.failed("Sanktions-/KYC-Prüfung: 'candidates' ist kein gültiges "
                    + "JSON-Array.");
        }
        double threshold = AgentInputs.decimal(context.input("threshold")) == null ? 0.85
                : AgentInputs.decimal(context.input("threshold")).doubleValue();
        if (threshold <= 0 || threshold > 1) {
            threshold = 0.85;
        }
        List<Hit> hits = new ArrayList<>();
        for (Map<String, Object> candidate : candidateRows) {
            String candidateName = text(candidate.get("name"));
            if (candidateName == null) {
                continue;
            }
            double score = similarity(name, candidateName);
            String candidateCountry = text(candidate.get("country"));
            if (country != null && candidateCountry != null) {
                if (country.equalsIgnoreCase(candidateCountry)) {
                    score = Math.min(1.0, score + 0.05);
                }
                else {
                    score = Math.max(0.0, score - 0.10);
                }
            }
            if (score >= threshold) {
                hits.add(new Hit(candidateName, candidateCountry,
                        text(candidate.get("list")), text(candidate.get("type")),
                        Math.round(score * 1000) / 1000.0));
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        String sourceKey = "legal-sanctions-kyc:" + contactId + ":" + AgentInputs.key(name,
                country == null ? "" : country, rawCandidates);
        String html = html(contactId, name, country, threshold, hits, candidateRows.size());
        AgentWorkspace.DocumentRef document = workspace.ensureDocument(context, sourceKey, null, null,
                "KYC-Prüfung: " + name, html,
                Map.of("contactId", contactId.toString(), "hits", hits.size(),
                        "threshold", threshold), List.of());
        if (document.created()) {
            context.addProduced("generated_document", document.id().toString(), "KYC-Prüfbericht");
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("contactId", contactId.toString());
        meta.put("checkedEntries", String.valueOf(candidateRows.size()));
        meta.put("hits", String.valueOf(hits.size()));
        meta.put("threshold", String.valueOf(threshold));
        meta.put("blocked", "false");
        if (hits.isEmpty()) {
            return AgentResult.done("Sanktions-/KYC-Prüfung für „" + name + "“: " + candidateRows.size()
                    + " Listeneintrag/Listeneinträge geprüft, kein Treffer über " + threshold
                    + "; keine Sperre.", context.produced(), meta);
        }
        String title = "KYC-Prüfung entscheiden: " + name;
        AgentWorkspace.TaskRef task = workspace.ensureTask(context, sourceKey + ":review", title,
                "Kontakt: " + name + " (" + contactId + ")"
                        + "\nLand: " + (country == null ? "unbekannt" : country)
                        + "\nTreffer:\n- " + String.join("\n- ", hits.stream()
                                .map(h -> h.name() + " / " + h.list() + " / " + h.type()
                                        + " — Ähnlichkeit " + h.score()).toList())
                        + "\n\nDer Treffer ist ein Prüfhinweis, keine automatische Ablehnung. "
                        + "Entscheidung über die Geschäftsbeziehung durch eine verantwortliche Person.",
                Task.Priority.HIGH, context.now().plus(Duration.ofDays(2)),
                List.of("legal", "kyc", "sanctions"),
                Map.of("contactId", contactId.toString(), "hits", hits.size(),
                        "threshold", threshold));
        if (task.created()) {
            context.addProduced("task", task.id().toString(), title);
        }
        boolean notified = workspace.notify(context, context.actorUserId(), "KYC_REVIEW",
                Notification.Category.TASK, Notification.Severity.WARNING, title,
                hits.size() + " Treffer über Schwelle " + threshold, "task", task.id(),
                "legal-sanctions-kyc:" + contactId);
        meta.put("notified", String.valueOf(notified));
        return AgentResult.done("Sanktions-/KYC-Prüfung für „" + name + "“: " + hits.size()
                + " Treffer über Schwelle " + threshold + " — zur menschlichen Entscheidung "
                + "vorgelegt, keine Sperre.", context.produced(), meta);
    }

    /**
     * Name similarity: 60% token overlap, 40% character-level Levenshtein ratio. Normalisation
     * removes legal forms and accents, so "Müller & Söhne GmbH" and "Mueller Soehne" compare fairly.
     * Visible for tests.
     */
    public static double similarity(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        java.util.Set<String> tokensA = new java.util.LinkedHashSet<>(List.of(a.split(" ")));
        java.util.Set<String> tokensB = new java.util.LinkedHashSet<>(List.of(b.split(" ")));
        java.util.Set<String> intersection = new java.util.LinkedHashSet<>(tokensA);
        intersection.retainAll(tokensB);
        java.util.Set<String> union = new java.util.LinkedHashSet<>(tokensA);
        union.addAll(tokensB);
        double jaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
        int distance = levenshtein(a, b);
        double ratio = 1.0 - (double) distance / Math.max(a.length(), b.length());
        return Math.max(0, Math.min(1, jaccard * 0.6 + ratio * 0.4));
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("ß", "ss").replace("&", " und ");
        value = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\b(gmbh|ag|kg|ug|ohg|gbr|e\\.v\\.|ev|mbh|se|co)\\b", " ")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
        return value;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static String nameOf(Map<String, Object> contact) {
        String organization = text(contact.get("organization_name"));
        if (organization != null) {
            return organization;
        }
        String display = text(contact.get("display_name"));
        if (display != null) {
            return display;
        }
        return (text(contact.get("first_name")) + " " + text(contact.get("last_name"))).strip();
    }

    private static String html(UUID contactId, String name, String country, double threshold,
                               List<Hit> hits, int checked) {
        StringBuilder html = new StringBuilder("<h1>Sanktions-/KYC-Prüfung</h1>")
                .append("<p>Kontakt: ").append(AgentInputs.html(name)).append(" (")
                .append(contactId).append(")<br>Land: ")
                .append(AgentInputs.html(country == null ? "unbekannt" : country))
                .append("<br>Geprüfte Einträge: ").append(checked)
                .append("<br>Schwelle: ").append(threshold).append("</p>");
        if (hits.isEmpty()) {
            html.append("<p>Kein Treffer über der Schwelle. Das ist keine Freigabe der "
                    + "Geschäftsbeziehung.</p>");
        }
        else {
            html.append("<table><tr><th>Treffer</th><th>Liste</th><th>Typ</th><th>Land</th>"
                    + "<th>Ähnlichkeit</th></tr>");
            for (Hit hit : hits) {
                html.append("<tr><td>").append(AgentInputs.html(hit.name())).append("</td><td>")
                        .append(AgentInputs.html(hit.list())).append("</td><td>")
                        .append(AgentInputs.html(hit.type())).append("</td><td>")
                        .append(AgentInputs.html(hit.country())).append("</td><td>")
                        .append(hit.score()).append("</td></tr>");
            }
            html.append("</table>");
        }
        html.append("<p><em>Treffer sind Prüfhinweise, keine automatische Ablehnung. Die "
                + "Entscheidung über die Geschäftsbeziehung trifft eine verantwortliche Person; "
                + "der Agent sperrt nichts und nutzt keine externen Listen.</em></p>");
        return html.toString();
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value).strip();
    }

    /** One possible list match. */
    public record Hit(String name, String country, String list, String type, double score) {
    }
}
