package de.buerokratiecopilot.documents.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.documents.DocumentMatchRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentMatch;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/**
 * Verifies matching of a document to cases, contacts and contracts (spec §9.5).
 *
 * <p>The requirement is not only that a link is proposed, but that the proposal can be judged: it
 * carries the score, the reason in words, and the exact string that matched. The test therefore
 * checks the evidence, not just the existence of a row, and checks that nothing is linked silently.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MatchingIT {

    /**
     * An invoice that carries everything the matcher looks at: a case reference, the VAT id of the
     * supplier contact and the invoice vocabulary the classifier needs.
     */
    private static String invoiceText(String caseReference) {
        return """
                Muster GmbH
                USt-IdNr: DE123456789
                Aktenzeichen: %s
                Rechnungsnummer: 2026-501
                Rechnungsdatum: 15.03.2026
                Zahlbar bis: 31.03.2026
                Nettobetrag: 1.000,00 EUR
                Umsatzsteuer: 190,00 EUR
                Bruttobetrag: 1.190,00 EUR
                """.formatted(caseReference);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentMatchRepository matches;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    @Autowired
    private MatchingService matching;

    @Autowired
    private de.buerokratiecopilot.documents.DocumentVersionRepository versions;

    @Autowired
    private de.buerokratiecopilot.contacts.identifiers.ContactIdentifierService identifiers;

    private record Tenant(UUID tenantId, UUID caseId, UUID contactId, UUID userId, UUID documentId) {
    }

    /** Creates a tenant with one case and one contact, the way a first customer would have them. */
    private Tenant setUpTenant(String caseReference) throws Exception {
        return setUpTenant(caseReference, invoiceText(caseReference));
    }

    private Tenant setUpTenant(String caseReference, String documentText) throws Exception {
        String email = "match-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Match Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));

        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });

        UUID[] ids = RequestContext.runAs(tenantId, () -> {
            UUID caseId = jdbc.queryForObject("""
                    insert into cases (id, tenant_id, reference, title, status, priority, opened_at)
                    values (gen_random_uuid(), ?, ?, 'Nebenkostenabrechnung prüfen', 'NEW', 'NORMAL', now())
                    returning id
                    """, UUID.class, tenantId, caseReference);

            UUID contactId = jdbc.queryForObject("""
                    insert into contacts (id, tenant_id, contact_type, display_name, organization_name,
                                          vat_id, is_customer, is_supplier)
                    values (gen_random_uuid(), ?, 'ORGANIZATION', 'Muster GmbH', 'Muster GmbH',
                            'DE123456789', false, true)
                    returning id
                    """, UUID.class, tenantId);
            return new UUID[] { caseId, contactId };
        });

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = login.getResponse().getCookie("bc_session");
        UUID documentId = UUID.fromString(uploadDocument(session, documentText));
        return new Tenant(tenantId, ids[0], ids[1], userId, documentId);
    }

    private String uploadDocument(Cookie session, String text) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "Rechnung.pdf", "application/pdf",
                TestDocuments.pdf(text));
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents").file(file).with(csrf())
                        .cookie(session))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(upload.getResponse().getContentAsString(), "$.document.id");
    }

    private void advance(UUID documentId) {
        UUID tenant = documents.findById(documentId).orElseThrow().getTenantId();
        RequestContext.runAs(tenant, () -> pipeline.advance(documentId));
    }

    /**
     * Reads as the tenant, the way every application request does.
     *
     * <p>The tables are protected by row level security, so a query without a tenant in the context
     * returns nothing — which is the policy working, not a missing row. A test that bypassed the
     * context would be testing a situation the application never creates.</p>
     */
    private <T> T asTenant(UUID tenantId, java.util.function.Supplier<T> reader) {
        return RequestContext.runAs(tenantId, reader);
    }

    @Test
    @DisplayName("a document naming a case reference is matched to that case, with the reason")
    void matchesCaseAndExplainsWhy() throws Exception {
        Tenant tenant = setUpTenant("2026-114");
        // The document carries the reference on its own line, as a real letter does.
        advance(tenant.documentId());

        DocumentMatch match = asTenant(tenant.tenantId(),
                () -> matches.findByDocumentIdAndMatchType(tenant.documentId(),
                        DocumentMatch.MatchType.CASE).stream().findFirst())
                .orElseThrow(() -> new AssertionError("The case was not matched"));

        assertThat(match.getTargetId()).isEqualTo(tenant.caseId());
        assertThat(match.getScore().doubleValue()).isGreaterThanOrEqualTo(0.9);
        assertThat(match.getRationale().get("de").toString())
                .as("a user must see the basis of the proposal")
                .contains("2026-114");
        assertThat(match.getEvidence().get("matchedText")).isEqualTo("2026-114");
        assertThat(match.getEvidence().get("page")).isEqualTo(1);
        assertThat(match.getEvidence().get("snippet").toString()).contains("2026-114");
    }

    @Test
    @DisplayName("an exact reference is linked automatically, and the link says why")
    void linksExactReferenceAndRecordsTheReason() throws Exception {
        Tenant tenant = setUpTenant("2026-115");
        advance(tenant.documentId());

        Integer links = asTenant(tenant.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from case_documents where case_id = ? and document_id = ?
                """, Integer.class, tenant.caseId(), tenant.documentId()));

        assertThat(links)
                .as("an exact reference is strong enough to link, but only with the reason attached")
                .isEqualTo(1);

        String note = asTenant(tenant.tenantId(), () -> jdbc.queryForObject("""
                select note from case_documents where case_id = ? and document_id = ?
                """, String.class, tenant.caseId(), tenant.documentId()));
        assertThat(note).contains("2026-115");

        String origin = asTenant(tenant.tenantId(), () -> jdbc.queryForObject("""
                select link_origin from case_documents where case_id = ? and document_id = ?
                """, String.class, tenant.caseId(), tenant.documentId()));
        assertThat(origin).isEqualTo("RULE");
    }

    @Test
    @DisplayName("a contact is proposed but never linked silently")
    void proposesContactWithoutLinking() throws Exception {
        Tenant tenant = setUpTenant("2026-116");
        // The supplier VAT id identifies the contact.
        advance(tenant.documentId());

        assertThat(asTenant(tenant.tenantId(), () -> matches.findByDocumentId(tenant.documentId())))
                .isNotEmpty();
        assertThat(asTenant(tenant.tenantId(),
                () -> matches.findByStatusOrderByScoreDesc(DocumentMatch.Status.SUGGESTED)))
                .as("a proposal waits for a decision, it is not a decision")
                .allSatisfy(match -> assertThat(match.getDecidedAt()).isNull());
    }

    @Test
    @DisplayName("a document without any recognisable reference is not linked to anything")
    void doesNotInventMatches() throws Exception {
        Tenant tenant = setUpTenant("2026-900");
        advance(tenant.documentId());

        // The document matches the case, but nothing in it is a contract number, a contact e-mail or
        // a tax id of another contact - so those types must stay empty rather than being filled with
        // the closest available object.
        assertThat(asTenant(tenant.tenantId(), () -> matches.findByDocumentIdAndMatchType(
                tenant.documentId(), DocumentMatch.MatchType.CONTRACT)))
                .as("no contract number appears in the document, so no contract is proposed")
                .isEmpty();
        assertThat(asTenant(tenant.tenantId(), () -> matches.findByDocumentIdAndMatchType(
                tenant.documentId(), DocumentMatch.MatchType.INVOICE)))
                .isEmpty();
    }

    @Test
    @DisplayName("a rejected proposal is recorded as a decision, not deleted")
    void recordsRejection() throws Exception {
        Tenant tenant = setUpTenant("2026-117");
        advance(tenant.documentId());

        DocumentMatch match = asTenant(tenant.tenantId(),
                () -> matches.findByDocumentId(tenant.documentId()).stream()
                        .filter(DocumentMatch::isSuggested)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Expected at least one open proposal")));

        // Through the service, because that is the path the application uses and it runs in a
        // transaction: mutating a detached entity and saving it back would silently write the old
        // value, which is exactly what happened before this test was fixed.
        asTenant(tenant.tenantId(),
                () -> matching.decide(match.getId(), DocumentMatch.Status.REJECTED, tenant.userId()));
        DocumentMatch stored = asTenant(tenant.tenantId(),
                () -> matches.findById(match.getId()).orElseThrow());

        assertThat(stored.getStatus()).isEqualTo(DocumentMatch.Status.REJECTED);
        assertThat(stored.getDecidedBy()).isEqualTo(tenant.userId());
        assertThat(stored.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("a rejected proposal does not come back when the matching runs again, and no second row appears")
    void rejectedProposalStaysRejectedAfterRerun() throws Exception {
        Tenant tenant = setUpTenant("2026-119");
        advance(tenant.documentId());

        DocumentMatch contact = asTenant(tenant.tenantId(),
                () -> matches.findByDocumentId(tenant.documentId()).stream()
                        .filter(match -> match.getMatchType() == DocumentMatch.MatchType.CONTACT)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Expected a proposal for the contact")));
        asTenant(tenant.tenantId(),
                () -> matching.decide(contact.getId(), DocumentMatch.Status.REJECTED, tenant.userId()));

        // The same document is matched again, as happens when a stage is repeated after a fix.
        asTenant(tenant.tenantId(), () -> {
            Document document = documents.findById(tenant.documentId()).orElseThrow();
            var version = versions.findFirstByDocumentIdOrderByVersionDesc(document.getId()).orElseThrow();
            return matching.match(document, version, null);
        });

        var rows = asTenant(tenant.tenantId(), () -> matches.findByDocumentId(tenant.documentId()).stream()
                .filter(match -> match.getMatchType() == DocumentMatch.MatchType.CONTACT
                        && match.getTargetId().equals(contact.getTargetId()))
                .toList());
        assertThat(rows).as("one row for the pair, not a second suggestion").hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(DocumentMatch.Status.REJECTED);
    }

    @Test
    @DisplayName("a rejected proposal comes back only when a newer version supports it clearly more strongly, and then a person decides again")
    void rejectedProposalReturnsOnlyOnMuchStrongerNewEvidence() throws Exception {
        Tenant tenant = setUpTenant("2026-121");
        advance(tenant.documentId());
        DocumentMatch contact = asTenant(tenant.tenantId(),
                () -> matches.findByDocumentId(tenant.documentId()).stream()
                        .filter(match -> match.getMatchType() == DocumentMatch.MatchType.CONTACT)
                        .findFirst().orElseThrow());
        asTenant(tenant.tenantId(), () -> matching.decide(contact.getId(), DocumentMatch.Status.REJECTED, tenant.userId()));

        // A second version of the same document with the same pages.
        asTenant(tenant.tenantId(), () -> {
            UUID first = jdbc.queryForObject("select id from document_versions where document_id = ? order by version limit 1",
                    UUID.class, tenant.documentId());
            UUID second = jdbc.queryForObject("""
                    insert into document_versions (id, document_id, tenant_id, version, blob_id, content_hash, size_bytes,
                                                   mime_type, original_filename)
                    select gen_random_uuid(), document_id, tenant_id, version + 1, blob_id, md5(random()::text) || md5(random()::text),
                           size_bytes, mime_type, original_filename
                    from document_versions where id = ? returning id
                    """, UUID.class, first);
            jdbc.update("""
                    insert into document_pages (id, document_id, version_id, page_number, text_content, ocr_used, text_blocks)
                    select gen_random_uuid(), document_id, ?, page_number, text_content, ocr_used, text_blocks
                    from document_pages where version_id = ?
                    """, second, first);
            return null;
        });
        Runnable rerun = () -> asTenant(tenant.tenantId(), () -> {
            Document document = documents.findById(tenant.documentId()).orElseThrow();
            var version = versions.findFirstByDocumentIdOrderByVersionDesc(document.getId()).orElseThrow();
            return matching.match(document, version, null);
        });
        java.util.function.Supplier<DocumentMatch.Status> status = () -> asTenant(tenant.tenantId(),
                () -> matches.findByDocumentId(tenant.documentId()).stream()
                        .filter(match -> match.getId().equals(contact.getId())).findFirst().orElseThrow().getStatus());

        rerun.run();
        assertThat(status.get()).as("a new version with the same evidence is not new evidence").isEqualTo(DocumentMatch.Status.REJECTED);

        // The pair was rejected on weak evidence; the new version supports it far more strongly.
        asTenant(tenant.tenantId(), () -> jdbc.update("update document_matches set score = 0.30 where id = ?", contact.getId()));
        rerun.run();
        assertThat(status.get()).isEqualTo(DocumentMatch.Status.SUGGESTED);
    }

    @Test
    @DisplayName("a later document of the same kind and number is proposed as replacing the earlier one; only proposed, once, and never across tenants")
    void laterDocumentWithTheSameNumberIsProposedAsReplacement() throws Exception {
        Tenant tenant = setUpTenant("2026-122");
        advance(tenant.documentId());
        UUID typeId = asTenant(tenant.tenantId(), () -> jdbc.queryForObject(
                "select id from document_types where code = 'rechnung' limit 1", UUID.class));
        UUID older = asTenant(tenant.tenantId(), () -> {
            jdbc.update("update documents set document_type_id = ?, document_number = 'R-77', document_date = date '2026-03-15' where id = ?",
                    typeId, tenant.documentId());
            return jdbc.queryForObject("""
                    insert into documents (id, tenant_id, original_filename, internal_name, mime_type, size_bytes, content_hash,
                                           source, processing_status, review_status, document_type_id, document_number, document_date)
                    values (gen_random_uuid(), ?, 'alt.pdf', 'alt.pdf', 'application/pdf', 100, ?, 'UPLOAD', 'READY', 'NOT_REQUIRED',
                            ?, 'R-77', date '2026-02-01')
                    returning id
                    """, UUID.class, tenant.tenantId(), "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()), typeId);
        });
        Runnable rerun = () -> asTenant(tenant.tenantId(), () -> {
            Document document = documents.findById(tenant.documentId()).orElseThrow();
            var version = versions.findFirstByDocumentIdOrderByVersionDesc(document.getId()).orElseThrow();
            return matching.match(document, version, null);
        });
        rerun.run();
        rerun.run();

        var rows = asTenant(tenant.tenantId(), () -> jdbc.queryForList("""
                select source_document_id, target_document_id, status from document_relations where relation_type = 'SUPERSEDES'
                """));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("source_document_id")).isEqualTo(tenant.documentId());
        assertThat(rows.get(0).get("target_document_id")).isEqualTo(older);
        assertThat(rows.get(0).get("status")).isEqualTo("SUGGESTED");
    }

    @Test
    @DisplayName("a contact is found by the IBAN or VAT id written in the letter, in any spelling, and only proposed")
    void contactFoundByIdentifierInAnySpelling() throws Exception {
        String text = invoiceText("2026-120") + "USt-IdNr.: DE 123 456 789\n"
                + "IBAN: DE89 3704 0044 0532 0130 00\n";
        Tenant tenant = setUpTenant("2026-120", text);
        asTenant(tenant.tenantId(), () -> identifiers.add(tenant.tenantId(), tenant.contactId(),
                de.buerokratiecopilot.contacts.identifiers.IdentifierKind.IBAN, "DE89370400440532013000", tenant.userId()));
        advance(tenant.documentId());

        var proposals = asTenant(tenant.tenantId(), () -> matches.findByDocumentId(tenant.documentId()).stream()
                .filter(m -> m.getMatchType() == DocumentMatch.MatchType.CONTACT
                        && m.getTargetId().equals(tenant.contactId()))
                .toList());
        assertThat(proposals).hasSize(1);
        assertThat(proposals.get(0).getScore().doubleValue()).isGreaterThanOrEqualTo(0.92);
        assertThat(proposals.get(0).getStatus()).as("a contact is never linked silently")
                .isEqualTo(DocumentMatch.Status.SUGGESTED);
    }

    @Test
    @DisplayName("two contacts with the same name are not mistaken for one: a name alone proposes nothing")
    void nameAloneProposesNothing() throws Exception {
        String text = "Beispiel Handels GmbH\n" + invoiceText("2026-121");
        Tenant tenant = setUpTenant("2026-121", text);
        UUID namesake = asTenant(tenant.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (id, tenant_id, contact_type, display_name, organization_name)
                values (gen_random_uuid(), ?, 'ORGANIZATION', 'Beispiel Handels GmbH', 'Beispiel Handels GmbH')
                returning id
                """, UUID.class, tenant.tenantId()));
        advance(tenant.documentId());

        var forNamesake = asTenant(tenant.tenantId(), () -> matches.findByDocumentId(tenant.documentId()).stream()
                .filter(m -> m.getTargetId().equals(namesake)).toList());
        assertThat(forNamesake).isEmpty();
    }

    @Test
    @DisplayName("an identifier already held by another contact is refused, naming that contact")
    void identifierIsHeldByOneContactOnly() throws Exception {
        Tenant tenant = setUpTenant("2026-122");
        UUID other = asTenant(tenant.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (id, tenant_id, contact_type, display_name)
                values (gen_random_uuid(), ?, 'ORGANIZATION', 'Zweite Firma') returning id
                """, UUID.class, tenant.tenantId()));
        asTenant(tenant.tenantId(), () -> identifiers.add(tenant.tenantId(), tenant.contactId(),
                de.buerokratiecopilot.contacts.identifiers.IdentifierKind.VAT_ID, "DE 987 654 321", tenant.userId()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> asTenant(tenant.tenantId(), () -> identifiers.add(
                        tenant.tenantId(), other, de.buerokratiecopilot.contacts.identifiers.IdentifierKind.VAT_ID,
                        "DE987654321", tenant.userId())))
                .hasMessageContaining("Muster GmbH");
    }

    @Test
    @DisplayName("a document with an open proposal is not marked ready")
    void openProposalKeepsTheDocumentInReview() throws Exception {
        Tenant tenant = setUpTenant("2026-118");
        advance(tenant.documentId());

        Document document = documents.findById(tenant.documentId()).orElseThrow();
        assertThat(document.getProcessingStatus())
                .as("the next stage must not act on a link nobody confirmed")
                .isEqualTo(Document.ProcessingStatus.REVIEW_REQUIRED);
        assertThat(document.getReviewStatus()).isEqualTo(Document.ReviewStatus.PENDING);
    }

    @Test
    @DisplayName("once the last question about a document is answered, processing finishes and the document is ready")
    void answeredQuestionsMakeTheDocumentReady() throws Exception {
        Tenant tenant = setUpTenant("2026-123");
        advance(tenant.documentId());
        assertThat(documents.findById(tenant.documentId()).orElseThrow().getProcessingStatus())
                .isEqualTo(Document.ProcessingStatus.REVIEW_REQUIRED);

        asTenant(tenant.tenantId(), () -> jdbc.update("""
                update document_reviews set status = 'APPROVED', decided_by = ?, decided_at = now()
                where document_id = ? and status in ('PENDING', 'IN_REVIEW')
                """, tenant.userId(), tenant.documentId()));
        advance(tenant.documentId());

        Document document = documents.findById(tenant.documentId()).orElseThrow();
        assertThat(document.getProcessingStatus()).isEqualTo(Document.ProcessingStatus.READY);
        assertThat(document.getReviewStatus()).isEqualTo(Document.ReviewStatus.APPROVED);
    }
}
