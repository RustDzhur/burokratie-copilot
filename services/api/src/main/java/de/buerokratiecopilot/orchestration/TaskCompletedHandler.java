package de.buerokratiecopilot.orchestration;

import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.outbox.OutboxEvent;
import de.buerokratiecopilot.shared.outbox.OutboxEventHandler;

/** Decides, when a task is completed, whether the matter still waits for a confirmation. */
@Component
public class TaskCompletedHandler implements OutboxEventHandler {

    private final FollowUpService followUps;

    public TaskCompletedHandler(FollowUpService followUps) {
        this.followUps = followUps;
    }

    @Override
    public String eventType() {
        return "task.completed";
    }

    @Override
    public void handle(OutboxEvent event) {
        followUps.onTaskCompleted(event.getAggregateId());
    }
}
