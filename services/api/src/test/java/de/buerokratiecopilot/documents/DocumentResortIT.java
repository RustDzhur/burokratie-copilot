package de.buerokratiecopilot.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.documents.classification.DocumentResortService;
import de.buerokratiecopilot.shared.context.RequestContext;

/** Documents filed earlier are put where the current rules say, and a person's decision is not undone. */
@SpringBootTest
@ActiveProfiles("test")
class DocumentResortIT {

    @Autowired
    private DocumentResortService resort;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID document(UUID tenantId, String name, String text, String typeCode) {
        return jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type, size_bytes, content_hash, source,
                                       processing_status, review_status, extracted_text, document_type_id)
                values (gen_random_uuid(), ?, ?, ?, 'application/pdf', 100, ?, 'UPLOAD', 'READY', 'NOT_REQUIRED', ?,
                        (select id from document_types where code = ?))
                returning id
                """, UUID.class, tenantId, name, name, "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()),
                text, typeCode);
    }

    @Test
    @DisplayName("a wrong drawer is corrected, one without text is emptied and sent to review, a confirmed one is left alone")
    void resortsStoredDocuments() {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("insert into tenants (id, slug, name, account_type) values (?, ?, 'T', 'COMPANY')", tenantId,
                "t-" + tenantId.toString().substring(0, 8));
        String invoice = "Rechnung Nr. 2026-0815\nRechnungsbetrag 119,00 EUR\nUmsatzsteuer 19 %\nZahlbar bis 20.10.2026\nBitte überweisen Sie den Betrag";

        UUID[] ids = RequestContext.runAs(tenantId, () -> {
            UUID wrong = document(tenantId, "scan1.pdf", invoice, "vertrag");
            UUID noText = document(tenantId, "BWA 2025.jpg", null, "bwa");
            UUID confirmed = document(tenantId, "scan2.pdf", invoice, "vertrag");
            UUID clicked = document(tenantId, "scan3.pdf", invoice, "vertrag");
            jdbc.update("""
                    insert into document_reviews (tenant_id, document_id, review_type, status, reason, changes, decided_at)
                    values (?, ?, 'CLASSIFICATION', 'APPROVED', 'ok', '{"documentTypeCode":"vertrag"}'::jsonb, now())
                    """, tenantId, confirmed);
            // A click on "confirm" for a suggestion is not a choice of the person: this one is sorted again.
            jdbc.update("""
                    insert into document_reviews (tenant_id, document_id, review_type, status, reason, changes, decided_at)
                    values (?, ?, 'CLASSIFICATION', 'APPROVED', 'ok', '{"documentTypeCode":"vertrag","acceptedSuggestion":true}'::jsonb, now())
                    """, tenantId, clicked);
            return new UUID[] { wrong, noText, confirmed, clicked };
        });

        Map<String, Object> summary = RequestContext.runAs(tenantId, () -> resort.resort(tenantId));

        assertThat(summary).containsEntry("checked", 4).containsEntry("moved", 2).containsEntry("withoutText", 1)
                .containsEntry("keptByPerson", 1).containsEntry("failed", 0);
        RequestContext.runAs(tenantId, () -> {
            assertThat(typeCode(ids[0])).isEqualTo("rechnung");
            assertThat(typeCode(ids[1])).isNull();
            assertThat(jdbc.queryForObject("select review_status from documents where id = ?", String.class, ids[1])).isEqualTo("PENDING");
            assertThat(typeCode(ids[2])).isEqualTo("vertrag");
            assertThat(typeCode(ids[3])).isEqualTo("rechnung");
            return null;
        });
    }

    private String typeCode(UUID documentId) {
        return jdbc.query("select t.code from documents d left join document_types t on t.id = d.document_type_id where d.id = ?",
                rs -> rs.next() ? rs.getString(1) : null, documentId);
    }
}
