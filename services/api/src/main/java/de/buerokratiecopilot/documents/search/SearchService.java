package de.buerokratiecopilot.documents.search;

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
 * Full text search over documents, with filters and page-level hits (spec §10.5).
 *
 * <p>The search vector is maintained by PostgreSQL itself (see the generated column in migration V3)
 * and queried with the German configuration, so "Rechnungen" finds "Rechnung" and "Mahnbescheid"
 * finds "Mahnung" through stemming rather than through a hand-written synonym list.</p>
 *
 * <p>Two things the query deliberately does not do:</p>
 * <ul>
 *   <li>it does not filter by tenant in application code. Row level security does that, and a second
 *       filter would create the illusion that the application is what protects the data — the day
 *       someone forgets the clause is the day it matters;</li>
 *   <li>it does not fall back to loading everything and filtering in memory. Every filter is a SQL
 *       predicate, so a result page never contains a row the caller may not see.</li>
 * </ul>
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    /** Pages examined per hit for the page-level references. */
    private static final int MAX_PAGE_HITS = 5;

    private final JdbcTemplate jdbc;
    private final SemanticSearchPort semantic;

    public SearchService(JdbcTemplate jdbc, SemanticSearchPort semantic) {
        this.jdbc = jdbc;
        this.semantic = semantic;
    }

    /** Whether semantic search can run, for the API and for diagnostics. */
    public SemanticSearchPort semanticPort() {
        return semantic;
    }

    @Transactional(readOnly = true)
    public SearchResponse search(SearchRequest request) {
        List<Object> parameters = new ArrayList<>();
        String where = buildWhere(request, parameters);

        String rankExpression = request.hasTextQuery()
                ? "ts_rank(d.search_vector, plainto_tsquery('german', ?))"
                : "0";

        List<Object> queryParameters = new ArrayList<>();
        if (request.hasTextQuery()) {
            queryParameters.add(request.query());
        }
        queryParameters.addAll(parameters);

        String sql = """
                select d.id, d.document_number, d.original_filename, d.processing_status, d.received_at,
                       dt.code as type_code, dt.name as type_name,
                       %s as rank,
                       ts_headline('german', coalesce(d.extracted_text, ''), plainto_tsquery('german', ?),
                                   'StartSel=<<, StopSel=>>, MaxWords=30, MinWords=12') as snippet
                from documents d
                left join document_types dt on dt.id = d.document_type_id
                where %s
                order by rank desc, d.received_at desc
                limit ? offset ?
                """.formatted(rankExpression, where);

        // The headline needs its own tsquery parameter in addition to the ranking one.
        List<Object> sqlParameters = new ArrayList<>(queryParameters);
        sqlParameters.add(request.hasTextQuery() ? request.query() : "");
        sqlParameters.add(request.limit());
        sqlParameters.add(request.offset());

        List<SearchResult> results = jdbc.query(sql, (rs, rowNum) -> {
            UUID documentId = rs.getObject("id", UUID.class);
            return new SearchResult(documentId, rs.getString("document_number"),
                    rs.getString("original_filename"), rs.getString("type_code"),
                    typeName(rs.getObject("type_name")), rs.getString("processing_status"),
                    rs.getTimestamp("received_at") == null ? null
                            : rs.getTimestamp("received_at").toInstant(),
                    rs.getDouble("rank"), rs.getString("snippet"), List.of());
        }, sqlParameters.toArray());

        List<SearchResult> withPages = new ArrayList<>(results.size());
        for (SearchResult result : results) {
            withPages.add(new SearchResult(result.documentId(), result.documentNumber(),
                    result.filename(), result.typeCode(), result.typeName(), result.processingStatus(),
                    result.receivedAt(), result.rank(), result.snippet(),
                    request.hasTextQuery() ? pageHits(result.documentId(), request.query()) : List.of()));
        }

        long total = countTotal(request, parameters);
        String mode = request.hasTextQuery() ? "FULL_TEXT" : "FILTER";

        log.debug("Search mode {} returned {} of {} document(s)", mode, withPages.size(), total);
        return SearchResponse.of(withPages, total, request, semantic, mode);
    }

    /** Semantic hits, or an empty list with the reason available through {@link #semanticPort()}. */
    @Transactional(readOnly = true)
    public List<SemanticSearchPort.Hit> searchSemantically(UUID tenantId, String query, int limit) {
        return semantic.search(tenantId, query, limit);
    }

    /**
     * Builds the filter clause.
     *
     * <p>An empty query string is treated as "no text filter" rather than as a match-nothing query:
     * the API receives it when a user clears the box, and returning zero results for that would look
     * like an empty archive.</p>
     */
    private String buildWhere(SearchRequest request, List<Object> parameters) {
        List<String> conditions = new ArrayList<>();
        conditions.add("d.deleted_at is null");

        if (request.hasTextQuery()) {
            conditions.add("d.search_vector @@ plainto_tsquery('german', ?)");
            parameters.add(request.query());
        }
        if (request.documentTypeCode() != null && !request.documentTypeCode().isBlank()) {
            conditions.add("dt.code = ?");
            parameters.add(request.documentTypeCode());
        }
        if (request.processingStatus() != null && !request.processingStatus().isBlank()) {
            conditions.add("d.processing_status = ?");
            parameters.add(request.processingStatus());
        }
        if (request.receivedFrom() != null) {
            conditions.add("d.received_at >= ?");
            parameters.add(java.sql.Timestamp.valueOf(request.receivedFrom().atStartOfDay()));
        }
        if (request.receivedTo() != null) {
            conditions.add("d.received_at < ?");
            parameters.add(java.sql.Timestamp.valueOf(request.receivedTo().plusDays(1).atStartOfDay()));
        }
        if (request.caseId() != null) {
            conditions.add("exists (select 1 from case_documents cd where cd.document_id = d.id and cd.case_id = ?)");
            parameters.add(request.caseId());
        }
        if (request.contactEmail() != null && !request.contactEmail().isBlank()) {
            conditions.add("""
                    exists (select 1 from document_extractions x
                            where x.document_id = d.id and x.field_key in ('Lieferant', 'Absender')
                              and x.field_value ilike ?)
                    """);
            parameters.add("%" + request.contactEmail() + "%");
        }
        return String.join(" and ", conditions);
    }

    private long countTotal(SearchRequest request, List<Object> parameters) {
        String sql = """
                select count(*)
                from documents d
                left join document_types dt on dt.id = d.document_type_id
                where %s
                """.formatted(buildWhere(request, new ArrayList<>()));
        Long total = jdbc.queryForObject(sql, Long.class, parameters.toArray());
        return total == null ? 0 : total;
    }

    /**
     * The pages whose text matches the query.
     *
     * <p>Matching is done with the same German configuration as the document level search, so a page
     * hit cannot appear for a query the document itself did not match. The excerpt is the text around
     * the match, which is what a user needs to recognise the place.</p>
     */
    private List<SearchResult.PageHit> pageHits(UUID documentId, String query) {
        return jdbc.query("""
                select page_number,
                       ts_headline('german', coalesce(text_content, ''), plainto_tsquery('german', ?),
                                   'StartSel=<<, StopSel=>>, MaxWords=25, MinWords=8') as excerpt
                from document_pages
                where document_id = ?
                  and text_content is not null
                  and to_tsvector('german', text_content) @@ plainto_tsquery('german', ?)
                order by page_number
                limit ?
                """, (rs, rowNum) -> new SearchResult.PageHit(rs.getInt("page_number"),
                        rs.getString("excerpt")),
                query, documentId, query, MAX_PAGE_HITS);
    }

    /** The localised type name, which is stored as a JSON object per language. */
    private static String typeName(Object rawName) {
        if (rawName == null) {
            return null;
        }
        String json = String.valueOf(rawName);
        try {
            Map<?, ?> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
            Object german = parsed.get("de");
            return german == null ? null : String.valueOf(german);
        }
        catch (Exception ex) {
            return null;
        }
    }

    /** Diagnostics for the API: which filters were applied. */
    public Map<String, Object> describe(SearchRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("query", request.query() == null ? "" : request.query());
        details.put("type", request.documentTypeCode() == null ? "" : request.documentTypeCode());
        details.put("status", request.processingStatus() == null ? "" : request.processingStatus());
        details.put("semantic", semantic.status().name());
        return details;
    }
}
