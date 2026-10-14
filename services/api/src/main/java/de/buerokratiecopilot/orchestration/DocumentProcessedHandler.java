package de.buerokratiecopilot.orchestration;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.outbox.OutboxEvent;
import de.buerokratiecopilot.shared.outbox.OutboxEventHandler;

/**
 * Hands a fully processed document to the {@link CaseOrchestrator}.
 *
 * <p>The pipeline announces {@code document.processing_completed} when its last step has run, and again
 * after a review has been decided and the pipeline resumed, so one handler covers both the automatic path
 * and the path through a person. The orchestrator is idempotent, which is what makes the second
 * announcement harmless.</p>
 */
@Component
public class DocumentProcessedHandler implements OutboxEventHandler {

    private final CaseOrchestrator orchestrator;

    public DocumentProcessedHandler(CaseOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public String eventType() {
        return "document.processing_completed";
    }

    @Override
    public void handle(OutboxEvent event) {
        orchestrator.orchestrate(event.getAggregateId());
    }
}
