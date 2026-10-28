package de.buerokratiecopilot.agents.knowledge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code knowledge-policy-digest} — Richtlinien-Zusammenfassung.
 *
 * <p>Takes the policy documents named by the caller, writes a short summary with the roles and
 * deadlines found in the text, and prepares one acknowledgement task per policy. The summary is an
 * extract of the document, never a paraphrase invented by the agent; the payload says that the
 * original remains binding.</p>
 *
 * <p>An optional previous version can be named by document id. When it is available, the words that
 * appear only in the new text and only in the old one are listed — a mechanical change hint, not a
 * legal comparison. When it is missing, that is reported as a gap rather than silently omitted.</p>
 */
@Component
public class PolicyDigestAgent implements Agent {

    static final String ARTIFACT_KIND = "policy-digest";
    static final int MAX_POLICIES = 50;
    static final int SUMMARY_LENGTH = 400;
    private static final int MAX_COMPARISON_WORDS = 12;

    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern GERMAN_DATE =
            Pattern.compile("\\b(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})\\b");
    private static final Pattern WORD = Pattern.compile("[A-Za-zÄÖÜäöüß]{5,}");

    private final DocumentRepository documents;
    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public PolicyDigestAgent(DocumentRepository documents, AgentTaskFactory tasks,
                             AgentArtifactStore artifacts) {
        this.documents = documents;
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "knowledge-policy-digest";
    }

    /** The change hint between two texts: words only in the new and only in the old version. */
    public record Change(List<String> added, List<String> removed) {
    }

    @Override
    public AgentResult run(AgentContext context) {
        List<UUID> policyIds = new ArrayList<>();
        for (String rawId : AgentInputs.list(context.input("policyIds"))) {
            UUID id = AgentInputs.uuid(rawId);
            if (id == null) {
                return AgentResult.refused("INVALID_INPUT",
                        "policyIds enthält keine gültige UUID: " + rawId);
            }
            if (!policyIds.contains(id)) {
                policyIds.add(id);
            }
        }
        if (policyIds.isEmpty()) {
            return AgentResult.refused("NO_INPUT",
                    "Es wurden keine Richtlinien angegeben (policyIds).");
        }
        if (policyIds.size() > MAX_POLICIES) {
            return AgentResult.refused("TOO_MANY_IDS",
                    "Es können höchstens " + MAX_POLICIES + " Richtlinien auf einmal zusammengefasst "
                            + "werden.");
        }

        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);
        String language = AgentInputs.text(context.input("language"));
        if (language == null) {
            language = "de";
        }
        List<String> targetRoles = AgentInputs.distinct(
                AgentInputs.list(context.input("targetRoles")), 30);

        List<Document> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (UUID id : policyIds) {
            Document document = documents.findByIdAndTenantIdAndDeletedAtIsNull(id,
                    context.tenantId()).orElse(null);
            if (document == null) {
                missing.add(id.toString());
            }
            else {
                found.add(document);
            }
        }
        if (found.isEmpty()) {
            return AgentResult.refused("NOT_FOUND",
                    "Keine der angegebenen Richtlinien wurde gefunden (" + missing.size()
                            + " unbekannt).");
        }

        List<Map<String, Object>> policies = new ArrayList<>();
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (String id : missing) {
            gaps.add(gap("POLICY_NOT_FOUND", "Richtlinie " + id + " wurde nicht gefunden."));
        }

