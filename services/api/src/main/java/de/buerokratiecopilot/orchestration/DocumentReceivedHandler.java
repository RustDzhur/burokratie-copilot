package de.buerokratiecopilot.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.shared.outbox.OutboxEvent;
import de.buerokratiecopilot.shared.outbox.OutboxEventHandler;

/**
 * Starts the processing pipeline when a document has been received.
 *
 * <p>This is the missing link between "the file was stored" and "the document is understood": the upload
 * and the mailbox collection announce {@code document.received}, and until this handler existed nobody
 * listened, so every document stayed at its first status for ever and the event ended in the dead-letter
 * queue.</p>
 *
 * <p>The pipeline stops at a step that failed, was skipped, or needs a person. The handler does not hide
 * that: a stop that is not a review is reported to the owners, so a document never sits unprocessed
 * without anybody being told why.</p>
 */
@Component
public class DocumentReceivedHandler implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DocumentReceivedHandler.class);

    private final DocumentProcessingPipeline pipeline;
    private final OwnerNotifier notifier;

    public DocumentReceivedHandler(DocumentProcessingPipeline pipeline, OwnerNotifier notifier) {
        this.pipeline = pipeline;
        this.notifier = notifier;
    }

    @Override
    public String eventType() {
        return "document.received";
    }

    @Override
    public void handle(OutboxEvent event) {
        var result = pipeline.advance(event.getAggregateId());
        log.info("Document {} advanced: executed {} step(s), status {}, stopped at {} ({})",
                event.getAggregateId(), result.executed().size(), result.status(), result.stoppedAt(),
                result.stopReason());

        if (result.stoppedAt() == null) {
            return;
        }
        if (result.status() == Document.ProcessingStatus.REVIEW_REQUIRED) {
            notifier.tell(event.getTenantId(), Notification.Category.DOCUMENT_REVIEW,
                    Notification.Severity.NOTICE, "document.review_required",
                    "Ein Dokument braucht Ihre Entscheidung", result.stopReason(), "documents",
                    event.getAggregateId(), "/review", "doc-review-" + event.getAggregateId());
            return;
        }
        notifier.tell(event.getTenantId(), Notification.Category.DOCUMENT_REVIEW,
                Notification.Severity.WARNING, "document.processing_stopped",
                "Ein Dokument konnte nicht vollständig verarbeitet werden",
                "Schritt " + result.stoppedAt() + ": " + result.stopReason()
                        + ". Sie können die Verarbeitung auf der Dokumentseite erneut starten.",
                "documents", event.getAggregateId(), "/documents/" + event.getAggregateId(),
                "doc-stopped-" + event.getAggregateId() + "-" + result.stoppedAt());
    }
}
