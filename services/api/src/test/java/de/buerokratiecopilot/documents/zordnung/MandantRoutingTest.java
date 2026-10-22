package de.buerokratiecopilot.documents.zordnung;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule that keeps client A's paperwork out of client B's books (§5.2, §11.7).
 */
class MandantRoutingTest {

    private static final UUID CLIENT = UUID.randomUUID();

    @Test
    @DisplayName("above the threshold the platform may assign on its own")
    void confidentMatchIsAssigned() {
        assertThat(MandantRouting.autoAssign(CLIENT, 0.95)).contains(CLIENT);
        assertThat(MandantRouting.autoAssign(CLIENT, 0.9))
                .as("the threshold itself counts as confident")
                .contains(CLIENT);
    }

    @Test
    @DisplayName("below the threshold nobody is assigned, and no guess is made instead")
    void unsureMatchIsLeftToAPerson() {
        assertThat(MandantRouting.autoAssign(CLIENT, 0.89)).isEmpty();
        assertThat(MandantRouting.autoAssign(CLIENT, 0.5)).isEmpty();
        assertThat(MandantRouting.autoAssign(null, 1.0)).isEmpty();
        assertThat(MandantRouting.autoAssign(CLIENT, Double.NaN))
                .as("an unreadable confidence is not a confident one")
                .isEmpty();
    }

    @Test
    @DisplayName("two clients that match alike are not decided by alphabetical accident")
    void closeMatchesAreAmbiguous() {
        assertThat(MandantRouting.isAmbiguous(0.94, 0.90)).isTrue();
        assertThat(MandantRouting.isAmbiguous(0.99, 0.60)).isFalse();
    }

    @Test
    @DisplayName("a matcher that cannot rate the runner-up cannot claim a clear winner")
    void missingRunnerUpIsAmbiguous() {
        assertThat(MandantRouting.isAmbiguous(0.99, null)).isTrue();
    }
}
