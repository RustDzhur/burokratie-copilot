package de.buerokratiecopilot.ai.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies that the platform says "no data" instead of answering without evidence (spec §25, E-008 acceptance).
 *
 * <p>The criterion is the one that decides whether the whole feature can be trusted. A model asked to
 * answer without documents will answer anyway — from its training, from the wording of the question, from
 * nowhere — and the result reads exactly like an answer that came from the files. So the platform does not
 * ask: when retrieval finds nothing above the relevance floor, it returns "not found" and stops.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RagNoAnswerTest {

    @Autowired
    private RagService rag;

    @Autowired
    private RetrievalService retrieval;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Keine Antwort Test', 'COMPANY') returning id
                """, UUID.class, "noa-" + UUID.randomUUID().toString().substring(0, 12));
    }

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
        int[] pageCounter = {1};
        // document_pages is protected by a row-level security policy that reaches the tenant through the
        // document, so a write without a tenant context is refused - which is the point of the policy.
        RequestContext.runAs(tenantId, () -> {
            for (String text : pages) {
                jdbc.update("""
                        insert into document_pages (id, document_id, version_id, page_number, text_content,
                                                    ocr_used, text_blocks)
                        values (gen_random_uuid(), ?, ?, ?, ?, false, '[]'::jsonb)
                        """, documentId, versionId, pageCounter[0]++, text);
            }
        });
        return documentId;
    }

    private RagService.Answer ask(UUID tenantId, String query, List<Citation> claims) {
        return RequestContext.runAs(tenantId, () -> rag.prepare(tenantId, query, claims, List.of(), 10));
    }

    @Test
    @DisplayName("a question nothing matches is answered with no data")
    void nothingMatchesMeansNoData() {
        UUID tenantId = tenant();
        document(tenantId, "rechnung.pdf", "Rechnung Nr. 4711 über 119,00 EUR.");

        RagService.Answer answer = ask(tenantId, "Kündigung Arbeitsvertrag Probezeit",
                List.of(new Citation(null, null, null, "Eine erfundene Aussage")));

        assertThat(answer.hasData())
                .as("answering anyway would produce something that reads exactly like an answer from the files")
                .isFalse();
        assertThat(answer.kind()).isEqualTo("NO_DATA");
        assertThat(answer.sources()).isEmpty();
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    @DisplayName("the refusal says what was searched for")
    void refusalSaysWhatWasSearched() {
        UUID tenantId = tenant();
        document(tenantId, "rechnung.pdf", "Rechnung Nr. 4711.");

        RagService.Answer answer = ask(tenantId, "Mietvertrag Gewerbe",
                List.of());

        assertThat(answer.message())
                .as("a user has to tell 'no such document' from 'I did not understand the question'")
                .contains("Mietvertrag Gewerbe");
        assertThat(answer.message()).contains("Belege");
    }

    @Test
    @DisplayName("a tenant with no documents at all gets no data")
    void emptyTenantGetsNoData() {
        UUID tenantId = tenant();

        RagService.Answer answer = ask(tenantId, "Irgendetwas", List.of());

        assertThat(answer.kind()).isEqualTo("NO_DATA");
    }

    @Test
    @DisplayName("another tenant's documents are not searched")
    void anotherTenantsDocumentsAreNotSearched() {
        UUID owner = tenant();
        UUID stranger = tenant();
        document(owner, "geheim.pdf", "Der geheime Widerspruch gegen den Bescheid.");

        RagService.Answer answer = ask(stranger, "Widerspruch Bescheid", List.of());

        assertThat(answer.hasData())
                .as("retrieval is scoped to the tenant; row level security would refuse anything else")
                .isFalse();
        assertThat(String.valueOf(answer.asMap())).doesNotContain("geheim.pdf");
    }

    @Test
    @DisplayName("a search below the relevance floor is not offered")
    void weakMatchesAreNotOffered() {
        UUID tenantId = tenant();
        document(tenantId, "notiz.pdf", "Ein völlig anderes Thema.");

        var result = RequestContext.runAs(tenantId, () -> retrieval.search(tenantId,
                "Widerspruch Bescheid Fristverlängerung", List.of(), 10));

        assertThat(result.isEmpty())
                .as("a search that always returns something lets an answer be built on nothing")
                .isTrue();
    }

    @Test
    @DisplayName("an empty question is refused rather than searched for")
    void emptyQuestionIsRefused() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            rag.requireQuery("   ");
            return null;
        })).isInstanceOf(ApiException.class).hasMessageContaining("Frage");

        assertThat(RequestContext.runAs(tenantId, () -> retrieval.search(tenantId, "  ", List.of(), 10))
                .isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the answer states how many passages it rests on")
    void answerStatesItsEvidence() {
        UUID tenantId = tenant();
        document(tenantId, "bescheid.pdf", "Der Bescheid wurde am 14.03.2026 zugestellt.");

        RagService.Answer answer = ask(tenantId, "Bescheid zugestellt", List.of());

        assertThat(answer.hasData()).isTrue();
        assertThat(answer.message()).contains("Stelle");
        assertThat(answer.asMap()).containsEntry("found", answer.sources().size())
                .containsEntry("kind", "ANSWER");
    }

    @Test
    @DisplayName("every source of an answer can be listed for a user")
    void sourcesCanBeListed() {
        UUID tenantId = tenant();
        document(tenantId, "bescheid.pdf", "Der Bescheid wurde am 14.03.2026 zugestellt.");

        RagService.Answer answer = ask(tenantId, "Bescheid zugestellt", List.of());

        List<String> sources = rag.sourceList(answer);
        assertThat(sources).isNotEmpty();
        assertThat(sources.getFirst()).contains("bescheid.pdf");
    }

    @Test
    @DisplayName("a search can be restricted to document types")
    void searchCanBeRestrictedByType() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId, "bescheid.pdf", "Der Bescheid wurde zugestellt.");
        UUID typeId = jdbc.queryForObject("select id from document_types limit 1", UUID.class);
        jdbc.update("update documents set document_type_id = ? where id = ?", typeId, documentId);

        var matching = RequestContext.runAs(tenantId,
                () -> retrieval.search(tenantId, "Bescheid", List.of(typeId), 10));
        var other = RequestContext.runAs(tenantId, () -> retrieval.search(tenantId, "Bescheid",
                List.of(UUID.randomUUID()), 10));

        assertThat(matching.isEmpty()).isFalse();
        assertThat(other.isEmpty()).isTrue();
    }
}
