package de.buerokratiecopilot.cases;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Links between cases and documents.
 *
 * <p>Reads and writes go through SQL rather than an entity: the link table carries no state of its own
 * beyond the link, and the document module already owns the document side. Keeping it here means the
 * case module does not need a second entity for a row that only exists to join two others.</p>
 */
@Repository
public class CaseDocumentRepository {

    private final JdbcTemplate jdbc;

    public CaseDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The documents of a case, newest first, with the information a case card shows. */
    public List<LinkedDocument> findByCaseId(UUID caseId) {
        return jdbc.query("""
                select d.id, d.original_filename, d.document_number, d.processing_status,
                       cd.is_primary, cd.link_origin, cd.confidence
                from case_documents cd
                join documents d on d.id = cd.document_id
                where cd.case_id = ? and d.deleted_at is null
                order by cd.created_at desc
                """, (rs, rowNum) -> new LinkedDocument(rs.getObject("id", UUID.class),
                rs.getString("original_filename"), rs.getString("document_number"),
                rs.getString("processing_status"), rs.getBoolean("is_primary"),
                rs.getString("link_origin"),
                rs.getBigDecimal("confidence") == null ? null : rs.getBigDecimal("confidence").doubleValue()),
                caseId);
    }

    public long countByCaseId(UUID caseId) {
        Long count = jdbc.queryForObject("select count(*) from case_documents where case_id = ?",
                Long.class, caseId);
        return count == null ? 0 : count;
    }

    /** One row of the case's document list. */
    public record LinkedDocument(UUID documentId, String filename, String documentNumber,
                                 String processingStatus, boolean primary, String linkOrigin,
                                 Double confidence) {
    }
}
