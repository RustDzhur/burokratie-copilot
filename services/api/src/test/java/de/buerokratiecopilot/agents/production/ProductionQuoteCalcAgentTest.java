package de.buerokratiecopilot.agents.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.core.AgentArtifactStore;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.agents.core.AgentTaskFactory;
import de.buerokratiecopilot.documents.DocumentExtractionRepository;
import de.buerokratiecopilot.documents.DocumentRepository;

class ProductionQuoteCalcAgentTest {

    private DocumentRepository documents;
    private DocumentExtractionRepository extractions;
    private AgentArtifactStore artifacts;
    private AgentTaskFactory tasks;
    private ProductionQuoteCalcAgent agent;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        documents = mock(DocumentRepository.class);
        extractions = mock(DocumentExtractionRepository.class);
        artifacts = mock(AgentArtifactStore.class);
        tasks = mock(AgentTaskFactory.class);
        agent = new ProductionQuoteCalcAgent(documents, extractions, artifacts, tasks);
    }

    @Test
    void calculatesACheckableQuoteAndStoresTheSheet() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId))
                .thenReturn(Optional.empty());
        when(artifacts.store(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentArtifactStore.Artifact(UUID.randomUUID(),
                        "tenants/key/quote.html", "kalkulation.html", "text/html", 500, true));
        when(tasks.createIfAbsent(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AgentTaskFactory.Created(UUID.randomUUID(), true));

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("productId", productId.toString(), "quantity", "2",
                        "materialCosts", "100", "hourlyRate", "50", "laborHours", "2",
                        "machineRate", "20", "machineHours", "1",
                        "overheadPercent", "10", "marginPercent", "10",
                        "discountPercent", "5")));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("total", "255.44")
                .containsEntry("unitPrice", "127.72")
                .containsEntry("material", "100.00")
                .containsEntry("labor", "100.00")
                .containsEntry("machine", "20.00");
        assertThat(result.produced()).extracting(AgentResult.Produced::kind)
                .contains("quote-calculation", "quote-assumption", "task");
        assertThat(result.summary()).contains("Kein PDF, kein Versand");
    }

    @Test
    void withoutAnyCostValueTheAgentSkipsInsteadOfGuessing() {
        when(documents.findByIdAndTenantIdAndDeletedAtIsNull(productId, tenantId))
                .thenReturn(Optional.empty());

        AgentResult result = agent.run(new AgentContext(tenantId, UUID.randomUUID(), "manual",
                Instant.parse("2026-10-27T09:00:00Z"),
                Map.of("productId", productId.toString(), "quantity", "1")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Keine belastbaren Kostenwerte");
        verifyNoInteractions(artifacts, tasks);
    }
}
