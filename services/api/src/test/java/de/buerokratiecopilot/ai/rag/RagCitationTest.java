package de.buerokratiecopilot.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.ai.rag.RagService.Citation;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies that citations point at passages that were actually found (spec §25, E-008).
 *
 * <p>An answer whose sources are invented is worse than no answer, because it is checkable in principle
 * and wrong in fact. The tests build a small file of real pages and then try to cite it — correctly, from
 * the wrong page, and from a document that was never retrieved.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RagCitationTest {

    @Autowired
    private RagService rag;

    @Autowired
    private RetrievalService retrieval;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'RAG Test', 'COMPANY') returning id
                """, UUID.class, "rag-" + UUID.randomUUID().toString().substring(0, 12));
    }

    /** A document with pages, so citations have a page number to point at. */
    private UUID document(UUID tenantId, String name, String... pages) {
        String hash = "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        UUID documentId = jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, ?, ?, 'application/pdf', 1000, ?, 'UPLOAD', 'READY',
                        'NOT_REQUIRED')
                returning id
                """, UUID.class, tenantId, name, name, hash);
        // A page belongs to a version, and a version points at a stored object: both are required, and
        // the foreign keys say so.
        UUID blobId = jdbc.queryForObject("""
                insert into document_blobs (id, tenant_id, storage_key, storage_backend, size_bytes,
                                            content_hash, content_type, scan_status, scan_details,
                                            quarantined)
                values (gen_random_uuid(), ?, ?, 'filesystem', 1000, ?, 'application/pdf', 'CLEAN',
                        '{}'::jsonb, false)
                returning id
                """, UUID.class, tenantId, "test/" + UUID.randomUUID(), hash);
        UUID versionId = jdbc.queryForObject("""
                insert into document_versions (id, document_id, tenant_id, version, blob_id, content_hash,
                                               size_bytes, mime_type, original_filename)
                values (gen_random_uuid(), ?, ?, 1, ?, ?, 1000, 'application/pdf', ?)
                returning id
                """, UUID.class, documentId, tenantId, blobId, hash, name);
        int page = 1;
        for (String text : pages) {
            jdbc.update("""
                    insert into document_pages (id, document_id, version_id, page_number, text_content,
                                                ocr_used, text_blocks)
                    values (gen_random_uuid(), ?, ?, ?, ?, false, '[]'::jsonb)
                    """, documentId, versionId, page++, text);
        }
        return documentId;
    }

    private RagService.Answer ask(UUID tenantId, String query, List<Citation> claims) {
        return RequestContext.runAs(tenantId, () -> rag.prepare(tenantId, query, claims, List.of(), 10));
    }

    @Test
    @DisplayName("a passage is found and cited with its page")
    void passageIsFoundAndCited() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId, "widerspruch.pdf",
                "Sehr geehrte Damen und Herren, hiermit lege ich Widerspruch gegen den Bescheid ein.",
                "Der Bescheid wurde am 14.03.2026 zugestellt.");

        RagService.Answer answer = ask(tenantId, "Widerspruch Bescheid",
                List.of(new Citation(documentId, 1, null, "Es wurde Widerspruch eingelegt")));

        assertThat(answer.hasData()).isTrue();
        assertThat(answer.sources()).isNotEmpty();
        assertThat(answer.sources().getFirst().citation())
                .as("a citation names the document and the page, because 'somewhere in your files' is "
                        + "not checkable")
                .contains("widerspruch.pdf")
                .contains("Seite");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.rejected()).isEmpty();
    }

    @Test
    @DisplayName("a claim citing the wrong page is rejected")
    void wrongPageIsRejected() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId, "bescheid.pdf",
                "Der Bescheid wurde am 14.03.2026 zugestellt.");

        RagService.Answer answer = ask(tenantId, "Bescheid zugestellt",
                List.of(new Citation(documentId, 99, null, "Auf Seite 99 steht etwas anderes")));

        assertThat(answer.rejected()).hasSize(1);
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    @DisplayName("a claim citing a document that was not retrieved is rejected")
    void unretrievedDocumentIsRejected() {
        UUID tenantId = tenant();
        UUID found = document(tenantId, "bescheid.pdf", "Der Bescheid wurde zugestellt.");
        UUID other = document(tenantId, "notiz.pdf", "Eine ganz andere Notiz ohne Bezug.");

        RagService.Answer answer = ask(tenantId, "Bescheid zugestellt",
                List.of(new Citation(found, 1, null, "Aussage aus dem Bescheid"),
                        new Citation(other, 1, null, "Aussage aus einer Notiz, die nicht gefunden wurde")));

        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.rejected()).hasSize(1);
        assertThat(answer.rejected().getFirst().documentId()).isEqualTo(other);
        assertThat(answer.asMap()).containsEntry("rejectedCitations", 1);
    }

    @Test
    @DisplayName("a claim without a source is not supported")
    void claimWithoutSourceIsNotSupported() {
        UUID tenantId = tenant();
        document(tenantId, "bescheid.pdf", "Der Bescheid wurde zugestellt.");

        RagService.Answer answer = ask(tenantId, "Bescheid zugestellt",
                List.of(new Citation(null, null, null, "Eine Behauptung ohne Quelle")));

        assertThat(answer.rejected()).hasSize(1);
    }

    @Test
    @DisplayName("a page that exists but was not retrieved does not support a claim")
    void unretrievedPageDoesNotSupport() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId, "akte.pdf",
                "Der Widerspruch wurde eingelegt.",
                "Ein völlig anderes Thema ohne die gesuchten Wörter.");

        // Page 2 exists, but the search did not return it, so the model cannot have read it.
        RagService.Answer answer = ask(tenantId, "Widerspruch eingelegt",
                List.of(new Citation(documentId, 2, null, "Aussage von Seite 2")));

        assertThat(answer.rejected()).hasSize(1);
    }

    @Test
    @DisplayName("an extracted field can be cited with its snippet and page")
    void extractedFieldCanBeCited() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId, "rechnung.pdf", "Rechnung Nr. 4711 über 119,00 EUR.");
        RequestContext.runAs(tenantId, () -> jdbc.update("""
                insert into document_extractions (id, document_id, field_key, field_value, value_type,
                                                  confidence, source_kind, source_page, source_snippet,
                                                  extractor)
                values (gen_random_uuid(), ?, 'invoice_number', '4711', 'TEXT', 0.95, 'TEXT', 1,
                        'Rechnung Nr. 4711 über 119,00 EUR.', 'RULE')
                """, documentId));

        RagService.Answer answer = ask(tenantId, "Rechnung 4711",
                List.of(new Citation(documentId, 1, "invoice_number", "Die Rechnungsnummer ist 4711")));

        assertThat(answer.sources()).anySatisfy(passage -> assertThat(passage.source())
                .isEqualTo(RetrievalService.Source.EXTRACTED_FIELD));
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.rejected()).isEmpty();
    }

    @Test
    @DisplayName("the retrieval result carries what was searched and what was found")
    void retrievalResultIsExplained() {
        UUID tenantId = tenant();
        document(tenantId, "bescheid.pdf", "Der Bescheid wurde am 14.03.2026 zugestellt.");

        var result = RequestContext.runAs(tenantId, () -> retrieval.search(tenantId, "Bescheid", List.of(),
                10));

        assertThat(result.query()).isEqualTo("Bescheid");
        assertThat(result.considered()).isGreaterThanOrEqualTo(result.passages().size());
        assertThat(result.asMap()).containsKeys("query", "considered", "found", "passages");
    }

    @Test
    @DisplayName("an excerpt is bounded, because a citation quotes a sentence")
    void excerptIsBounded() {
        String longText = "Widerspruch ".repeat(100);

        assertThat(RetrievalService.excerpt(longText).length()).isLessThanOrEqualTo(300);
    }

    @Test
    @DisplayName("a claim may name the field it used")
    void fieldKeyIsChecked() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId, "rechnung.pdf", "Rechnung Nr. 4711.");
        RequestContext.runAs(tenantId, () -> jdbc.update("""
                insert into document_extractions (id, document_id, field_key, field_value, value_type,
                                                  confidence, source_kind, source_page, source_snippet,
                                                  extractor)
                values (gen_random_uuid(), ?, 'invoice_number', '4711', 'TEXT', 0.95, 'TEXT', 1,
                        'Rechnung Nr. 4711.', 'RULE')
                """, documentId));

        // A field the extraction does not have is not a source, even for a document that was found.
        RagService.Answer answer = ask(tenantId, "Rechnung 4711",
                List.of(new Citation(documentId, 1, "customer_number", "Kundennummer 999")));

        assertThat(answer.rejected()).hasSize(1);
    }
}
