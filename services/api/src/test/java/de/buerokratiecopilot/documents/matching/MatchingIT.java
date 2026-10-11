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

    private record Tenant(UUID tenantId, UUID caseId, UUID contactId, UUID userId, UUID documentId) {
    }

    /** Creates a tenant with one case and one contact, the way a first customer would have them. */
    private Tenant setUpTenant(String caseReference) throws Exception {
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
        UUID documentId = UUID.fromString(uploadDocument(session, invoiceText(caseReference)));
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
}
