package de.buerokratiecopilot.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies the process model and the engine (spec §16, F-001 and F-002).
 *
 * <p>Two acceptance criteria carry the weight. A new version must not break a run that is already going:
 * the run keeps the version it started on, which is why a published version can never be edited. And a
 * restart must neither lose nor repeat a step, which is checked here by calling {@code advance} twice
 * without pretending anything crashed — the second call is exactly what a restart produces.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkflowEngineIT {

    @Autowired
    private WorkflowService workflows;

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Prozess Test', 'COMPANY') returning id
                """, UUID.class, "wf-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID person(UUID tenantId) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "wf-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                """, tenantId, userId);
        return userId;
    }

    /** A three-step process: reach a step, branch, then end. */
    private Map<String, Object> branchingDefinition() {
        return Map.of(
                "entryStep", "start",
                "steps", List.of(
                        Map.of("key", "start", "type", "ACTION", "config", Map.of("next", "check")),
                        Map.of("key", "check", "type", "CONDITION",
                                "config", Map.of("field", "urgent", "equals", "true", "onTrue", "fast",
                                        "onFalse", "slow")),
                        Map.of("key", "fast", "type", "TERMINATE",
                                "config", Map.of("outcome", "COMPLETED")),
                        Map.of("key", "slow", "type", "NOTIFICATION", "config", Map.of("next", "end")),
                        Map.of("key", "end", "type", "TERMINATE", "config", Map.of()))); 
    }

    private UUID publish(UUID tenantId, String code, Map<String, Object> definition) {
        UUID definitionId = RequestContext.runAs(tenantId, () -> workflows.define(tenantId, code, code,
                "Ein Prozess", "MANUAL", Map.of(), person(tenantId))).definitionId();
        RequestContext.runAs(tenantId, () -> workflows.publish(tenantId, definitionId, definition, Map.of(),
                null, person(tenantId)));
        return definitionId;
    }

    private WorkflowEngine.Run start(UUID tenantId, UUID definitionId, Map<String, Object> payload,
                                     String key) {
        return RequestContext.runAs(tenantId, () -> engine.start(tenantId, definitionId, "MANUAL",
                payload, null, null, null, key, null));
    }

    private WorkflowEngine.StepResult advance(UUID tenantId, UUID runId) {
        return RequestContext.runAs(tenantId, () -> engine.advance(tenantId, runId));
    }

    private String statusOf(UUID tenantId, UUID runId) {
        return jdbc.queryForObject("select status from workflow_runs where id = ? and tenant_id = ?",
                String.class, runId, tenantId);
    }

    // ---------------------------------------------------------------- F-001: the model

    @Test
    @DisplayName("a valid definition is published and becomes the current version")
    void validDefinitionIsPublished() {
        UUID tenantId = tenant();
        UUID definitionId = publish(tenantId, "rechnung", branchingDefinition());

        assertThat(RequestContext.runAs(tenantId, () -> workflows.currentVersion(tenantId, definitionId))
                .version()).isEqualTo(1);
    }

    @Test
    @DisplayName("a definition with a step that leads nowhere is refused at publishing")
    void definitionWithDanglingStepIsRefused() {
        UUID tenantId = tenant();
        UUID definitionId = RequestContext.runAs(tenantId, () -> workflows.define(tenantId, "kaputt",
                "Kaputt", null, "MANUAL", Map.of(), person(tenantId))).definitionId();
        Map<String, Object> broken = Map.of(
                "entryStep", "start",
                "steps", List.of(Map.of("key", "start", "type", "ACTION",
                        "config", Map.of("next", "gibt-es-nicht"))));

        // Finding this while a customer's invoice is halfway through is far too late.
        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> workflows.publish(tenantId,
                definitionId, broken, Map.of(), null, person(tenantId))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("gibt-es-nicht");
    }

    @Test
    @DisplayName("every problem of a definition is reported at once")
    void allProblemsAreReported() {
        WorkflowDefinition.Definition definition = WorkflowDefinition.parse(Map.of(
                "entryStep", "start",
                "steps", List.of(
                        Map.of("key", "start", "type", "TELEPORT", "config", Map.of()),
                        Map.of("key", "start", "type", "ACTION", "config", Map.of()))));

        var validation = WorkflowDefinition.validate(definition);

        assertThat(validation.isValid()).isFalse();
        assertThat(validation.problems()).extracting(WorkflowDefinition.Problem::code)
                .contains("DUPLICATE_KEY", "UNKNOWN_TYPE");
        assertThat(validation.summary()).contains("Problem");
    }

    @Test
    @DisplayName("a condition without both branches is refused")
    void conditionNeedsBothBranches() {
        var validation = WorkflowDefinition.validate(WorkflowDefinition.parse(Map.of(
                "entryStep", "c",
                "steps", List.of(Map.of("key", "c", "type", "CONDITION",
                        "config", Map.of("onTrue", "e")),
                        Map.of("key", "e", "type", "TERMINATE", "config", Map.of())))));

        assertThat(validation.problems()).extracting(WorkflowDefinition.Problem::code)
                .contains("CONDITION_WITHOUT_BRANCHES");
    }

    @Test
    @DisplayName("a definition without an entry step is refused")
    void definitionNeedsAnEntry() {
        var validation = WorkflowDefinition.validate(WorkflowDefinition.parse(Map.of(
                "steps", List.of(Map.of("key", "a", "type", "TERMINATE", "config", Map.of())))));

        assertThat(validation.problems()).extracting(WorkflowDefinition.Problem::code)
                .contains("NO_ENTRY");
    }

    @Test
    @DisplayName("a new version does not touch a run that is already going")
    void aNewVersionDoesNotBreakARunningInstance() {
        UUID tenantId = tenant();
        UUID definitionId = publish(tenantId, "urlaub", branchingDefinition());
        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of("urgent", "true"), "key-1");
        advance(tenantId, run.runId());

        // A second version is published while the first run is in flight.
        RequestContext.runAs(tenantId, () -> workflows.publish(tenantId, definitionId,
                Map.of("entryStep", "neu",
                        "steps", List.of(Map.of("key", "neu", "type", "TERMINATE", "config", Map.of()))),
                Map.of(), "Neue Fassung", person(tenantId)));

        assertThat(RequestContext.runAs(tenantId, () -> workflows.currentVersion(tenantId, definitionId))
                .version()).isEqualTo(2);
        // The run stays on version one, which is the reason a published version can never be edited.
        assertThat(jdbc.queryForObject("""
                select v.version from workflow_runs r join workflow_versions v on v.id = r.version_id
                where r.id = ?
                """, Integer.class, run.runId())).isEqualTo(1);

        advance(tenantId, run.runId());
        advance(tenantId, run.runId());
        assertThat(statusOf(tenantId, run.runId())).as("start -> check -> fast erfordert drei Schritte")
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a draft version is not used by a run")
    void aDraftIsNotUsed() {
        UUID tenantId = tenant();
        UUID definitionId = RequestContext.runAs(tenantId, () -> workflows.define(tenantId, "entwurf",
                "Entwurf", null, "MANUAL", Map.of(), person(tenantId))).definitionId();
        RequestContext.runAs(tenantId, () -> workflows.saveDraft(tenantId, definitionId,
                branchingDefinition(), person(tenantId)));

        assertThatThrownBy(() -> start(tenantId, definitionId, Map.of(), "key-draft"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("veröffentlicht");
    }

    @Test
    @DisplayName("an inactive process is not started")
    void anInactiveProcessIsNotStarted() {
        UUID tenantId = tenant();
        UUID definitionId = publish(tenantId, "inaktiv", branchingDefinition());
        RequestContext.runAs(tenantId, () -> {
            workflows.deactivate(tenantId, definitionId);
            return null;
        });

        assertThatThrownBy(() -> start(tenantId, definitionId, Map.of(), "key-inactive"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nicht aktiv");
    }

    // ---------------------------------------------------------------- F-002: the engine

    @Test
    @DisplayName("a run goes through its steps and ends")
    void aRunGoesThroughItsSteps() {
        UUID tenantId = tenant();
        UUID definitionId = publish(tenantId, "durchlauf", branchingDefinition());
        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of("urgent", "false"), "key-2");

        assertThat(run.currentStep()).isEqualTo("start");
        advance(tenantId, run.runId());
        assertThat(jdbc.queryForObject("select current_step_key from workflow_runs where id = ?",
                String.class, run.runId())).isEqualTo("check");

        advance(tenantId, run.runId());
        assertThat(jdbc.queryForObject("select current_step_key from workflow_runs where id = ?",
                String.class, run.runId())).as("the condition chose the slow branch").isEqualTo("slow");

        advance(tenantId, run.runId());
        advance(tenantId, run.runId());
        assertThat(statusOf(tenantId, run.runId())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a condition chooses the branch its payload names")
    void aConditionChoosesItsBranch() {
        UUID tenantId = tenant();
        UUID definitionId = publish(tenantId, "bedingung", branchingDefinition());

        WorkflowEngine.Run fast = start(tenantId, definitionId, Map.of("urgent", "true"), "fast-key");
        // start -> check -> fast, and only then is the process at its end.
        advance(tenantId, fast.runId());
        advance(tenantId, fast.runId());
        advance(tenantId, fast.runId());

        assertThat(statusOf(tenantId, fast.runId())).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from workflow_run_steps where run_id = ? "
                + "and step_key = 'slow'", Integer.class, fast.runId()))
                .as("the branch that was not taken must not have been executed")
                .isZero();
    }

    @Test
    @DisplayName("a step is not executed twice when advance is called again")
    void aStepIsNotExecutedTwice() {
        UUID tenantId = tenant();
        UUID definitionId = publish(tenantId, "wiederholung", branchingDefinition());
        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of("urgent", "true"), "key-3");

        WorkflowEngine.StepResult first = advance(tenantId, run.runId());
        WorkflowEngine.StepResult second = advance(tenantId, run.runId());

        assertThat(first.status()).isEqualTo("SUCCEEDED");
        assertThat(second.status())
                .as("the second call is exactly what a restart produces, and it must do nothing")
                .isEqualTo("SUCCEEDED");
        // The 'start' step ran once: the condition is now the current step, and the record exists once.
        assertThat(jdbc.queryForObject("""
                select count(*) from workflow_run_steps where run_id = ? and step_key = 'start'
                """, Integer.class, run.runId())).isEqualTo(1);
    }

    @Test
    @DisplayName("starting with the same key twice produces one run")
    void startingTwiceProducesOneRun() {
        UUID tenantId = tenant();
        UUID definitionId = publish(tenantId, "idempotent", branchingDefinition());

        WorkflowEngine.Run first = start(tenantId, definitionId, Map.of(), "same-key");
        WorkflowEngine.Run second = start(tenantId, definitionId, Map.of(), "same-key");

        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.created()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from workflow_runs where tenant_id = ?",
                Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a wait step records when it continues, and resuming continues the run")
    void aWaitStepIsPersistent() {
        UUID tenantId = tenant();
        UUID definitionId = RequestContext.runAs(tenantId, () -> workflows.define(tenantId, "warten",
                "Warten", null, "MANUAL", Map.of(), person(tenantId))).definitionId();
        RequestContext.runAs(tenantId, () -> workflows.publish(tenantId, definitionId, Map.of(
                "entryStep", "delay",
                "steps", List.of(
                        Map.of("key", "delay", "type", "DELAY",
                                "config", Map.of("seconds", 0, "next", "end")),
                        Map.of("key", "end", "type", "TERMINATE", "config", Map.of()))),
                Map.of(), null, person(tenantId)));

        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of(), "wait-key");
        advance(tenantId, run.runId());

        assertThat(statusOf(tenantId, run.runId())).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("select wait_until from workflow_runs where id = ?",
                java.sql.Timestamp.class, run.runId()))
                .as("a run waiting until Friday is a row with a date, not a timer a restart would drop")
                .isNotNull();

        List<UUID> resumed = RequestContext.runAs(tenantId, () -> engine.resumeDue(tenantId, 10));

        assertThat(resumed).contains(run.runId());
        assertThat(statusOf(tenantId, run.runId())).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("an event wakes the run that waits for it, and a second delivery does nothing")
    void eventDeliveryIsIdempotent() {
        UUID tenantId = tenant();
        UUID definitionId = RequestContext.runAs(tenantId, () -> workflows.define(tenantId, "ereignis",
                "Ereignis", null, "MANUAL", Map.of(), person(tenantId))).definitionId();
        RequestContext.runAs(tenantId, () -> workflows.publish(tenantId, definitionId, Map.of(
                "entryStep", "wait",
                "steps", List.of(
                        Map.of("key", "wait", "type", "WAIT",
                                "config", Map.of("waitFor", "payment.received", "next", "end")),
                        Map.of("key", "end", "type", "TERMINATE", "config", Map.of()))),
                Map.of(), null, person(tenantId)));
        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of(), "event-key");
        advance(tenantId, run.runId());

        assertThat(RequestContext.runAs(tenantId,
                () -> engine.deliverEvent(tenantId, run.runId(), "etwas.anderes", Map.of())))
                .as("an event nobody waits for changes nothing")
                .isFalse();
        assertThat(RequestContext.runAs(tenantId,
                () -> engine.deliverEvent(tenantId, run.runId(), "payment.received", Map.of())))
                .isTrue();
        assertThat(RequestContext.runAs(tenantId,
                () -> engine.deliverEvent(tenantId, run.runId(), "payment.received", Map.of())))
                .as("queues repeat, webhooks retry, and an operator clicks twice")
                .isFalse();
    }

    @Test
    @DisplayName("an approval step waits for a person and continues when approved")
    void anApprovalStepWaitsForAPerson() {
        UUID tenantId = tenant();
        UUID definitionId = RequestContext.runAs(tenantId, () -> workflows.define(tenantId, "freigabe",
                "Freigabe", null, "MANUAL", Map.of(), person(tenantId))).definitionId();
        RequestContext.runAs(tenantId, () -> workflows.publish(tenantId, definitionId, Map.of(
                "entryStep", "approve",
                "steps", List.of(
                        Map.of("key", "approve", "type", "APPROVAL", "config", Map.of("next", "end")),
                        Map.of("key", "end", "type", "TERMINATE", "config", Map.of()))),
                Map.of(), null, person(tenantId)));
        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of(), "approval-key");
        advance(tenantId, run.runId());
        assertThat(statusOf(tenantId, run.runId())).isEqualTo("WAITING_APPROVAL");

        RequestContext.runAs(tenantId, () -> engine.approve(tenantId, run.runId(), person(tenantId), true,
                "Geprüft"));

        assertThat(statusOf(tenantId, run.runId())).isEqualTo("RUNNING");
        advance(tenantId, run.runId());
        assertThat(statusOf(tenantId, run.runId())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a rejection ends the run as failed, not as approved")
    void aRejectionFailsTheRun() {
        UUID tenantId = tenant();
        UUID definitionId = RequestContext.runAs(tenantId, () -> workflows.define(tenantId, "ablehnung",
                "Ablehnung", null, "MANUAL", Map.of(), person(tenantId))).definitionId();
        RequestContext.runAs(tenantId, () -> workflows.publish(tenantId, definitionId, Map.of(
                "entryStep", "approve",
                "steps", List.of(
                        Map.of("key", "approve", "type", "APPROVAL", "config", Map.of("next", "end")),
                        Map.of("key", "end", "type", "TERMINATE", "config", Map.of()))),
                Map.of(), null, person(tenantId)));
        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of(), "reject-key");
        advance(tenantId, run.runId());

        RequestContext.runAs(tenantId, () -> engine.approve(tenantId, run.runId(), person(tenantId), false,
                "Betrag stimmt nicht"));

        assertThat(statusOf(tenantId, run.runId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select failure_message from workflow_runs where id = ?",
                String.class, run.runId())).contains("Betrag");
    }

    @Test
    @DisplayName("an approval needs a person")
    void anApprovalNeedsAPerson() {
        UUID tenantId = tenant();
        UUID definitionId = RequestContext.runAs(tenantId, () -> workflows.define(tenantId, "person",
                "Person", null, "MANUAL", Map.of(), person(tenantId))).definitionId();
        RequestContext.runAs(tenantId, () -> workflows.publish(tenantId, definitionId, Map.of(
                "entryStep", "approve",
                "steps", List.of(
                        Map.of("key", "approve", "type", "APPROVAL", "config", Map.of("next", "end")),
                        Map.of("key", "end", "type", "TERMINATE", "config", Map.of()))),
                Map.of(), null, person(tenantId)));
        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of(), "person-key");
        advance(tenantId, run.runId());

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> engine.approve(tenantId, run.runId(), null, true, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Person");
    }

    @Test
    @DisplayName("a run can be cancelled and then does nothing more")
    void aRunCanBeCancelled() {
        UUID tenantId = tenant();
        UUID definitionId = publish(tenantId, "abbruch", branchingDefinition());
        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of(), "cancel-key");

        RequestContext.runAs(tenantId, () -> {
            engine.cancel(tenantId, run.runId(), "Vorgang zurückgezogen");
            return null;
        });

        assertThat(statusOf(tenantId, run.runId())).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> advance(tenantId, run.runId())).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("everything that happened is in the journal")
    void everythingIsInTheJournal() {
        UUID tenantId = tenant();
        UUID definitionId = publish(tenantId, "journal", branchingDefinition());
        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of("urgent", "true"), "journal-key");
        advance(tenantId, run.runId());
        advance(tenantId, run.runId());

        Map<String, Object> described = RequestContext.runAs(tenantId,
                () -> engine.describe(tenantId, run.runId()));

        assertThat(described).containsKeys("status", "steps", "events");
        assertThat((List<?>) described.get("events")).isNotEmpty();
        assertThat((List<?>) described.get("steps")).isNotEmpty();
    }

    @Test
    @DisplayName("a run of another tenant is not reachable")
    void anotherTenantsRunIsNotReachable() {
        UUID owner = tenant();
        UUID stranger = tenant();
        UUID definitionId = publish(owner, "fremd", branchingDefinition());
        WorkflowEngine.Run run = start(owner, definitionId, Map.of(), "fremd-key");

        assertThatThrownBy(() -> RequestContext.runAs(stranger, () -> engine.describe(stranger,
                run.runId())))
                .isInstanceOf(ApiException.class);
        assertThat(RequestContext.runAs(stranger,
                () -> engine.runsInStatus(stranger, "RUNNING", 10))).isEmpty();
    }

    @Test
    @DisplayName("a trivial definition validates and runs")
    void aTrivialDefinitionRuns() {
        UUID tenantId = tenant();
        UUID definitionId = publish(tenantId, "trivial", Map.of(
                "entryStep", "end",
                "steps", List.of(Map.of("key", "end", "type", "TERMINATE", "config", Map.of()))));

        WorkflowEngine.Run run = start(tenantId, definitionId, Map.of(), "trivial-key");
        advance(tenantId, run.runId());

        assertThat(statusOf(tenantId, run.runId())).isEqualTo("COMPLETED");
    }
}
