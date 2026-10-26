package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.documents.DocumentPageRepository;
import de.buerokratiecopilot.documents.DocumentRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentPage;
import de.buerokratiecopilot.documents.domain.DocumentProcessingStep;
import de.buerokratiecopilot.documents.ocr.NoOpOcrEngine;
import de.buerokratiecopilot.documents.ocr.OcrEngine;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;

/**
 * {@code doc-ocr}: the honest branches — no text needed, OCR off with acceptance, OCR off without
 * acceptance, and the pipeline path when an engine is available.
 */
class DocOcrAgentTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    private DocumentRepository documents;
    private DocumentPageRepository pages;
    private DocumentProcessingPipeline pipeline;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        pages = mock(DocumentPageRepository.class);
        pipeline = mock(DocumentProcessingPipeline.class);
        environment = new MockEnvironment();
    }

    private Document document() {
        return new Document(documentId, tenantId, "scan.pdf", "stored/scan.pdf", "application/pdf",
                1200, "a".repeat(64), Document.Source.SCANNER);
    }

    private AgentContext ctx(String documentId) {
        return new AgentContext(tenantId, UUID.randomUUID(), "manual", null,
                Map.of("documentId", documentId));
    }

    @Test
    @DisplayName("pages that already carry text need no OCR: the agent says so instead of inventing work")
    void pagesWithTextNeedNoOcr() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document()));
        DocumentPage page = new DocumentPage(UUID.randomUUID(), documentId, UUID.randomUUID(), 1);
        page.setTextContent("Rechnung");
        when(pages.findByDocumentIdOrderByPageNumberAsc(documentId)).thenReturn(List.of(page));

        DocOcrAgent agent = new DocOcrAgent(documents, pages, new NoOpOcrEngine(), pipeline,
                environment);
        AgentResult result = agent.run(ctx(documentId.toString()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine OCR nötig");
        verify(pipeline, never()).advance(any());
    }

    @Test
    @DisplayName("with OCR switched off and BC_ALLOW_NO_OCR set, the refusal is honest and explains why")
    void disabledOcrWithAcceptanceIsAnHonestSkip() {
        environment.withProperty("BC_ALLOW_NO_OCR", "true");
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document()));
        when(pages.findByDocumentIdOrderByPageNumberAsc(documentId)).thenReturn(List.of());

        DocOcrAgent agent = new DocOcrAgent(documents, pages, new NoOpOcrEngine(), pipeline,
                environment);
        AgentResult result = agent.run(ctx(documentId.toString()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("BC_ALLOW_NO_OCR");
        verify(pipeline, never()).advance(any());
    }

    @Test
    @DisplayName("with OCR switched off and no acceptance, the failure says what to do")
    void disabledOcrWithoutAcceptanceFails() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document()));
        when(pages.findByDocumentIdOrderByPageNumberAsc(documentId)).thenReturn(List.of());

        DocOcrAgent agent = new DocOcrAgent(documents, pages, new NoOpOcrEngine(), pipeline,
                environment);
        AgentResult result = agent.run(ctx(documentId.toString()));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("BC_ALLOW_NO_OCR");
        verify(pipeline, never()).advance(any());
    }

    @Test
    @DisplayName("with an engine available the existing pipeline prepares the text")
    void availableEngineAdvancesThePipeline() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(documentId, tenantId))
                .thenReturn(Optional.of(document()));
        DocumentPage emptyPage = new DocumentPage(UUID.randomUUID(), documentId, UUID.randomUUID(), 1);
        when(pages.findByDocumentIdOrderByPageNumberAsc(documentId)).thenReturn(List.of(emptyPage));
        when(pipeline.advance(documentId)).thenReturn(new DocumentProcessingPipeline.AdvanceResult(
                documentId, DocumentProcessingStep.Step.MATCHING, null,
                Document.ProcessingStatus.READY, List.of(DocumentProcessingStep.Step.TEXT_EXTRACT,
                        DocumentProcessingStep.Step.OCR)));

        OcrEngine engine = new StubEngine(true);
        DocOcrAgent agent = new DocOcrAgent(documents, pages, engine, pipeline, environment);
        AgentResult result = agent.run(ctx(documentId.toString()));

        assertThat(result.ok()).isTrue();
        assertThat(result.summary()).contains("OCR");
        verify(pipeline).advance(documentId);
    }

    @Test
    @DisplayName("without a documentId the agent reports honestly instead of guessing")
    void missingDocumentIdIsSkipped() {
        DocOcrAgent agent = new DocOcrAgent(documents, pages, new NoOpOcrEngine(), pipeline,
                environment);
        AgentResult result = agent.run(ctx(""));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("documentId");
    }

    /** The external OCR engine is the one part worth faking: it is a local process, not logic. */
    private static final class StubEngine implements OcrEngine {

        private final boolean available;

        private StubEngine(boolean available) {
            this.available = available;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public List<String> availableLanguages() {
            return List.of("deu");
        }

        @Override
        public Result recognise(byte[] image, List<String> languages) {
            return Result.empty(name(), "deu");
        }
    }
}
