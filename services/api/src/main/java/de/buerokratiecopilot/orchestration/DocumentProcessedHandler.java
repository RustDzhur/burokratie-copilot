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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentProcessedHandler.class);

    private final CaseOrchestrator orchestrator;
    private final de.buerokratiecopilot.documents.reading.AiDocumentReader reader;

    private final de.buerokratiecopilot.parties.AddresseeResolver addressees;
    private final de.buerokratiecopilot.telegram.TelegramInformer telegram;

    public DocumentProcessedHandler(CaseOrchestrator orchestrator,
                                    de.buerokratiecopilot.documents.reading.AiDocumentReader reader,
                                    de.buerokratiecopilot.parties.AddresseeResolver addressees,
                                    de.buerokratiecopilot.telegram.TelegramInformer telegram) {
        this.addressees = addressees;
        this.telegram = telegram;
        this.orchestrator = orchestrator;
        this.reader = reader;
    }

    @Override
    public String eventType() {
        return "document.processing_completed";
    }

    @Override
    public void handle(OutboxEvent event) {
        // The platform's own reading has finished. Only if it left a value unread that the document needs (handwriting)
        // is the AI asked, and a failure of the AI never stops the document from being organised.
        try {
            reader.readIfNeeded(event.getAggregateId());
        }
        catch (RuntimeException ex) {
            log.warn("AI reading of document {} failed, continuing without it: {}", event.getAggregateId(),
                    ex.getMessage());
        }
        // Whom the document is addressed to decides whom the work is for; a failure here never stops the document.
        try {
            addressees.resolve(event.getAggregateId());
        }
        catch (RuntimeException ex) {
            log.warn("Addressee of document {} could not be determined: {}", event.getAggregateId(), ex.getMessage());
        }
        CaseOrchestrator.Outcome outcome = orchestrator.orchestrate(event.getAggregateId());
        if (outcome != null && !"ALREADY_HANDLED".equals(outcome.result())) {
            telegram.documentProcessed(event.getTenantId(), event.getAggregateId(), outcome.result());
        }
    }
}
