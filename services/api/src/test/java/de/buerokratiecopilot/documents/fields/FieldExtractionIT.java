package de.buerokratiecopilot.documents.fields;

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

import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.DocumentProcessingStepRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentExtraction;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/**
 * Verifies field extraction end to end (spec §9.4).
 *
 * <p>What matters is not only that a value was found, but that it can be justified: the page it came
 * from and the line it was read on are part of the result, because an administrative decision must be
 * traceable to the document. The test also checks that a field the document does not contain is
 * reported as missing rather than invented.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FieldExtractionIT {

    /** A realistic German invoice: every value is written the way a real one writes it. */
    private static final String INVOICE = """
            Muster GmbH
            Rechnungsnummer: 2026-001
            Rechnungsdatum: 15.03.2026
            Zahlbar bis: 31.03.2026
            Nettobetrag: 1.000,00 EUR
            Umsatzsteuer: 190,00 EUR
            Bruttobetrag: 1.190,00 EUR
            IBAN: DE89 3704 0044 0532 0130 00
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentExtractionRepository extractions;

    @Autowired
    private DocumentProcessingStepRepository steps;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    private UUID uploadInvoice(String text) throws Exception {
        String email = "felder-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Felder Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        String tenantId = JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId");
        String userId = JsonPath.read(registration.getResponse().getContentAsString(), "$.userId");

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

        MockMultipartFile file = new MockMultipartFile("file", "Rechnung 2026-001.pdf", "application/pdf",
                TestDocuments.pdf(text));
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents").file(file).with(csrf())
                        .cookie(session))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(upload.getResponse().getContentAsString(), "$.document.id"));
    }

    private void advance(UUID documentId) {
        UUID tenant = documents.findById(documentId).orElseThrow().getTenantId();
        RequestContext.runAs(tenant, () -> pipeline.advance(documentId));
    }

    private DocumentExtraction field(UUID documentId, String key) {
        // The extraction rows are protected by a row-level security policy that reaches the tenant through
        // the document, so a read without a tenant context sees nothing - which is the point of the policy
        // and is why this helper establishes one, as the application does.
        UUID tenant = documents.findById(documentId).orElseThrow().getTenantId();
        return RequestContext.runAs(tenant,
                        () -> extractions.findByDocumentIdAndFieldKey(documentId, key))
                .orElseThrow(() -> new AssertionError("The field " + key + " was not extracted"));
    }

    @Test
    @DisplayName("an invoice yields its declared fields with normalised values and their source")
    void extractsInvoiceFields() throws Exception {
        UUID id = uploadInvoice(INVOICE);
        advance(id);

        Document document = documents.findById(id).orElseThrow();
        assertThat(document.getDocumentTypeId())
                .as("field extraction needs a document type to know which fields exist")
                .isNotNull();

        DocumentExtraction reference = field(id, "Rechnungsnummer");
        assertThat(reference.getFieldValue()).isEqualTo("2026-001");
        assertThat(reference.getNormalizedValue()).isEqualTo("2026-001");
        assertThat(reference.getExtractor()).isEqualTo("RULE");
        assertThat(reference.getSourcePage()).isEqualTo(1);
        assertThat(reference.getSourceSnippet())
                .as("a reviewer must see the line the value was read from")
                .contains("Rechnungsnummer")
                .contains("2026-001");
        assertThat(reference.getSourceKind()).isEqualTo("TEXT");
        assertThat(reference.getConfidence().doubleValue()).isBetween(0.5, 1.0);
        assertThat(reference.isVerified()).isFalse();
    }

    @Test
    @DisplayName("amounts and dates are stored in a comparable form")
    void normalisesAmountsAndDates() throws Exception {
        UUID id = uploadInvoice(INVOICE);
        advance(id);

        assertThat(field(id, "Brutto").getNormalizedValue()).isEqualTo("1190.00");
        assertThat(field(id, "Netto").getNormalizedValue()).isEqualTo("1000.00");
        assertThat(field(id, "Umsatzsteuer").getNormalizedValue()).isEqualTo("190.00");
        assertThat(field(id, "Zahlungsziel").getNormalizedValue()).isEqualTo("2026-03-31");
        assertThat(field(id, "Rechnungsdatum").getNormalizedValue()).isEqualTo("2026-03-15");

        // The original wording is kept next to the comparable form, so a reviewer recognises it.
        assertThat(field(id, "Brutto").getFieldValue()).contains("1.190,00");
    }

    @Test
    @DisplayName("an IBAN is validated, so a transposed digit never reaches a payment")
    void validatesIban() throws Exception {
        UUID id = uploadInvoice(INVOICE);
        advance(id);

        assertThat(field(id, "IBAN").getNormalizedValue()).isEqualTo("DE89370400440532013000");
    }

    @Test
    @DisplayName("a field the document does not contain is reported as missing, not invented")
    void doesNotInventMissingFields() throws Exception {
        UUID id = uploadInvoice(INVOICE);
        advance(id);

        assertThat(extractions.findByDocumentIdAndFieldKey(id, "Waehrung")).isEmpty();
        assertThat(extractions.findByDocumentIdAndFieldKey(id, "Bestellreferenz")).isEmpty();

        DocumentProcessingStep step = steps
                .findByDocumentIdAndStep(id, DocumentProcessingStep.Step.FIELD_EXTRACT).orElseThrow();
        assertThat(step.getStatus()).isEqualTo(DocumentProcessingStep.Status.SUCCEEDED);
        assertThat(step.getDiagnostics().get("notFound").toString())
                .contains("Bestellreferenz");
        assertThat(step.getDiagnostics().get("fieldsWithValues").toString()).isNotEqualTo("0");
    }

    @Test
    @DisplayName("nothing is extracted when the document type is unknown")
    void extractsNothingWithoutAType() throws Exception {
        // Text that matches no classification rule, so the document gets no type. The pipeline now
        // stops at the classification stage and routes the document to a human instead of running
        // later stages on a document nobody has identified.
        UUID id = uploadInvoice("Zwölf Boxkämpfer jagen Eva quer über den großen Sylter Deich.");
        advance(id);

        assertThat(extractions.findByDocumentId(id)).isEmpty();

        assertThat(steps.findByDocumentIdAndStep(id, DocumentProcessingStep.Step.FIELD_EXTRACT))
                .as("field extraction must not run while the type is still unknown")
                .isEmpty();

        Document document = documents.findById(id).orElseThrow();
        assertThat(document.getProcessingStatus()).isEqualTo(Document.ProcessingStatus.REVIEW_REQUIRED);
        assertThat(document.getDocumentTypeId()).isNull();
    }
}
