package de.buerokratiecopilot.agents.knowledge;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentInputs;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@code knowledge-base} — Wissensartikel.
 *
 * <p>Builds an index of the documents that can serve as internal knowledge: title, type, category,
 * date, owner and the source document. Everything is read from the document register — this
 * platform has no separate article table, and inventing one would create a second truth next to the
 * documents. The index is stored as a JSON artifact under a stable source key, so a rerun with the
 * same filter reuses it instead of writing a second copy.</p>
 *
 * <p><strong>Stale articles are put in front of a person, not silently relinked.</strong> A document
 * older than {@code reviewDueDays} becomes a review task with a completion criterion. Missing type,
 * missing owner and missing date are reported as gaps; the agent does not fill them in.</p>
 */
@Component
public class KnowledgeBaseAgent implements Agent {

    static final int DEFAULT_REVIEW_DAYS = 180;
    static final int MAX_ARTICLES = 500;
    static final String ARTIFACT_KIND = "knowledge-index";

    private final JdbcTemplate jdbc;
    private final AgentTaskFactory tasks;
    private final AgentArtifactStore artifacts;

    public KnowledgeBaseAgent(JdbcTemplate jdbc, AgentTaskFactory tasks, AgentArtifactStore artifacts) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.artifacts = artifacts;
    }

    @Override
    public String id() {
        return "knowledge-base";
    }

    /** One indexed document. */
    public record Article(UUID documentId, String title, String typeCode, String category,
                          LocalDate documentDate, LocalDate receivedOn, UUID ownerUserId,
                          boolean stale, List<String> gaps) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("documentId", documentId.toString());
            map.put("title", title);
            map.put("typeCode", typeCode);
            map.put("category", category);
            map.put("documentDate", documentDate == null ? null : documentDate.toString());
            map.put("receivedOn", receivedOn == null ? null : receivedOn.toString());
            map.put("ownerUserId", ownerUserId == null ? null : ownerUserId.toString());
            map.put("stale", stale);
            map.put("gaps", gaps);
            return map;
        }
    }

    @Override
    public AgentResult run(AgentContext context) {
        int reviewDays = AgentInputs.integer(context.input("reviewDueDays"), DEFAULT_REVIEW_DAYS,
                1, 3650);
        String query = AgentInputs.text(context.input("query"));
        String category = AgentInputs.text(context.input("category"));
        String ownerRaw = context.input("ownerUserId");
        UUID owner = AgentInputs.uuid(ownerRaw);
        if (AgentInputs.text(ownerRaw) != null && owner == null) {
            return AgentResult.refused("INVALID_INPUT",
                    "ownerUserId ist keine gültige UUID: " + ownerRaw);
        }
        LocalDate today = LocalDate.ofInstant(context.now(), AgentInputs.BERLIN);

        List<Map<String, Object>> rows;
        try {
            rows = load(context.tenantId(), query, category, owner);
        }
        catch (RuntimeException ex) {
            return AgentResult.refused("STORAGE_UNAVAILABLE",
                    "Die Dokumentenablage konnte nicht gelesen werden: " + ex.getMessage());
        }
        if (rows.isEmpty()) {
            return AgentResult.refused("NO_DATA",
                    "Keine Wissensartikel im gewählten Ausschnitt gefunden"
                            + (query == null ? "" : " (Suche: " + query + ")") + ".");
        }

        List<Article> articles = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            articles.add(toArticle(row, today, reviewDays));
        }

        List<Article> stale = articles.stream().filter(Article::stale).toList();
        List<Article> withGaps = articles.stream().filter(article -> !article.gaps().isEmpty())
                .toList();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", ARTIFACT_KIND);
        payload.put("reviewDueDays", reviewDays);
        payload.put("query", query);
        payload.put("category", category);
        payload.put("ownerUserId", owner == null ? null : owner.toString());
        payload.put("articleCount", articles.size());
        payload.put("staleCount", stale.size());
        payload.put("gapCount", withGaps.size());
        payload.put("articles", articles.stream().map(Article::asMap).toList());

        String sourceKey = AgentInputs.sourceKey(ARTIFACT_KIND, owner == null ? null : owner.toString(),
                category, query == null ? null : AgentInputs.slug(query, 60),
                Integer.toString(reviewDays));
        AgentArtifactStore.Artifact artifact = artifacts.put(context.tenantId(),
                context.actorUserId(), ARTIFACT_KIND, sourceKey, payload);
        context.addProduced("knowledge-index", artifact.id().toString(),
                "Wissensindex mit " + articles.size() + " Artikel(n), Quelle " + sourceKey);

        int created = 0;
        for (Article article : stale) {
            String title = "Wissensartikel prüfen: " + AgentInputs.truncate(article.title(), 270);
            var request = new AgentTaskFactory.Request(title,
                    "Der Artikel \"" + article.title() + "\" ist älter als " + reviewDays
                            + " Tage. Quelle, Gültigkeit und Inhalt prüfen; nicht stillschweigend "
                            + "weiterverlinken.",
                    "Der Artikel ist geprüft und entweder bestätigt oder durch eine aktuelle "
                            + "Fassung ersetzt.",
                    AgentInputs.dueAt(today, 7), Task.Priority.NORMAL,
                    List.of("wissensbasis", "pruefung"), null, article.ownerUserId());
            var optionalTask = tasks.createIfAbsent(context, request);
            if (optionalTask.isPresent()) {
                AgentTaskFactory.produce(context, optionalTask.get());
                created++;
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("articles", String.valueOf(articles.size()));
        meta.put("stale", String.valueOf(stale.size()));
        meta.put("gaps", String.valueOf(withGaps.size()));
        meta.put("reviewTasks", String.valueOf(created));
        meta.put("reviewDueDays", String.valueOf(reviewDays));
        meta.put("artifactId", artifact.id().toString());
        meta.put("artifactReused", String.valueOf(artifact.reused()));
        meta.put("sourceKey", sourceKey);
        return AgentResult.done("Wissensbasis: " + articles.size() + " Artikel indexiert, "
                + stale.size() + " veraltet, " + withGaps.size() + " mit Lücken; "
                + created + " Prüfaufgabe(n) angelegt.", context.produced(), meta);
    }

    private List<Map<String, Object>> load(UUID tenantId, String query, String category, UUID owner) {
        StringBuilder sql = new StringBuilder("""
                select d.id, d.original_filename, d.document_number, d.document_date, d.received_at,
                       d.created_by, dt.code as type_code, dt.category as category
                from documents d
                left join document_types dt on dt.id = d.document_type_id
                where d.tenant_id = ? and d.deleted_at is null
                  and d.processing_status not in
                      ('DUPLICATE', 'CANCELLED', 'QUARANTINED', 'FAILED', 'UNSUPPORTED')
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        if (query != null) {
            sql.append("""
                      and (lower(d.original_filename) like lower(?)
                           or lower(coalesce(d.document_number, '')) like lower(?)
                           or lower(coalesce(d.summary, '')) like lower(?))
                    """);
            String like = "%" + query + "%";
            parameters.add(like);
            parameters.add(like);
            parameters.add(like);
        }
        if (category != null) {
            sql.append(" and (dt.category = ? or dt.code = ?)\n");
            parameters.add(category);
            parameters.add(category);
        }
        if (owner != null) {
            sql.append(" and d.created_by = ?\n");
            parameters.add(owner);
        }
        sql.append(" order by d.received_at desc limit ").append(MAX_ARTICLES);
        return jdbc.queryForList(sql.toString(), parameters.toArray());
    }

    /** Classifies one document row; visible for tests. */
    static Article toArticle(Map<String, Object> row, LocalDate today, int reviewDays) {
        UUID id = row.get("id") instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(row.get("id")));
        String title = displayTitle(row);
        String typeCode = row.get("type_code") == null ? null : String.valueOf(row.get("type_code"));
        String category = row.get("category") == null ? null : String.valueOf(row.get("category"));
        LocalDate documentDate = AgentInputs.toLocalDate(row.get("document_date"));
        LocalDate receivedOn = AgentInputs.toLocalDate(row.get("received_at"));
        UUID owner = row.get("created_by") instanceof UUID uuid ? uuid
                : AgentInputs.uuid(row.get("created_by") == null ? null : String.valueOf(row.get("created_by")));

        LocalDate reference = documentDate != null ? documentDate : receivedOn;
        boolean stale = reference != null && reference.isBefore(today.minusDays(reviewDays));

        List<String> gaps = new ArrayList<>();
        if (typeCode == null) {
            gaps.add("ohne Dokumenttyp");
        }
        if (owner == null) {
            gaps.add("ohne Verantwortlichkeit");
        }
        if (documentDate == null) {
            gaps.add("ohne Dokumentdatum");
        }
        return new Article(id, title, typeCode, category, documentDate, receivedOn, owner,
                stale, List.copyOf(gaps));
    }

    private static String displayTitle(Map<String, Object> row) {
        String number = row.get("document_number") == null ? null
                : String.valueOf(row.get("document_number"));
        String filename = row.get("original_filename") == null ? "Dokument"
                : String.valueOf(row.get("original_filename"));
        return number == null || number.isBlank() ? filename : number + " – " + filename;
    }
}
