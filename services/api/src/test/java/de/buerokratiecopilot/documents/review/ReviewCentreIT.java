package de.buerokratiecopilot.documents.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentReviewRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentReview;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/**
 * Verifies the review centre end to end (spec §9.6, §13).
 *
 * <p>The point of the centre is that a person can act on what the platform refused to decide. The test
 * therefore goes the whole way: a document the platform will not classify appears in the queue with a
 * reason, a reviewer chooses a type, and the document continues — now with a type, and without the
 * entry still sitting in the queue.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewCentreIT {

    /** Text that matches no classification rule, so the platform asks instead of guessing. */
    private static final String UNCLASSIFIABLE = """
            Zwölf Boxkämpfer jagen Eva quer über den großen Sylter Deich.
            Falsches Üben von Xylophonmusik quält jeden größeren Zwerg.
            """;

    private static final String INVOICE = """
            Muster GmbH
            Rechnungsnummer: 2026-701
            Rechnungsdatum: 15.03.2026
            Zahlbar bis: 31.03.2026
            Nettobetrag: 1.000,00 EUR
            Umsatzsteuer: 190,00 EUR
            Bruttobetrag: 1.190,00 EUR
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentReviewRepository reviews;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    private record Session(Cookie cookie, UUID tenantId, UUID userId, UUID documentId) {
    }

    private Session upload(String text, String filename) throws Exception {
        String email = "review-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Review Test","accountType":"COMPANY"}
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

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie session = login.getResponse().getCookie("bc_session");

        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf",
                TestDocuments.pdf(text));
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents").file(file).with(csrf())
                        .cookie(session))
                .andExpect(status().isCreated())
                .andReturn();
        UUID documentId = UUID.fromString(JsonPath.read(
                upload.getResponse().getContentAsString(), "$.document.id"));
        return new Session(session, tenantId, userId, documentId);
    }

    private void advance(Session session) {
        RequestContext.runAs(session.tenantId(), () -> pipeline.advance(session.documentId()));
    }

    @Test
    @DisplayName("a document the platform will not classify appears in the queue with the reason")
    void unclassifiableDocumentAppearsWithReason() throws Exception {
        Session session = upload(UNCLASSIFIABLE, "Notiz.pdf");
        advance(session);

        mockMvc.perform(get("/api/v1/reviews").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentId").value(session.documentId().toString()))
                .andExpect(jsonPath("$[0].type").value("CLASSIFICATION"))
                .andExpect(jsonPath("$[0].reason").isNotEmpty());

        Document document = documents.findById(session.documentId()).orElseThrow();
        assertThat(document.getProcessingStatus()).isEqualTo(Document.ProcessingStatus.REVIEW_REQUIRED);
        assertThat(document.getReviewStatus()).isEqualTo(Document.ReviewStatus.PENDING);
    }

    @Test
    @DisplayName("the detail view explains why, and shows values with their source")
    void detailExplainsWhyAndShowsProvenance() throws Exception {
        Session session = upload(INVOICE, "Rechnung.pdf");
        advance(session);

        // A confidently classified invoice is not waiting for anyone, so the detail view must still
        // be able to show its values - that is what a reviewer opens when something looks wrong.
        MvcResult result = mockMvc.perform(get("/api/v1/reviews/{id}", session.documentId())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values[?(@.fieldKey=='Rechnungsnummer')].value")
                        .value(org.hamcrest.Matchers.contains("2026-701")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"snippet\"").contains("Rechnungsnummer");
        assertThat(body).contains("\"page\":1");
    }

    @Test
    @DisplayName("a reviewer's choice of type is applied and the document continues")
    void reviewerChoosesTypeAndDocumentContinues() throws Exception {
        Session session = upload(UNCLASSIFIABLE, "Notiz.pdf");
        advance(session);

        UUID reviewId = RequestContext.runAs(session.tenantId(), () -> reviews
                .findByStatusOrderByCreatedAtAsc(DocumentReview.Status.PENDING).stream()
                .filter(review -> review.getDocumentId().equals(session.documentId()))
                .findFirst().orElseThrow().getId());

        mockMvc.perform(post("/api/v1/reviews/{id}/decision", reviewId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVED","note":"Das ist eine Mahnung",
                                 "changes":{"documentTypeCode":"mahnung"}}
                                """))
                .andExpect(status().isOk());

        Document document = documents.findById(session.documentId()).orElseThrow();
        assertThat(document.getDocumentTypeId())
                .as("the type the reviewer chose is applied")
                .isNotNull();
        assertThat(document.getReviewStatus()).isEqualTo(Document.ReviewStatus.APPROVED);

        DocumentReview review = reviews.findById(reviewId).orElseThrow();
        assertThat(review.getStatus()).isEqualTo(DocumentReview.Status.APPROVED);
        assertThat(review.getDecidedBy()).isEqualTo(session.userId());
        assertThat(review.getChanges()).containsKey("documentTypeCode");

        // And the entry is gone from the queue.
        mockMvc.perform(get("/api/v1/reviews").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.documentId=='" + session.documentId() + "')]").isEmpty());
    }

    @Test
    @DisplayName("a corrected field value is stored as verified, not as extracted")
    void correctedValueIsMarkedAsVerified() throws Exception {
        Session session = upload(INVOICE, "Rechnung.pdf");
        advance(session);

        UUID reviewId = UUID.randomUUID();
        RequestContext.runAs(session.tenantId(), () -> {
            // A review that a person raises after noticing a wrong value.
            var review = new DocumentReview(reviewId, session.tenantId(), session.documentId(), null,
                    DocumentReview.ReviewType.EXTRACTION, "Der Betrag stimmt nicht");
            return reviews.save(review);
        });

        mockMvc.perform(post("/api/v1/reviews/{id}/decision", reviewId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"APPROVED","note":"Betrag korrigiert",
                                 "changes":{"fieldValues":{"Brutto":"1.250,00"}}}
                                """))
                .andExpect(status().isOk());

        MvcResult detail = mockMvc.perform(get("/api/v1/reviews/{id}", session.documentId())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                // A JsonPath filter yields a list, so the expectation is a list as well.
                .andExpect(jsonPath("$.values[?(@.fieldKey=='Brutto')].value")
                        .value(org.hamcrest.Matchers.contains("1.250,00")))
                .andExpect(jsonPath("$.values[?(@.fieldKey=='Brutto')].normalizedValue")
                        .value(org.hamcrest.Matchers.contains("1250.00")))
                .andExpect(jsonPath("$.values[?(@.fieldKey=='Brutto')].verified")
                        .value(org.hamcrest.Matchers.contains(true)))
                .andReturn();

        assertThat(detail.getResponse().getContentAsString()).contains("1250.00");
    }

    @Test
    @DisplayName("a rejected review is recorded as rejected and does not release the document")
    void rejectionIsRecordedAndDoesNotRelease() throws Exception {
        Session session = upload(UNCLASSIFIABLE, "Notiz.pdf");
        advance(session);

        UUID reviewId = RequestContext.runAs(session.tenantId(), () -> reviews
                .findByStatusOrderByCreatedAtAsc(DocumentReview.Status.PENDING).stream()
                .filter(review -> review.getDocumentId().equals(session.documentId()))
                .findFirst().orElseThrow().getId());

        mockMvc.perform(post("/api/v1/reviews/{id}/decision", reviewId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"REJECTED","note":"Das Dokument gehört nicht hierher"}
                                """))
                .andExpect(status().isOk());

        Document document = documents.findById(session.documentId()).orElseThrow();
        assertThat(document.getReviewStatus()).isEqualTo(Document.ReviewStatus.REJECTED);
        assertThat(document.getProcessingStatus())
                .as("a rejected document does not continue by itself")
                .isEqualTo(Document.ProcessingStatus.REVIEW_REQUIRED);
    }

    @Test
    @DisplayName("the same question is not asked twice")
    void doesNotStackDuplicates() throws Exception {
        Session session = upload(UNCLASSIFIABLE, "Notiz.pdf");
        advance(session);
        // Advancing again must not add a second entry for the same open question.
        advance(session);

        long open = RequestContext.runAs(session.tenantId(), () -> reviews
                .findByDocumentIdOrderByCreatedAtDesc(session.documentId()).stream()
                .filter(DocumentReview::isOpen).count());

        assertThat(open).as("a queue with the same document twice is a queue nobody trusts").isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown decision is refused instead of silently ignored")
    void refusesUnknownDecision() throws Exception {
        Session session = upload(UNCLASSIFIABLE, "Notiz.pdf");
        advance(session);

        UUID reviewId = RequestContext.runAs(session.tenantId(), () -> reviews
                .findByStatusOrderByCreatedAtAsc(DocumentReview.Status.PENDING).stream()
                .filter(review -> review.getDocumentId().equals(session.documentId()))
                .findFirst().orElseThrow().getId());

        mockMvc.perform(post("/api/v1/reviews/{id}/decision", reviewId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest());
    }
}
