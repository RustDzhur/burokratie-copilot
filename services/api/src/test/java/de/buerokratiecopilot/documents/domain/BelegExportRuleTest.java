package de.buerokratiecopilot.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule the whole client routing exists for (§5.2, §11.7): a voucher whose client is unknown never reaches an
 * export.
 *
 * <p>This test states the rule where it can be read without a database. The service enforces it; the machine
 * describes what is otherwise allowed, so both are checked.</p>
 */
class BelegExportRuleTest {

    @Test
    @DisplayName("the machine allows the move, so the service has to be the guard")
    void theMachineAloneWouldAllowIt() {
        // If this ever stops being true the service guard becomes redundant - and until then it is the only
        // thing standing between an unrouted voucher and a DATEV batch.
        assertThat(BelegStatusMachine.canMove(BelegStatus.ZU_PRUEFEN, BelegStatus.EXPORTIERT)).isTrue();
    }

    @Test
    @DisplayName("exporting is the only destination that needs a client")
    void onlyExportNeedsAClient() {
        assertThat(BelegStatusMachine.canMove(BelegStatus.NEU, BelegStatus.OFFEN)).isTrue();
        assertThat(BelegStatusMachine.canMove(BelegStatus.OFFEN, BelegStatus.ZU_PRUEFEN)).isTrue();
        // Neither of those asks whose voucher it is: a person can accept and check paperwork before deciding
        // the client. Only leaving the office does.
    }

    @Test
    @DisplayName("an impossible move still fails before any client check can hide it")
    void impossibleMovesStillFail() {
        assertThatThrownBy(() -> BelegStatusMachine.requireMove(BelegStatus.NEU, BelegStatus.EXPORTIERT))
                .isInstanceOf(IllegalStateException.class);
    }
}
