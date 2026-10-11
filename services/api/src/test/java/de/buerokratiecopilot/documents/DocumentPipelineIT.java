package de.buerokratiecopilot.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
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

import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies the processing pipeline (spec §9.1): steps are recorded, the document advances only along
 * allowed transitions, and a step whose implementation does not exist yet is reported as pending
 * instead of being simulated as successful.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentPipelineIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private DocumentRepository documents;

    @Autowired
    private DocumentProcessingPipeline pipeline;

    /** Text the seeded classification rules recognise as an invoice. */
    static final String INVOICE_TEXT = "Rechnungsnummer 2026-001\n"
            + "Rechnungsbetrag netto 100,00 EUR\nUmsatzsteuer 19,00 EUR\nZahlbar bis 31.03.2026";

    @Autowired
    private DocumentProcessingStepRepository steps;

    private String uploadDocument() throws Exception {
        return uploadDocument(de.buerokratiecopilot.support.TestDocuments.pdf(DocumentPipelineIT.INVOICE_TEXT),
                "Rechnung.pdf");
    }

    private String uploadDocument(byte[] content, String filename) throws Exception {
        String email = "pipeline-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Pipeline Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = registration.getResponse().getContentAsString();
        String userId = JsonPath.read(body, "$.userId");
        String tenantId = JsonPath.read(body, "$.tenantId");

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

        // A real PDF: the extraction stage parses it with PDFBox, and a hand-written byte string
        // that merely looks like a PDF header would fail there.
        MockMultipartFile file = new MockMultipartFile("file", filename, "application/pdf", content);
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents").file(file).with(csrf())
                        .cookie(session))
                .andExpect(status().isCreated())
                .andReturn();

        return JsonPath.read(upload.getResponse().getContentAsString(), "$.document.id");
    }

    @Test
    @DisplayName("the upload records the steps it really performed")
    void uploadRecordsItsSteps() throws Exception {
        String documentId = uploadDocument();
        UUID id = UUID.fromString(documentId);

        List<DocumentProcessingStep> history = RequestContext.runAs(tenantOf(id), () -> pipeline.history(id));

        assertThat(history).extracting(DocumentProcessingStep::getStep)
                .contains(DocumentProcessingStep.Step.SECURITY_SCAN,
                        DocumentProcessingStep.Step.HASH,
                        DocumentProcessingStep.Step.DUPLICATE_CHECK);
        assertThat(history).allSatisfy(step ->
                assertThat(step.getStatus()).isEqualTo(DocumentProcessingStep.Status.SUCCEEDED));
        assertThat(steps.findByDocumentIdAndStep(id, DocumentProcessingStep.Step.SECURITY_SCAN))
                .isPresent()
                .get()
                .satisfies(step -> assertThat(step.getDiagnostics()).containsKeys(
                        "structuralVerdict", "malwareVerdict"));
    }

    @Test
    @DisplayName("the pipeline extracts the text and stops at the step that is still missing")
    void pipelineExtractsTextAndStopsAtOcr() throws Exception {
        UUID id = UUID.fromString(uploadDocument());
        UUID tenant = tenantOf(id);

        DocumentProcessingPipeline.AdvanceResult result =
                RequestContext.runAs(tenant, () -> pipeline.advance(id));

        assertThat(result.executed()).containsExactly(
                DocumentProcessingStep.Step.FORMAT_DETECT, DocumentProcessingStep.Step.TEXT_EXTRACT,
                DocumentProcessingStep.Step.OCR, DocumentProcessingStep.Step.LANGUAGE_DETECT,
                DocumentProcessingStep.Step.CLASSIFY, DocumentProcessingStep.Step.FIELD_EXTRACT);
        // A PDF with a text layer needs no OCR; the pipeline then detects the language, classifies
        // the document as an invoice, reads the declared fields and stops at the stage that has no
        // implementation yet.
        assertThat(result.stoppedAt()).isEqualTo(DocumentProcessingStep.Step.MATCHING);
        assertThat(result.stopReason()).contains("No handler is registered");
        assertThat(result.status()).isEqualTo(Document.ProcessingStatus.CLASSIFYING);

        Document stored = RequestContext.runAs(tenant, () -> documents.findById(id).orElseThrow());
        assertThat(stored.getExtractedText())
                .as("the text of the uploaded PDF is stored with the document")
                .contains("Rechnungsnummer 2026-001");

        // The unimplemented step stays PENDING: it is waiting, not skipped and not succeeded.
        assertThat(steps.findByDocumentIdAndStep(id, DocumentProcessingStep.Step.MATCHING))
                .isPresent()
                .get()
                .satisfies(step -> assertThat(step.getStatus())
                        .isEqualTo(DocumentProcessingStep.Status.PENDING));
    }

    @Test
    @DisplayName("a scanned document is routed to OCR and, without an engine, to human review")
    void scannedDocumentIsNotPretendedToBeRead() throws Exception {
        // Three pages without any text layer, as a scanner produces.
        UUID id = UUID.fromString(uploadDocument(
                de.buerokratiecopilot.support.TestDocuments.scannedPdf(3), "Scan.pdf"));
        UUID tenant = tenantOf(id);

        DocumentProcessingPipeline.AdvanceResult result =
                RequestContext.runAs(tenant, () -> pipeline.advance(id));

        // Text extraction found no text layer and handed the document to the OCR stage.
        // Text extraction found no text layer, so the OCR stage is reached; the later stages are
        // not, because the document has nothing to classify and no language to detect.
        assertThat(result.executed()).containsExactly(
                DocumentProcessingStep.Step.FORMAT_DETECT, DocumentProcessingStep.Step.TEXT_EXTRACT,
                DocumentProcessingStep.Step.OCR);

        // No OCR engine is configured in the test profile, so the OCR step is reported as skipped
        // with the reason, and the document goes to a human instead of being treated as blank.
        DocumentProcessingStep ocr = steps.findByDocumentIdAndStep(id, DocumentProcessingStep.Step.OCR)
                .orElseThrow();
        assertThat(ocr.getStatus())
                .as("a skipped step is not a failure and not a success")
                .isEqualTo(DocumentProcessingStep.Status.SKIPPED);
        assertThat(ocr.getErrorMessage()).contains("No OCR engine");
        assertThat(ocr.getErrorMessage()).contains("3 page(s)");

        Document stored = RequestContext.runAs(tenant, () -> documents.findById(id).orElseThrow());
        assertThat(stored.getProcessingStatus()).isEqualTo(Document.ProcessingStatus.REVIEW_REQUIRED);
        assertThat(stored.getExtractedText()).isEmpty();
    }

    @Test
    @DisplayName("running the pipeline again changes nothing")
    void pipelineIsIdempotent() throws Exception {
        UUID id = UUID.fromString(uploadDocument());
        UUID tenant = tenantOf(id);

        RequestContext.runAs(tenant, () -> pipeline.advance(id));
        DocumentProcessingPipeline.AdvanceResult second =
                RequestContext.runAs(tenant, () -> pipeline.advance(id));

        assertThat(second.progressed())
                .as("the finished step must not run twice")
                .isFalse();
        assertThat(steps.findByDocumentIdAndStep(id, DocumentProcessingStep.Step.FORMAT_DETECT))
                .isPresent()
                .get()
                .satisfies(step -> assertThat(step.getAttempt())
                        .as("an already finished step is not attempted again")
                        .isEqualTo(1));
    }

    @Test
    @DisplayName("only a failed step can be retried")
    void onlyFailedStepsCanBeRetried() throws Exception {
        UUID id = UUID.fromString(uploadDocument());
        UUID tenant = tenantOf(id);
        RequestContext.runAs(tenant, () -> pipeline.advance(id));

        assertThatThrownBy(() -> RequestContext.runAs(tenant,
                () -> pipeline.retryStep(id, DocumentProcessingStep.Step.TEXT_EXTRACT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only a failed step can be retried");
    }

    private UUID tenantOf(UUID documentId) {
        Document document = documents.findById(documentId).orElseThrow();
        return document.getTenantId();
    }
}
