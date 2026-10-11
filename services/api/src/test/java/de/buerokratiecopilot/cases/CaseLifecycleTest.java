package de.buerokratiecopilot.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.cases.domain.Case;
import de.buerokratiecopilot.cases.domain.CaseStatusMachine;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies the case lifecycle (spec §11.2).
 *
 * <p>The transitions are checked directly because they are the part of the model that protects the
 * data: a case that jumps from "new" to "completed" has no history of ever being worked on, and a case
 * that leaves "closed" without being reopened makes the closed date meaningless.</p>
 */
class CaseLifecycleTest {

    @Test
    @DisplayName("a new case is worked on or closed, but does not jump to completed work it never had")
    void newCaseCannotSkipWork() {
        assertThat(CaseStatusMachine.canTransition(Case.Status.NEW, Case.Status.IN_PROGRESS)).isTrue();
        assertThat(CaseStatusMachine.canTransition(Case.Status.NEW, Case.Status.WAITING_FOR_DOCUMENTS))
                .isTrue();
        assertThat(CaseStatusMachine.canTransition(Case.Status.NEW, Case.Status.CLOSED)).isTrue();
        assertThat(CaseStatusMachine.canTransition(Case.Status.NEW, Case.Status.REOPENED))
                .as("a case that was never closed cannot be reopened")
                .isFalse();
        assertThat(CaseStatusMachine.canTransition(Case.Status.NEW, Case.Status.WAITING_FOR_APPROVAL))
                .as("an approval can only be awaited for work that started")
                .isFalse();
    }

    @Test
    @DisplayName("waiting states lead back to work and sideways to one another")
    void waitingStatesAreInterchangeable() {
        assertThat(CaseStatusMachine.canTransition(Case.Status.WAITING_FOR_DOCUMENTS,
                Case.Status.IN_PROGRESS)).isTrue();
        assertThat(CaseStatusMachine.canTransition(Case.Status.WAITING_FOR_DOCUMENTS,
                Case.Status.WAITING_FOR_USER)).isTrue();
        assertThat(CaseStatusMachine.canTransition(Case.Status.WAITING_FOR_USER,
                Case.Status.CLOSED)).isTrue();
    }

    @Test
    @DisplayName("a closed case only leaves that state by being reopened")
    void closedCaseOnlyReopens() {
        assertThat(CaseStatusMachine.allowedFrom(Case.Status.CLOSED))
                .containsExactly(Case.Status.REOPENED);
        assertThat(CaseStatusMachine.canTransition(Case.Status.CLOSED, Case.Status.IN_PROGRESS))
                .as("continuing a closed case without reopening it would hide that it was closed")
                .isFalse();
    }

    @Test
    @DisplayName("completed and closed are both closing, waiting is waiting")
    void classifiesStates() {
        assertThat(CaseStatusMachine.isClosing(Case.Status.COMPLETED)).isTrue();
        assertThat(CaseStatusMachine.isClosing(Case.Status.CLOSED)).isTrue();
        assertThat(CaseStatusMachine.isClosing(Case.Status.IN_PROGRESS)).isFalse();

        assertThat(CaseStatusMachine.isWaiting(Case.Status.WAITING_FOR_APPROVAL)).isTrue();
        assertThat(CaseStatusMachine.isWaiting(Case.Status.IN_PROGRESS)).isFalse();
    }

    @Test
    @DisplayName("a refused transition names both states so the message is actionable")
    void refusalNamesBothStates() {
        assertThatThrownBy(() -> CaseStatusMachine.requireTransition(Case.Status.NEW,
                Case.Status.REOPENED))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("NEW")
                .hasMessageContaining("REOPENED");
    }

    @Test
    @DisplayName("a move to the state the case is already in is refused rather than recorded")
    void sameStateIsRefused() {
        assertThatThrownBy(() -> CaseStatusMachine.requireTransition(Case.Status.IN_PROGRESS,
                Case.Status.IN_PROGRESS))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already");
    }

    @Test
    @DisplayName("close reasons come from the fixed list the schema allows")
    void parsesCloseReasons() {
        assertThat(CaseLifecycleService.parseCloseReason("outcome_achieved"))
                .isEqualTo(CaseLifecycleService.CloseReason.OUTCOME_ACHIEVED);
        assertThat(CaseLifecycleService.parseCloseReason(" DUPLICATE ")).
                isEqualTo(CaseLifecycleService.CloseReason.DUPLICATE);
        assertThat(CaseLifecycleService.parseCloseReason(null)).isNull();

        assertThatThrownBy(() -> CaseLifecycleService.parseCloseReason("because I said so"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown close reason");
    }

    @Test
    @DisplayName("every state has a way forward, so no case can become unmanageable")
    void everyStateCanMoveOn() {
        for (Case.Status status : Case.Status.values()) {
            assertThat(CaseStatusMachine.allowedFrom(status))
                    .as("state %s has no way out", status)
                    .isNotEmpty();
        }
    }
}
