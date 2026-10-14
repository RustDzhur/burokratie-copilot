package de.buerokratiecopilot.orchestration;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.shared.outbox.OutboxEvent;
import de.buerokratiecopilot.shared.outbox.OutboxEventHandler;

/**
 * Reports a duplicate instead of letting its event end in the dead-letter queue.
 *
 * <p>A duplicate is kept (the original file and its history are never discarded) but it does not start a
 * second process: that is what "do not create a duplicate process" means. The owners are told once, with a
 * link, so a person can confirm that it really is a copy.</p>
 */
@Component
public class DocumentDuplicateHandler implements OutboxEventHandler {

    private final OwnerNotifier notifier;

    public DocumentDuplicateHandler(OwnerNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public String eventType() {
        return "document.duplicate_detected";
    }

    @Override
    public void handle(OutboxEvent event) {
        notifier.tell(event.getTenantId(), Notification.Category.DOCUMENT_REVIEW,
                Notification.Severity.INFO, "document.duplicate_detected",
                "Ein Dokument ist ein Duplikat",
                "Der Inhalt stimmt mit einem bereits vorhandenen Dokument überein. Es wurde abgelegt, "
                        + "startet aber keinen zweiten Vorgang.",
                "documents", event.getAggregateId(), "/documents/" + event.getAggregateId(),
                "doc-duplicate-" + event.getAggregateId());
    }
}
