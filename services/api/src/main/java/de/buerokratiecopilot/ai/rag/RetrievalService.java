package de.buerokratiecopilot.ai.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the passages a question can be answered from (spec §25, E-008).
 *
 * <p>Retrieval is a search, and for a legal-administrative platform a search is only useful if what comes
 * back can be cited. A passage therefore carries the document and the page it came from, because "somewhere
 * in your files" is not an answer anybody can check.</p>
 *
 * <p>Two sources are used. The page text is what a person would read, and it carries the page number.
 * The extracted fields are what the platform has already understood — an invoice number, an amount, a
 * deadline — with the snippet and page the extraction came from, so a citation from there is as checkable
 * as one from the text.</p>
 *
 * <p>A relevance floor is applied, and it is the whole point of {@link #search}: a search that always
 * returns something is a search that lets an answer be built on nothing. What falls below the floor is
 * not reported, and the caller has to deal with an empty result rather than a weak one.</p>
 *
 * <p>Every query is scoped to one tenant, and row level security would refuse anything else.</p>
 */
@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /**
     * The relevance below which a passage is not offered.
     *
     * <p>Deliberately a constant with a name rather than a magic number: it is a judgement about when a
     * hit stops being evidence, and it should be visible and arguable.</p>
     */
    public static final double RELEVANCE_FLOOR = 0.01;

    private final JdbcTemplate jdbc;

    public RetrievalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Where a passage came from. */
    public enum Source {
        /** The text of a page, which is what a person would read. */
        PAGE_TEXT,
        /** A field the platform extracted, with the snippet it came from. */
        EXTRACTED_FIELD,
        /** A clause of a contract (notice period, price, renewal), with the page it was read from. */
        CONTRACT_CLAUSE
    }

    /** A passage that may be cited. */
    public record Passage(UUID documentId, String documentName, Integer pageNumber, String excerpt,
                          double relevance, Source source, String fieldKey) {

        /** How this passage must be cited; built here so no caller invents one. */
        public String citation() {
            StringBuilder citation = new StringBuilder(documentName == null ? "Dokument" : documentName);
            if (pageNumber != null) {
                citation.append(", Seite ").append(pageNumber);
            }
            if (fieldKey != null) {
                citation.append(" (").append(fieldKey).append(")");
            }
            return citation.toString();
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("documentId", documentId.toString());
            map.put("documentName", documentName);
            map.put("pageNumber", pageNumber);
            map.put("excerpt", excerpt);
            map.put("relevance", relevance);
            map.put("source", source.name());
            map.put("fieldKey", fieldKey);
            map.put("citation", citation());
            return map;
        }
    }

    /** What a search returned. */
    public record Result(List<Passage> passages, String query, int considered) {

        public boolean isEmpty() {
            return passages.isEmpty();
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("query", query);
            map.put("considered", considered);
            map.put("found", passages.size());
            map.put("passages", passages.stream().map(Passage::asMap).toList());
            return map;
        }
    }

    /**
     * Searches the tenant's documents.
     *
     * @param documentTypeIds optional restriction to certain document types; empty means all
     * @param limit           how many passages to offer at most
     */
    @Transactional(readOnly = true)
    public Result search(UUID tenantId, String query, List<UUID> documentTypeIds, int limit) {
        if (query == null || query.isBlank()) {
            return new Result(List.of(), query, 0);
        }
        List<Passage> passages = new ArrayList<>();

        passages.addAll(searchPageText(tenantId, query, documentTypeIds, limit));
        passages.addAll(searchExtractedFields(tenantId, query, documentTypeIds, limit));

        List<Passage> ranked = passages.stream()
                .filter(passage -> passage.relevance() >= RELEVANCE_FLOOR)
                .sorted(java.util.Comparator.comparingDouble(Passage::relevance).reversed())
                .limit(Math.max(1, limit))
                .toList();

        log.info("Retrieval for tenant {} found {} passage(s) from {} candidates", tenantId, ranked.size(),
                passages.size());
        return new Result(ranked, query, passages.size());
    }

    private List<Passage> searchPageText(UUID tenantId, String query, List<UUID> documentTypeIds,
                                         int limit) {
        boolean filtered = documentTypeIds != null && !documentTypeIds.isEmpty();
        String typeFilter = filtered ? " and d.document_type_id = any (?::uuid[])" : "";

        return jdbc.query("""
                select d.id as document_id, d.original_filename, p.page_number, p.text_content,
                       ts_rank(to_tsvector('german', coalesce(p.text_content, '')), q.tsq) as relevance
                from document_pages p
                join documents d on d.id = p.document_id
                cross join (select plainto_tsquery('german', ?) as tsq) q
                where d.tenant_id = ? and d.deleted_at is null
                  and p.text_content is not null
                  and to_tsvector('german', p.text_content) @@ q.tsq
                """ + typeFilter + """
                order by relevance desc
                limit ?
                """, (rs, row) -> new Passage(rs.getObject("document_id", UUID.class),
                rs.getString("original_filename"), rs.getInt("page_number"),
                excerpt(rs.getString("text_content")), rs.getDouble("relevance"), Source.PAGE_TEXT, null),
                filtered
                        ? new Object[] { query, tenantId, documentTypeIds.toArray(UUID[]::new), limit }
                        : new Object[] { query, tenantId, limit });
    }

    private List<Passage> searchExtractedFields(UUID tenantId, String query,
                                                List<UUID> documentTypeIds, int limit) {
        return jdbc.query("""
                select e.document_id, d.original_filename, e.source_page, e.source_snippet,
                       e.field_key, e.field_value,
                       ts_rank(to_tsvector('german', coalesce(e.field_value, '') || ' '
                               || coalesce(e.source_snippet, '')), q.tsq) as relevance
                from document_extractions e
                join documents d on d.id = e.document_id
                cross join (select plainto_tsquery('german', ?) as tsq) q
                where d.tenant_id = ? and d.deleted_at is null
                  and to_tsvector('german', coalesce(e.field_value, '') || ' '
                      || coalesce(e.source_snippet, '')) @@ q.tsq
                order by relevance desc
                limit ?
                """, (rs, row) -> new Passage(rs.getObject("document_id", UUID.class),
                rs.getString("original_filename"),
                rs.getObject("source_page") == null ? null : rs.getInt("source_page"),
                rs.getString("source_snippet") == null ? excerpt(rs.getString("field_value"))
                        : excerpt(rs.getString("source_snippet")),
                rs.getDouble("relevance"), Source.EXTRACTED_FIELD, rs.getString("field_key")),
                query, tenantId, limit);
    }


    /** A bounded piece of a passage; a citation quotes a sentence, not a document. */
    static String excerpt(String text) {
        if (text == null) {
            return null;
        }
        String flattened = text.replaceAll("\\s+", " ").strip();
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 297) + "…";
    }

    /** Whether a passage may be cited for a given document and page. */
    @Transactional(readOnly = true)
    public boolean pageExists(UUID tenantId, UUID documentId, Integer pageNumber) {
        if (pageNumber == null) {
            return true;
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from document_pages p
                join documents d on d.id = p.document_id
                where p.document_id = ? and p.page_number = ? and d.tenant_id = ?
                """, Integer.class, documentId, pageNumber, tenantId);
        return count != null && count > 0;
    }
}
