package de.buerokratiecopilot.documents.classification;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.documents.DocumentClassificationRepository;
import de.buerokratiecopilot.documents.DocumentProcessingStepRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.DocumentTypeRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/**
 * Verifies classification against the seeded taxonomy (spec §9.3, §9.4, §32).
 *
 * <p>The cases are the three decisions the specification cares about: a document the rules recognise
 * confidently, one they recognise but that always needs a human, and one they cannot place at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassificationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentTypeRepository types;

    @Autowired
    private DocumentClassificationRepository classifications;

    @Autowired
    private DocumentProcessingStepRepository steps;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    private String uploadDocument(byte[] content, String filename) throws Exception {
        String email = "klass-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Klassifikation Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = registration.getResponse().getContentAsString();
        String tenantId = JsonPath.read(body, "$.tenantId");
        String userId = JsonPath.read(body, "$.userId");

        RequestContext.runAs(UUID.fromString(tenantId), () -> {
            User user = users.findById(UUID.fromString(userId)).orElseThrow();
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

        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf", content);
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents").file(file).with(csrf())
                        .cookie(session))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(upload.getResponse().getContentAsString(), "$.document.id");
    }

    private DocumentProcessingPipeline.AdvanceResult advance(UUID documentId) {
        UUID tenant = documents.findById(documentId).orElseThrow().getTenantId();
        return RequestContext.runAs(tenant, () -> pipeline.advance(documentId));
    }

    @Test
    @DisplayName("an invoice is recognised from its own vocabulary and processing continues")
    void recognisesInvoice() throws Exception {
        // Text that the seeded rules describe: invoice field labels and a payment term.
        UUID id = UUID.fromString(uploadDocument(TestDocuments.pdf(
                "Rechnungsnummer 2026-001\nRechnungsbetrag netto 100,00 EUR\n"
                        + "Umsatzsteuer 19,00 EUR\nZahlbar bis 31.03.2026"),
                "Rechnung 2026-001.pdf"));

        advance(id);

        Document stored = documents.findById(id).orElseThrow();
        assertThat(stored.getDocumentTypeId())
                .as("the invoice type is applied when the rules agree")
                .isNotNull();
        assertThat(types.findById(stored.getDocumentTypeId()).orElseThrow().getCode())
                .isEqualTo("rechnung");

        // The classifications are protected the same way: without a tenant context the read sees nothing.
        UUID tenantOfDocument = documents.findById(id).orElseThrow().getTenantId();
        var classification = RequestContext.runAs(tenantOfDocument,
                () -> classifications.findByDocumentIdAndCurrentTrue(id)).orElseThrow();
        assertThat(classification.getMethod()).isEqualTo("RULE");
        assertThat(classification.getConfidence().doubleValue()).isGreaterThanOrEqualTo(0.85);
        assertThat(classification.getRationale()).contains("rechnungsnummer");
    }

    @Test
    @DisplayName("a contract always goes to human review, however confident the classifier is")
    void contractAlwaysRequiresReview() throws Exception {
        UUID id = UUID.fromString(uploadDocument(TestDocuments.pdf(
                "Vertragsgegenstand: Mietvertrag über die Wohnung\n"
                        + "Vertragsparteien: Vermieter und Mieter\nLaufzeit 24 Monate\n"
                        + "Kündigungsfrist drei Monate"),
                "Mietvertrag.pdf"));

        advance(id);

        Document stored = documents.findById(id).orElseThrow();
        assertThat(stored.getProcessingStatus())
                .as("legal document types must be seen by a person")
                .isEqualTo(Document.ProcessingStatus.REVIEW_REQUIRED);
        assertThat(stored.getReviewStatus()).isEqualTo(Document.ReviewStatus.PENDING);
    }

    @Test
    @DisplayName("a document the rules cannot place keeps its type empty and is sent to review")
    void unknownDocumentIsNotGuessed() throws Exception {
        UUID id = UUID.fromString(uploadDocument(TestDocuments.pdf(
                "Zwölf Boxkämpfer jagen Eva quer über den großen Sylter Deich. "
                        + "Falsches Üben von Xylophonmusik quält jeden größeren Zwerg."),
                "Notiz.pdf"));

        advance(id);

        Document stored = documents.findById(id).orElseThrow();
        assertThat(stored.getDocumentTypeId())
                .as("no type may be invented for a document the rules do not recognise")
                .isNull();
        assertThat(stored.getProcessingStatus()).isEqualTo(Document.ProcessingStatus.REVIEW_REQUIRED);

        DocumentProcessingStep classify = steps
                .findByDocumentIdAndStep(id, DocumentProcessingStep.Step.CLASSIFY).orElseThrow();
        assertThat(classify.getStatus()).isEqualTo(DocumentProcessingStep.Status.SUCCEEDED);
        assertThat(classify.getDiagnostics().get("rationale").toString())
                .contains("No configured rule matched");
    }

    @Test
    @DisplayName("the language is named with evidence, and stays empty when the evidence is weak")
    void detectsLanguageOnlyWithEvidence() throws Exception {
        UUID german = UUID.fromString(uploadDocument(TestDocuments.pdf(
                "Sehr geehrte Damen und Herren, bitte senden Sie uns die Unterlagen. "
                        + "Die Frist ist der 31.03.2026 und wir bitten um Rückmeldung."),
                "Anschreiben.pdf"));
        advance(german);
        assertThat(documents.findById(german).orElseThrow().getLanguage()).isEqualTo("de");

        UUID unclear = UUID.fromString(uploadDocument(TestDocuments.pdf("12345"), "Zahlen.pdf"));
        advance(unclear);
        assertThat(documents.findById(unclear).orElseThrow().getLanguage())
                .as("a language guessed from three digits would be worse than none")
                .isNull();
    }
}
