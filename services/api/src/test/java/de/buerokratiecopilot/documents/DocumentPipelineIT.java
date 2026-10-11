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

    @Autowired
    private DocumentProcessingStepRepository steps;

    private String uploadDocument() throws Exception {
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
        MockMultipartFile file = new MockMultipartFile("file", "Rechnung.pdf", "application/pdf",
                de.buerokratiecopilot.support.TestDocuments.pdf("Rechnung 2026-001 Brutto 119,00 EUR"));
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
                DocumentProcessingStep.Step.FORMAT_DETECT, DocumentProcessingStep.Step.TEXT_EXTRACT);
        // A PDF with a text layer needs no OCR, so the extraction stage did its job and the next
        // stage is the one without an implementation.
        assertThat(result.stoppedAt()).isEqualTo(DocumentProcessingStep.Step.OCR);
        assertThat(result.stopReason()).contains("No handler is registered");
        assertThat(result.status()).isEqualTo(Document.ProcessingStatus.EXTRACTING);

        Document stored = RequestContext.runAs(tenant, () -> documents.findById(id).orElseThrow());
        assertThat(stored.getExtractedText())
                .as("the text of the uploaded PDF is stored with the document")
                .contains("Rechnung 2026-001");

        // The unimplemented step stays PENDING: it is waiting, not skipped and not succeeded.
        assertThat(steps.findByDocumentIdAndStep(id, DocumentProcessingStep.Step.OCR))
                .isPresent()
                .get()
                .satisfies(step -> assertThat(step.getStatus())
                        .isEqualTo(DocumentProcessingStep.Status.PENDING));
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
