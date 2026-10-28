package de.buerokratiecopilot.agents.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * {@code knowledge-search-answer} — Wissenssuche.
 *
 * <p>Answers an internal question from the documents the account holds, using the German full-text
 * index PostgreSQL maintains on {@code documents.search_vector}. The answer is an extract of the
 * best passage, not a generated text: every sentence the user reads is a quote with the document it
 * came from. That is the only form that can carry a citation.</p>
 *
 * <p>When nothing matches, the agent says so and returns no answer. It never fills the gap with
 * general knowledge — an internal answer without a source is indistinguishable from a rumour.</p>
 */
@Component
public class SearchAnswerAgent implements Agent {

    static final int DEFAULT_MAX_SOURCES = 5;
    static final int MAX_SOURCES = 20;
    private static final int ANSWER_LENGTH = 500;
    private static final int LABEL_LENGTH = 240;

    private final JdbcTemplate jdbc;

    public SearchAnswerAgent(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "knowledge-search-answer";
    }

    /** One documentary source of the answer. */
    public record Source(UUID documentId, String title, String typeCode, String category,
                         double rank, String excerpt) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("documentId", documentId.toString());
            map.put("title", title);
            map.put("typeCode", typeCode);
            map.put("category", category);
            map.put("rank", rank);
            map.put("excerpt", excerpt);
            return map;
        }
    }

    @Override
    public AgentResult run(AgentContext context) {
        String question = AgentInputs.text(context.input("question"));
        if (question == null) {
            return AgentResult.refused("NO_INPUT",
                    "Ohne Frage kann die Wissenssuche nichts belegen.");
        }
        int maxSources = AgentInputs.integer(context.input("maxSources"), DEFAULT_MAX_SOURCES, 1,
                MAX_SOURCES);
        String scope = AgentInputs.text(context.input("scope"));
        String language = AgentInputs.text(context.input("language"));
        if (language == null) {
            language = "de";
        }

        List<Map<String, Object>> rows;
        try {
            rows = search(context.tenantId(), question, scope, maxSources);
        }
        catch (RuntimeException ex) {
            return AgentResult.refused("SEARCH_UNAVAILABLE",
                    "Die Volltextsuche ist nicht verfügbar: " + ex.getMessage());
        }
        if (rows.isEmpty()) {
            return AgentResult.refused("NO_SOURCE",
                    "Zu \"" + question + "\" wurde keine belegte Fundstelle in den Dokumenten "
                            + "gefunden. Die Frage kann ohne Quelle nicht beantwortet werden.",
                    Map.of("question", question, "scope", scope == null ? "" : scope));
        }

        List<Source> sources = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            sources.add(toSource(row));
        }
        String topExcerpt = sources.getFirst().excerpt();
        String answer = topExcerpt == null || topExcerpt.isBlank()
                ? "Die beste Fundstelle enthält keinen auswertbaren Textauszug."
                : AgentInputs.truncate(topExcerpt, ANSWER_LENGTH);
        String confidence = confidence(sources.size(), sources.getFirst().rank());

        for (Source source : sources) {
            context.addProduced("citation", source.documentId().toString(),
                    source.title() + " — " + AgentInputs.truncate(source.excerpt() == null
                            ? "kein Auszug" : source.excerpt(), LABEL_LENGTH));
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("question", question);
        meta.put("sources", String.valueOf(sources.size()));
        meta.put("confidence", confidence);
        meta.put("scope", scope == null ? "" : scope);
        meta.put("language", language);
        meta.put("maxSources", String.valueOf(maxSources));
        meta.put("answerLength", String.valueOf(answer.length()));

        String sourceLabel = sources.size() == 1 ? "Quelle" : "Quellen";
        String prefix = "HIGH".equals(confidence) || "MEDIUM".equals(confidence)
                ? "Belegte Antwort aus " : "Nur schwache Fundstelle, belegt durch ";
        return AgentResult.done(prefix + sources.size() + " " + sourceLabel + " (Sicherheit "
                + confidence + "): " + answer, context.produced(), meta);
    }

    private List<Map<String, Object>> search(UUID tenantId, String question, String scope,
                                             int maxSources) {
        return jdbc.queryForList("""
                select d.id, d.original_filename, d.document_number, d.summary,
                       dt.code as type_code, dt.category as category,
                       ts_rank(d.search_vector, plainto_tsquery('german', ?)) as rank,
                       ts_headline('german', coalesce(d.extracted_text, ''),
                                   plainto_tsquery('german', ?),
                                   'StartSel=<<, StopSel=>>, MaxWords=40, MinWords=15') as snippet
                from documents d
                left join document_types dt on dt.id = d.document_type_id
                where d.tenant_id = ? and d.deleted_at is null
                  and d.search_vector @@ plainto_tsquery('german', ?)
                  and (? = '' or dt.category = ? or dt.code = ?)
                order by rank desc, d.received_at desc
                limit ?
                """, question, question, tenantId, question, scope == null ? "" : scope, scope,
                scope, maxSources);
    }

    static Source toSource(Map<String, Object> row) {
        UUID id = row.get("id") instanceof UUID uuid ? uuid
                : UUID.fromString(String.valueOf(row.get("id")));
        String number = row.get("document_number") == null ? null
                : String.valueOf(row.get("document_number"));
        String filename = row.get("original_filename") == null ? "Dokument"
                : String.valueOf(row.get("original_filename"));
        String title = number == null || number.isBlank() ? filename : number + " – " + filename;
        double rank = row.get("rank") instanceof Number value ? value.doubleValue() : 0d;
        return new Source(id, title,
                row.get("type_code") == null ? null : String.valueOf(row.get("type_code")),
                row.get("category") == null ? null : String.valueOf(row.get("category")), rank,
                cleanExcerpt(row.get("snippet") == null ? null : String.valueOf(row.get("snippet"))));
    }

    /** Removes the headline markers and collapses the whitespace of the excerpt. */
    static String cleanExcerpt(String snippet) {
        if (snippet == null) {
            return null;
        }
        return snippet.replace("<<", "").replace(">>", "").replaceAll("\\s+", " ").strip();
    }

    /**
     * Confidence is a statement about the evidence, not about the truth: high needs more than one
     * source and a solid rank. Visible for tests.
     */
    public static String confidence(int sources, double topRank) {
        if (sources == 0) {
            return "NONE";
        }
        if (sources >= 2 && topRank >= 0.05) {
            return "HIGH";
        }
        if (topRank >= 0.01) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
