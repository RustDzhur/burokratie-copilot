package de.buerokratiecopilot.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The voucher moves through the office one step at a time (§8.1).
 *
 * <p>These tests exist because the transitions are the promise: a batch that accepts a voucher nobody opened is
 * a batch the office cannot stand behind, and a screen that lets a failed voucher look exported is a lie.</p>
 */
class BelegStatusMachineTest {

    @Test
    @DisplayName("a new voucher is accepted, and nothing else")
    void newGoesToOpen() {
        assertThat(BelegStatusMachine.canMove(BelegStatus.NEU, BelegStatus.OFFEN)).isTrue();
        assertThat(BelegStatusMachine.canMove(BelegStatus.NEU, BelegStatus.EXPORTIERT))
                .as("a voucher nobody looked at does not belong in an export")
                .isFalse();
        assertThat(BelegStatusMachine.canMove(BelegStatus.NEU, BelegStatus.ZU_PRUEFEN))
                .as("checking comes after accepting, not before")
                .isFalse();
    }

    @Test
    @DisplayName("failure can strike any step, and a retry starts from the beginning")
    void failureAndRetry() {
        for (BelegStatus from : BelegStatus.values()) {
            if (from != BelegStatus.FEHLER) {
                assertThat(BelegStatusMachine.canMove(from, BelegStatus.FEHLER))
                        .as("failure from %s", from)
                        .isTrue();
            }
        }
        assertThat(BelegStatusMachine.canMove(BelegStatus.FEHLER, BelegStatus.NEU)).isTrue();
        assertThat(BelegStatusMachine.canMove(BelegStatus.FEHLER, BelegStatus.EXPORTIERT))
                .as("a retry must be worked through, not jumped over")
                .isFalse();
    }

    @Test
    @DisplayName("staying where it is never fails: a second click is not a mistake")
    void stayingIsAllowed() {
        for (BelegStatus status : BelegStatus.values()) {
            assertThat(BelegStatusMachine.canMove(status, status)).isTrue();
        }
    }

    @Test
    @DisplayName("a forbidden move says both ends, so the log line is usable")
    void forbiddenMoveIsNamed() {
        assertThatThrownBy(() -> BelegStatusMachine.requireMove(BelegStatus.NEU, BelegStatus.EXPORTIERT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEU")
                .hasMessageContaining("EXPORTIERT");
    }

    @Test
    @DisplayName("only the three middle states count as open work")
    void openStates() {
        assertThat(BelegStatusMachine.isOpen(BelegStatus.NEU)).isTrue();
        assertThat(BelegStatusMachine.isOpen(BelegStatus.OFFEN)).isTrue();
        assertThat(BelegStatusMachine.isOpen(BelegStatus.ZU_PRUEFEN)).isTrue();
        assertThat(BelegStatusMachine.isOpen(BelegStatus.EXPORTIERT)).isFalse();
        assertThat(BelegStatusMachine.isOpen(BelegStatus.FEHLER)).isFalse();
    }
}