        String concatenated = "";
        for (Document document : found) {
            String summary = summarise(document);
            List<String> deadlines = deadlines(today, document.getExtractedText());
            List<String> missingFields = new ArrayList<>();
            if (summary == null) {
                missingFields.add("kein Text");
            }
            if (document.getDocumentDate() == null) {
                missingFields.add("kein Dokumentdatum");
            }
            if (deadlines.isEmpty()) {
                missingFields.add("keine Frist erkannt");
            }
            if (!missingFields.isEmpty()) {
                gaps.add(gap("POLICY_INCOMPLETE", titleOf(document) + ": "
                        + String.join(", ", missingFields) + "."));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("documentId", document.getId().toString());
            entry.put("title", titleOf(document));
            entry.put("documentNumber", document.getDocumentNumber());
            entry.put("documentDate", document.getDocumentDate() == null ? null
                    : document.getDocumentDate().toString());
            entry.put("summary", summary == null ? "" : summary);
            entry.put("deadlines", deadlines);
            entry.put("roles", targetRoles);
            policies.add(entry);
            concatenated = concatenated + " " + nullToEmpty(document.getExtractedText());
        }

        if (!"de".equalsIgnoreCase(language)) {
            gaps.add(gap("NOT_TRANSLATED",
                    "Die Zusammenfassung liegt in der Sprache des Dokuments vor; es wurde nicht "
                            + "übersetzt (gewünscht: " + language + ")."));
        }

        Map<String, Object> comparison = new LinkedHashMap<>();
        String previousRaw = AgentInputs.text(context.input("previousVersion"));
        if (previousRaw == null) {
            comparison.put("previousVersion", null);
            comparison.put("available", false);
            comparison.put("note", "Keine Vorfassung angegeben; kein Änderungsabgleich möglich.");
            gaps.add(gap("NO_PREVIOUS_VERSION",
                    "Ohne Vorfassung ist kein Änderungsabgleich möglich."));
        }
        else {
            UUID previousId = AgentInputs.uuid(previousRaw);
            Document previous = previousId == null ? null
                    : documents.findByIdAndTenantIdAndDeletedAtIsNull(previousId,
                            context.tenantId()).orElse(null);
            comparison.put("previousVersion", previousRaw);
            comparison.put("available", previous != null);
            if (previous == null) {
                comparison.put("note", "Die Vorfassung konnte nicht geladen werden.");
                gaps.add(gap("PREVIOUS_NOT_FOUND",
                        "Die Vorfassung " + previousRaw + " wurde nicht gefunden."));
            }
            else {
                Change change = compare(nullToEmpty(previous.getExtractedText()), concatenated);
                comparison.put("previousDocumentId", previous.getId().toString());
                comparison.put("added", change.added());
                comparison.put("removed", change.removed());
                comparison.put("note", "Mechanischer Wortabgleich, keine juristische Bewertung.");
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("language", language);
        payload.put("targetRoles", targetRoles);
        payload.put("policyCount", found.size());
        payload.put("policies", policies);
        payload.put("comparison", comparison);
        payload.put("gaps", gaps);
        payload.put("note", "Die Zusammenfassung ersetzt nicht das Originaldokument.");

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND,
                policies.stream().map(entry -> String.valueOf(entry.get("documentId")))
                        .sorted().reduce((left, right) -> left + "+" + right).orElse("-"),
                AgentInputs.slug(language, 10),
                previousRaw == null ? null : AgentInputs.slug(previousRaw, 40));
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("policy-digest", artifact.id().toString(),
                "Richtlinien-Zusammenfassung für " + found.size() + " Richtlinie(n)");

        int created = 0;
        for (Document document : found) {
            String title = "Richtlinie zur Kenntnis nehmen: "
                    + AgentInputs.truncate(titleOf(document), 250);
            String summary = summarise(document);
            List<String> documentDeadlines = deadlines(today, document.getExtractedText());
            String description = (summary == null ? "Keine Zusammenfassung im Dokument hinterlegt."
                    : summary) + "\n\nBetroffene Rollen: "
                    + (targetRoles.isEmpty() ? "nicht angegeben" : String.join(", ", targetRoles))
                    + "\nFristen: " + (documentDeadlines.isEmpty() ? "keine erkannt"
                            : String.join(", ", documentDeadlines))
                    + "\nOriginaldokument: " + document.getId();
            AgentTaskFactory.Request request = new AgentTaskFactory.Request(title, description,
                    "Die Kenntnisnahme ist bestätigt (zum Beispiel im Intranet oder per E-Mail).",
                    AgentInputs.dueAt(today, 14), Task.Priority.NORMAL,
                    List.of("richtlinie", "kenntnisnahme"), null, context.actorUserId());
            var optionalTask = tasks.createIfAbsent(context, request);
            if (optionalTask.isPresent()) {
                AgentTaskFactory.produce(context, optionalTask.get());
                created++;
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("policiesRequested", String.valueOf(policyIds.size()));
        meta.put("policiesFound", String.valueOf(found.size()));
        meta.put("gaps", String.valueOf(gaps.size()));
        meta.put("tasksCreated", String.valueOf(created));
        meta.put("language", language);
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Richtlinien-Zusammenfassung für " + found.size()
                + " Richtlinie(n) erstellt, " + gaps.size() + " Lücke(n), " + created
                + " Aufgabe(n) zur Kenntnisnahme. Die Zusammenfassung ersetzt nicht das "
                + "Originaldokument.", context.produced(), meta);
    }

    /** Short extract, whitespace-collapsed; null when the document has no usable text. */
    static String summarise(Document document) {
        String source = document.getSummary();
        if (source == null || source.isBlank()) {
            source = document.getExtractedText();
        }
        if (source == null || source.isBlank()) {
            return null;
        }
        String text = source.replaceAll("\\s+", " ").strip();
        return text.length() <= SUMMARY_LENGTH ? text : text.substring(0, SUMMARY_LENGTH) + " …";
    }

    /** Dates mentioned in the text that are not in the past, ascending, at most three. */
    public static List<String> deadlines(LocalDate today, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<LocalDate> dates = new LinkedHashSet<>();
        Matcher iso = ISO_DATE.matcher(text);
        while (iso.find()) {
            addDate(dates, iso.group(1), iso.group(2), iso.group(3));
        }
        Matcher german = GERMAN_DATE.matcher(text);
        while (german.find()) {
            addDate(dates, german.group(3), german.group(2), german.group(1));
        }
        return dates.stream().filter(date -> !date.isBefore(today)).sorted().limit(3)
                .map(LocalDate::toString).toList();
    }

    private static void addDate(Set<LocalDate> dates, String year, String month, String day) {
        try {
            dates.add(LocalDate.of(Integer.parseInt(year), Integer.parseInt(month),
                    Integer.parseInt(day)));
        }
        catch (RuntimeException ignored) {
            // A number that looks like a date but is not one (32.13.2026) is simply not a date.
        }
    }

    /** Words only in {@code after} and only in {@code before}; mechanical, not semantic. */
    public static Change compare(String before, String after) {
        Set<String> beforeWords = words(before);
        Set<String> afterWords = words(after);
        List<String> added = afterWords.stream().filter(word -> !beforeWords.contains(word))
                .limit(MAX_COMPARISON_WORDS).toList();
        List<String> removed = beforeWords.stream().filter(word -> !afterWords.contains(word))
                .limit(MAX_COMPARISON_WORDS).toList();
        return new Change(added, removed);
    }

    private static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null) {
            return words;
        }
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            words.add(matcher.group().toLowerCase(Locale.GERMAN));
        }
        return words;
    }

    private static String titleOf(Document document) {
        String number = document.getDocumentNumber();
        String filename = document.getOriginalFilename() == null ? "Dokument"
                : document.getOriginalFilename();
        return number == null || number.isBlank() ? filename : number + " – " + filename;
    }

    private static Map<String, Object> gap(String code, String message) {
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("code", code);
        gap.put("message", message);
        return gap;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
