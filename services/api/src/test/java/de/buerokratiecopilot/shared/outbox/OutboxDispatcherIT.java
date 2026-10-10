package de.buerokratiecopilot.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import de.buerokratiecopilot.identity.domain.Tenant;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * Verifies the transactional outbox against the real database: atomicity with the business change,
 * delivery, deduplication of redeliveries, retry, dead-letter handling and the tenant context of
 * background work (spec §16.3, §27, §3.2).
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxDispatcherIT {

    /** A consumer that records what it received, in which tenant context, and how often. */
    static class RecordingHandler implements OutboxEventHandler {

        final List<UUID> received = new ArrayList<>();
        final List<UUID> tenantContexts = new ArrayList<>();
        int failuresBeforeSuccess;

        @Override
        public String eventType() {
            return "test.recorded";
        }

        @Override
        public void handle(OutboxEvent event) {
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--;
                throw new IllegalStateException("simulated downstream failure");
            }
            received.add(event.getEventId());
            tenantContexts.add(RequestContext.tenantId().orElse(null));
        }
    }

    /** A consumer that always fails, to exercise the dead-letter path. */
    static class FailingHandler implements OutboxEventHandler {

        @Override
        public String eventType() {
            return "test.always_fails";
        }

        @Override
        public void handle(OutboxEvent event) {
            throw new IllegalStateException("permanently broken downstream service");
        }
    }

    @TestConfiguration
    static class HandlerConfiguration {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }

        @Bean
        FailingHandler failingHandler() {
            return new FailingHandler();
        }
    }

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private RecordingHandler recordingHandler;

    @Autowired
    private TestDataFactory fixtures;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        recordingHandler.received.clear();
        recordingHandler.tenantContexts.clear();
        recordingHandler.failuresBeforeSuccess = 0;
        tenant = fixtures.createCompany();

        // Events left over from other tests must not interfere with the assertions below.
        transactions.executeWithoutResult(status -> repository.findByStatus(OutboxEvent.Status.PENDING)
                .forEach(event -> {
                    event.markPublished(Instant.now());
                    repository.save(event);
                }));
    }

    private OutboxEvent publish(String eventType, Map<String, Object> payload) {
        OutboxEvent event = transactions.execute(status -> writer.publish(
                new DomainEvent(eventType, "test_aggregate", UUID.randomUUID(), tenant.getId(), payload)));
        assertThat(event).isNotNull();
        return event;
    }

    /** Brings a failed event back to the front of the queue so the next attempt is due now. */
    private void makeDueNow(UUID eventId) {
        transactions.executeWithoutResult(status -> {
            OutboxEvent managed = repository.findById(eventId).orElseThrow();
            managed.releaseStaleLock(Instant.now());
            repository.save(managed);
        });
    }

    @Test
    @DisplayName("an event written with the business change is delivered exactly once")
    void eventIsDelivered() {
        OutboxEvent event = publish("test.recorded", Map.of("documentId", UUID.randomUUID().toString()));
        assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);

        dispatcher.dispatch();

        assertThat(recordingHandler.received).containsExactly(event.getEventId());
        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEvent.Status.PUBLISHED);
    }

    @Test
    @DisplayName("a rolled back transaction leaves no event behind")
    void rollbackLeavesNoEvent() {
        long before = repository.count();

        assertThatThrownBy(() -> transactions.execute(status -> {
            writer.publish(new DomainEvent("test.recorded", "test_aggregate", UUID.randomUUID(),
                    tenant.getId(), Map.of()));
            throw new IllegalStateException("business change failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.count()).as("the announcement must vanish with the change").isEqualTo(before);
    }

    @Test
    @DisplayName("publishing outside a transaction is refused instead of announcing nothing")
    void publishRequiresTransaction() {
        DomainEvent event = new DomainEvent("test.recorded", "test_aggregate", UUID.randomUUID(),
                tenant.getId(), Map.of());

        assertThatThrownBy(() -> writer.publish(event))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("a redelivered event is not handled twice")
    void redeliveryIsDeduplicated() {
        OutboxEvent event = publish("test.recorded", Map.of());
        dispatcher.dispatch();
        assertThat(recordingHandler.received).hasSize(1);

        // Simulate a redelivery after a crash: the same business event is queued again.
        makeDueNow(event.getId());
        dispatcher.dispatch();

        assertThat(recordingHandler.received)
                .as("the consumer has already recorded this event")
                .hasSize(1);
    }

    @Test
    @DisplayName("a failing consumer is retried and finally parked as a dead letter")
    void failuresEndInDeadLetter() {
        OutboxEvent event = publish("test.always_fails", Map.of());

        dispatcher.dispatch();
        OutboxEvent afterFirst = repository.findById(event.getId()).orElseThrow();
        assertThat(afterFirst.getStatus()).isEqualTo(OutboxEvent.Status.FAILED);
        assertThat(afterFirst.getAttempts()).isEqualTo(1);
        assertThat(afterFirst.getErrorMessage()).contains("permanently broken");
        assertThat(afterFirst.getAvailableAt()).isAfter(Instant.now());

        // The attempt budget (2 in the test profile) is exhausted on the next run.
        makeDueNow(event.getId());
        dispatcher.dispatch();

        OutboxEvent afterSecond = repository.findById(event.getId()).orElseThrow();
        assertThat(afterSecond.getStatus()).isEqualTo(OutboxEvent.Status.DEAD_LETTER);
        assertThat(afterSecond.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("an event without a registered consumer is parked, not retried forever")
    void unknownEventTypeIsParked() {
        OutboxEvent event = publish("test.nobody_listens", Map.of());

        dispatcher.dispatch();

        OutboxEvent stored = repository.findById(event.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OutboxEvent.Status.DEAD_LETTER);
        assertThat(stored.getErrorMessage()).contains("No handler registered");
    }

    @Test
    @DisplayName("a transient failure is retried and then succeeds")
    void transientFailureIsRetried() {
        recordingHandler.failuresBeforeSuccess = 1;
        OutboxEvent event = publish("test.recorded", Map.of());

        dispatcher.dispatch();
        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEvent.Status.FAILED);

        makeDueNow(event.getId());
        dispatcher.dispatch();

        assertThat(recordingHandler.received).containsExactly(event.getEventId());
        assertThat(repository.findById(event.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEvent.Status.PUBLISHED);
    }

    @Test
    @DisplayName("the consumer runs inside the tenant context of the event")
    void consumerRunsInTenantContext() {
        OutboxEvent event = publish("test.recorded", Map.of());

        dispatcher.dispatch();

        assertThat(recordingHandler.received).containsExactly(event.getEventId());
        assertThat(recordingHandler.tenantContexts)
                .as("background work must be subject to the same tenant isolation as a request")
                .containsExactly(tenant.getId());
    }
}
