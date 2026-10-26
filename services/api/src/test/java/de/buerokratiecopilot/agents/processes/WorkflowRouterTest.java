package de.buerokratiecopilot.agents.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.processes.WorkflowRouter.CaseFacts;
import de.buerokratiecopilot.agents.processes.WorkflowRouter.Route;
import de.buerokratiecopilot.agents.processes.WorkflowRouter.RunFacts;

/**
 * The routing rules of {@code workflow-route}, as a pure function: every state of a case and its
 * runs yields exactly one next step, in a fixed priority order.
 */
class WorkflowRouterTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private static CaseFacts caseFacts(String status, String caseTypeCode, boolean pendingTask,
                                       boolean overdue, boolean unverified) {
        return new CaseFacts("case-1", status, caseTypeCode, pendingTask, overdue, unverified);
    }

    @Test
    @DisplayName("the agent id matches the manifest id")
    void idMatchesManifest() {
        assertThat(new WorkflowRouteAgent(null, null, null, null, null).id())
                .isEqualTo("workflow-route");
    }

    @Test
    @DisplayName("a run whose waiting time has passed is resumed")
    void resumesOverdueWait() {
        RunFacts run = RunFacts.of("run-1", "WAITING", "wait_reply", null,
                NOW.minus(1, ChronoUnit.HOURS));
        Route route = WorkflowRouter.route(caseFacts("IN_PROGRESS", null, false, false, false),
                List.of(run), NOW);

        assertThat(route.action()).isEqualTo(WorkflowRouter.ACTION_RESUME_WAIT);
        assertThat(route.targetRunId()).isEqualTo("run-1");
        assertThat(route.isExecutable()).isTrue();
    }

    @Test
    @DisplayName("a running run is advanced")
    void advancesRunningRun() {
        RunFacts run = RunFacts.of("run-2", "RUNNING", "check", null, null);
        Route route = WorkflowRouter.route(caseFacts("IN_PROGRESS", null, false, false, false),
                List.of(run), NOW);

        assertThat(route.action()).isEqualTo(WorkflowRouter.ACTION_ADVANCE_RUN);
        assertThat(route.targetRunId()).isEqualTo("run-2");
    }

    @Test
    @DisplayName("a run waiting for a person is handed to the approver, not advanced")
    void routesApprovalToApprover() {
        RunFacts run = RunFacts.of("run-3", "WAITING_APPROVAL", "approve", null, null);
        Route route = WorkflowRouter.route(caseFacts("IN_PROGRESS", null, false, false, false),
                List.of(run), NOW);

        assertThat(route.action()).isEqualTo(WorkflowRouter.ACTION_AWAIT_APPROVAL);
        assertThat(route.handover()).isEqualTo("approver");
        assertThat(route.isExecutable()).isFalse();
    }

    @Test
    @DisplayName("a run waiting for an event names the event it waits for")
    void namesExpectedEvent() {
        RunFacts run = RunFacts.of("run-4", "WAITING", "wait_reply", "response.drafted", null);
        Route route = WorkflowRouter.route(caseFacts("IN_PROGRESS", null, false, false, false),
                List.of(run), NOW);

        assertThat(route.action()).isEqualTo(WorkflowRouter.ACTION_WAIT_EVENT);
        assertThat(route.nextStep()).isEqualTo("response.drafted");
    }

    @Test
    @DisplayName("a run waiting on time, not yet due, stays waiting")
    void waitsUntilTimePasses() {
        RunFacts run = RunFacts.of("run-5", "WAITING", "wait_reply", null,
                NOW.plus(1, ChronoUnit.DAYS));
        Route route = WorkflowRouter.route(caseFacts("IN_PROGRESS", null, false, false, false),
                List.of(run), NOW);

        assertThat(route.action()).isEqualTo(WorkflowRouter.ACTION_WAIT_TIME);
        assertThat(route.targetRunId()).isEqualTo("run-5");
        assertThat(route.isExecutable()).isFalse();
    }

    @Test
    @DisplayName("a closed case without runs has no next step")
    void closedCaseGetsNothing() {
        Route route = WorkflowRouter.route(caseFacts("CLOSED", null, false, false, false),
                List.of(), NOW);

        assertThat(route.action()).isEqualTo(WorkflowRouter.ACTION_CLOSED);
        assertThat(route.isExecutable()).isFalse();
    }

    @Test
    @DisplayName("a fresh invoice case starts the incoming invoice preset")
    void startsPresetForCaseType() {
        Route route = WorkflowRouter.route(caseFacts("NEW", "rechnungsklaerung", false, false, false),
                List.of(), NOW);

        assertThat(route.action()).isEqualTo(WorkflowRouter.ACTION_START_PROCESS);
        assertThat(route.nextStep()).isEqualTo("incoming_invoice");
        assertThat(route.isExecutable()).isTrue();
    }

    @Test
    @DisplayName("an unassigned task routes to the assignment rules")
    void assignsPendingTask() {
        Route route = WorkflowRouter.route(caseFacts("IN_PROGRESS", null, true, false, false),
                List.of(), NOW);

        assertThat(route.action()).isEqualTo(WorkflowRouter.ACTION_ASSIGN_PENDING_TASK);
        assertThat(route.handover()).isEqualTo("assignment-rules");
    }

    @Test
    @DisplayName("an overdue deadline routes to escalation, before anything else")
    void escalatesOverdueDeadline() {
        Route route = WorkflowRouter.route(caseFacts("IN_PROGRESS", null, true, true, false),
                List.of(), NOW);

        assertThat(route.action()).isEqualTo(WorkflowRouter.ACTION_ESCALATE_OVERDUE_DEADLINE);
        assertThat(route.handover()).isEqualTo("owner");
    }

    @Test
    @DisplayName("an unverified deadline routes to verification")
    void verifiesUnverifiedDeadline() {
        Route route = WorkflowRouter.route(caseFacts("IN_PROGRESS", null, false, false, true),
                List.of(), NOW);

        assertThat(route.action()).isEqualTo(WorkflowRouter.ACTION_VERIFY_DEADLINE);
    }

    @Test
    @DisplayName("a waiting case names who holds the ball")
    void handoverFollowsCaseStatus() {
        assertThat(WorkflowRouter.handoverFor("WAITING_FOR_USER")).isEqualTo("owner");
        assertThat(WorkflowRouter.handoverFor("WAITING_FOR_EXTERNAL_PARTY"))
                .isEqualTo("external-party");
        assertThat(WorkflowRouter.handoverFor("WAITING_FOR_DOCUMENTS")).isEqualTo("requester");
        assertThat(WorkflowRouter.handoverFor("IN_PROGRESS")).isNull();
    }

    @Test
    @DisplayName("preset mapping is keyword-based and deterministic")
    void presetMappingByKeywords() {
        assertThat(WorkflowRouter.presetForCaseType("rechnungsklaerung"))
                .isEqualTo("incoming_invoice");
        assertThat(WorkflowRouter.presetForCaseType("mietvertrag")).isEqualTo("contract_review");
        assertThat(WorkflowRouter.presetForCaseType("behoerdenpost")).isEqualTo("authority_letter");
        assertThat(WorkflowRouter.presetForCaseType(null)).isNull();
        assertThat(WorkflowRouter.presetForCaseType("sonstiges")).isNull();
    }
}
