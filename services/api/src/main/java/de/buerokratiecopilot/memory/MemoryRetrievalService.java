package de.buerokratiecopilot.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.ai.rag.RetrievalService;

/**
 * Search over the whole business memory: the text of documents, the facts read from them (with their verification), the
 * relationships a person confirmed and the obligations of contracts.
 *
 * <p>The selection itself respects the rights of the reader: what a person may not read is never fetched, so no answer
 * built on the result can quote it. Facts and text need {@code documents.view}, contract obligations
 * {@code contracts.view}, contact relationships {@code contacts.view}; a relationship to a case shows only for someone who
 * may see that case.</p>
 */
@Service
public class MemoryRetrievalService {

    /** A confirmed fact counts for more than a value only a machine read. The factor is a technical default. */
    static final double CONFIRMED_FACT_WEIGHT = 1.5;

    public enum Kind {
        PAGE_TEXT, FACT, RELATIONSHIP, OBLIGATION
    }

    /** One thing found, with everything needed to cite it. */
    public record Hit(Kind kind, UUID documentId, String documentName, Integer page, String excerpt, double relevance,
                      String verification, String objectType, UUID objectId, String subjectType, UUID subjectId,
                      String factKey, String normalizedValue) {

        public String citation() {
            StringBuilder text = new StringBuilder(documentName == null ? kind.name() : documentName);
            if (page != null) {
                text.append(", Seite ").append(page);
            }
            if (factKey != null) {
                text.append(" (").append(factKey).append(")");
            }
            return text.toString();
        }

        public Map<String, Object> asMap(int number) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("n", number);
            map.put("kind", kind.name());
            map.put("documentId", documentId);
            map.put("documentName", documentName);
            map.put("page", page);
            map.put("excerpt", excerpt);
            map.put("relevance", relevance);
            map.put("verification", verification);
            map.put("objectType", objectType);
            map.put("objectId", objectId);
            map.put("citation", citation());
            return map;
        }
    }

    /** Two facts about the same thing that say different things. */
    public record Contradiction(String factKey, String subjectType, UUID subjectId, List<Hit> sources) {
    }

    public record Result(String query, List<Hit> hits, List<Contradiction> contradictions, String mode) {
        public boolean isEmpty() {
            return hits.isEmpty();
        }
    }

    private static final String ALL_TERMS = "plainto_tsquery('german', ?)";
    private static final String ANY_TERM = "replace(plainto_tsquery('german', ?)::text, '&', '|')::tsquery";

    private final JdbcTemplate jdbc;

    public MemoryRetrievalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Result search(UUID tenantId, UUID userId, String query, int limit) {
        if (query == null || query.isBlank()) {
            return new Result(query, List.of(), List.of(), "ALL_TERMS");
        }
        int size = Math.max(1, Math.min(limit, 50));
        Set<String> held = ReaderRights.held();
        List<Hit> ranked = collect(tenantId, userId, query, size, held, ALL_TERMS);
        String mode = "ALL_TERMS";
        if (ranked.isEmpty()) {
            // A question is rarely a list of keywords: the words around the important one ("was kostet die ...") are in
            // no document. When nothing contains every term, the documents that contain some of them are offered, ranked.
            ranked = collect(tenantId, userId, query, size, held, ANY_TERM);
            mode = "ANY_TERM";
        }
        return new Result(query, ranked, contradictions(ranked), mode);
    }

    private List<Hit> collect(UUID tenantId, UUID userId, String query, int size, Set<String> held, String tsq) {
        List<Hit> hits = new ArrayList<>();
        if (held.contains("documents.view")) {
            hits.addAll(pageText(tenantId, query, size, tsq));
            hits.addAll(facts(tenantId, query, size, tsq));
        }
        if (held.contains("contacts.view")) {
            hits.addAll(contactRelationships(tenantId, query, size, tsq));
        }
        if (held.contains("contracts.view")) {
            hits.addAll(contractRelationships(tenantId, query, size, tsq));
            hits.addAll(obligations(tenantId, query, size, tsq));
        }
        if (held.contains("cases.view")) {
            hits.addAll(caseRelationships(tenantId, userId, query, size, tsq));
        }
        return hits.stream()
                .filter(hit -> hit.relevance() >= RetrievalService.RELEVANCE_FLOOR)
                .sorted(Comparator.comparingDouble(Hit::relevance).reversed())
                .limit(size).toList();
    }

    private static String sql(String text, String tsq) {
        return text.replace("{TSQ}", tsq);
    }

    private List<Hit> pageText(UUID tenantId, String query, int limit, String tsq) {
        return jdbc.query(sql("""
                select d.id as document_id, d.original_filename, p.page_number, p.text_content,
                       ts_rank(to_tsvector('german', coalesce(p.text_content, '')), q.tsq) as relevance
                from document_pages p join documents d on d.id = p.document_id
                cross join (select {TSQ} as tsq) q
                where d.tenant_id = ? and d.deleted_at is null and p.text_content is not null
                  and to_tsvector('german', p.text_content) @@ q.tsq
                order by relevance desc limit ?
                """, tsq), (rs, row) -> new Hit(Kind.PAGE_TEXT, rs.getObject("document_id", UUID.class),
                rs.getString("original_filename"), rs.getInt("page_number"), excerpt(rs.getString("text_content")),
                rs.getDouble("relevance"), null, null, null, null, null, null, null),
                query, tenantId, limit);
    }

    private static String excerpt(String text) {
        String flattened = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return flattened.length() <= 300 ? flattened : flattened.substring(0, 297) + "…";
    }

    private List<Hit> facts(UUID tenantId, String query, int limit, String tsq) {
        return jdbc.query(sql("""
                select e.document_id, d.original_filename, e.source_page, e.field_key, e.field_value, e.normalized_value,
                       e.source_snippet, e.verification, e.subject_type, e.subject_id,
                       ts_rank(to_tsvector('german', e.field_key || ' ' || coalesce(e.field_value, '') || ' '
                               || coalesce(e.source_snippet, '')), q.tsq)
                         * case when e.verification = 'USER_CONFIRMED' then ? else 1 end as relevance
                from document_extractions e join documents d on d.id = e.document_id
                cross join (select {TSQ} as tsq) q
                where d.tenant_id = ? and d.deleted_at is null and e.verification <> 'HYPOTHESIS'
                  and to_tsvector('german', e.field_key || ' ' || coalesce(e.field_value, '') || ' '
                      || coalesce(e.source_snippet, '')) @@ q.tsq
                order by relevance desc limit ?
                """, tsq), (rs, row) -> new Hit(Kind.FACT, rs.getObject("document_id", UUID.class),
                rs.getString("original_filename"), (Integer) rs.getObject("source_page"),
                rs.getString("field_key") + ": " + rs.getString("field_value"), rs.getDouble("relevance"),
                rs.getString("verification"), null, null, rs.getString("subject_type"),
                (UUID) rs.getObject("subject_id"), rs.getString("field_key"), rs.getString("normalized_value")),
                CONFIRMED_FACT_WEIGHT, query, tenantId, limit);
    }

    private List<Hit> contactRelationships(UUID tenantId, String query, int limit, String tsq) {
        return jdbc.query(sql("""
                select m.document_id, d.original_filename, c.id as target_id, c.display_name,
                       ts_rank(to_tsvector('german', c.display_name), q.tsq) as relevance
                from document_matches m join documents d on d.id = m.document_id
                join contacts c on c.id = m.target_id and c.tenant_id = m.tenant_id
                cross join (select {TSQ} as tsq) q
                where m.tenant_id = ? and m.match_type = 'CONTACT' and m.status = 'CONFIRMED'
                  and d.deleted_at is null and c.deleted_at is null and to_tsvector('german', c.display_name) @@ q.tsq
                order by relevance desc limit ?
                """, tsq), (rs, row) -> new Hit(Kind.RELATIONSHIP, rs.getObject("document_id", UUID.class),
                rs.getString("original_filename"), null,
                "Dokument gehört zu Kontakt " + rs.getString("display_name") + " (bestätigt)", rs.getDouble("relevance"),
                "USER_CONFIRMED", "CONTACT", rs.getObject("target_id", UUID.class), null, null, null, null),
                query, tenantId, limit);
    }

    private List<Hit> contractRelationships(UUID tenantId, String query, int limit, String tsq) {
        return jdbc.query(sql("""
                select m.document_id, d.original_filename, k.id as target_id, k.title,
                       ts_rank(to_tsvector('german', k.title || ' ' || coalesce(k.contract_number, '')), q.tsq) as relevance
                from document_matches m join documents d on d.id = m.document_id
                join contracts k on k.id = m.target_id and k.tenant_id = m.tenant_id
                cross join (select {TSQ} as tsq) q
                where m.tenant_id = ? and m.match_type = 'CONTRACT' and m.status = 'CONFIRMED'
                  and d.deleted_at is null and k.deleted_at is null
                  and to_tsvector('german', k.title || ' ' || coalesce(k.contract_number, '')) @@ q.tsq
                order by relevance desc limit ?
                """, tsq), (rs, row) -> new Hit(Kind.RELATIONSHIP, rs.getObject("document_id", UUID.class),
                rs.getString("original_filename"), null,
                "Dokument gehört zum Vertrag " + rs.getString("title") + " (bestätigt)", rs.getDouble("relevance"),
                "USER_CONFIRMED", "CONTRACT", rs.getObject("target_id", UUID.class), null, null, null, null),
                query, tenantId, limit);
    }

    private List<Hit> caseRelationships(UUID tenantId, UUID userId, String query, int limit, String tsq) {
        boolean all = ReaderRights.seesEveryCase();
        return jdbc.query(sql("""
                select m.document_id, d.original_filename, k.id as target_id, k.title,
                       ts_rank(to_tsvector('german', k.title || ' ' || k.reference), q.tsq) as relevance
                from document_matches m join documents d on d.id = m.document_id
                join cases k on k.id = m.target_id and k.tenant_id = m.tenant_id
                cross join (select {TSQ} as tsq) q
                where m.tenant_id = ? and m.match_type = 'CASE' and m.status = 'CONFIRMED'
                  and d.deleted_at is null and k.deleted_at is null
                  and to_tsvector('german', k.title || ' ' || k.reference) @@ q.tsq
                  and (? or k.owner_user_id = ? or k.responsible_user_id = ?
                       or exists (select 1 from case_participants p where p.case_id = k.id and p.user_id = ?
                                  and p.revoked_at is null and (p.expires_at is null or p.expires_at > now())))
                order by relevance desc limit ?
                """, tsq), (rs, row) -> new Hit(Kind.RELATIONSHIP, rs.getObject("document_id", UUID.class),
                rs.getString("original_filename"), null,
                "Dokument gehört zum Vorgang " + rs.getString("title") + " (bestätigt)", rs.getDouble("relevance"),
                "USER_CONFIRMED", "CASE", rs.getObject("target_id", UUID.class), null, null, null, null),
                query, tenantId, all, userId, userId, userId, limit);
    }

    private List<Hit> obligations(UUID tenantId, String query, int limit, String tsq) {
        return jdbc.query(sql("""
                select o.id, o.title, o.due_date, o.status, k.id as contract_id, k.title as contract_title,
                       ts_rank(to_tsvector('german', o.title || ' ' || k.title), q.tsq) as relevance
                from contract_obligations o join contracts k on k.id = o.contract_id
                cross join (select {TSQ} as tsq) q
                where o.tenant_id = ? and k.deleted_at is null
                  and to_tsvector('german', o.title || ' ' || k.title) @@ q.tsq
                order by relevance desc limit ?
                """, tsq), (rs, row) -> new Hit(Kind.OBLIGATION, null, rs.getString("contract_title"), null,
                "Pflicht: " + rs.getString("title") + (rs.getDate("due_date") == null ? "" : ", fällig " + rs.getDate("due_date"))
                        + " (" + rs.getString("status") + ")", rs.getDouble("relevance"), null, "CONTRACT",
                rs.getObject("contract_id", UUID.class), null, null, null, null),
                query, tenantId, limit);
    }

    /** Facts about the same thing, with the same key, that disagree. Only facts that have a subject can be compared. */
    static List<Contradiction> contradictions(List<Hit> hits) {
        Map<String, List<Hit>> groups = new LinkedHashMap<>();
        for (Hit hit : hits) {
            if (hit.kind() == Kind.FACT && hit.subjectId() != null && hit.normalizedValue() != null) {
                groups.computeIfAbsent(hit.subjectType() + "|" + hit.subjectId() + "|" + hit.factKey(), k -> new ArrayList<>())
                        .add(hit);
            }
        }
        List<Contradiction> found = new ArrayList<>();
        for (List<Hit> group : groups.values()) {
            if (group.stream().map(Hit::normalizedValue).distinct().count() > 1) {
                Hit first = group.get(0);
                found.add(new Contradiction(first.factKey(), first.subjectType(), first.subjectId(), List.copyOf(group)));
            }
        }
        return found;
    }
}
