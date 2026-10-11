package de.buerokratiecopilot.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.contracts.domain.ContractClause;

/**
 * Verifies the boundary between detected text and legal interpretation (spec §13.1 acceptance).
 *
 * <p>The acceptance criterion is that the platform does not formulate legal conclusions without rules.
 * The cases below are that boundary from both sides: what a clause holds when it is only read, what is
 * refused when somebody tries to record a conclusion without a basis, and how the two states stay
 * distinguishable once an interpretation exists.</p>
 */
class ContractInterpretationBoundaryTest {

    private static ContractClause clause() {
        return new ContractClause(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "NOTICE_PERIOD", ContractClause.ClauseType.NOTICE_PERIOD,
                "Der Vertrag kann mit einer Frist von drei Monaten zum Monatsende gekündigt werden.");
    }

    @Test
    @DisplayName("a clause that was only read has no interpretation at all")
    void detectedTextIsNotAnInterpretation() {
        ContractClause clause = clause();

        assertThat(clause.hasInterpretation()).isFalse();
        assertThat(clause.getInterpretation()).isNull();
        assertThat(clause.isInterpretationVerified()).isFalse();
        assertThat(ContractService.interpretationState(clause)).isEqualTo("NOT_INTERPRETED");
    }

    @Test
    @DisplayName("a conclusion without a basis is refused, because it would be the platform's own opinion")
    void interpretationWithoutSourceIsRefused() {
        ContractClause clause = clause();

        assertThatThrownBy(() -> clause.interpret("Die Kündigungsfrist beträgt drei Monate.", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basis");
        assertThatThrownBy(() -> clause.interpret("Die Kündigungsfrist beträgt drei Monate.",
                ContractClause.InterpretationSource.NONE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("basis");

        assertThat(clause.hasInterpretation())
                .as("a refused interpretation must not be recorded half-way")
                .isFalse();
    }

    @Test
    @DisplayName("a conclusion without a statement is refused as well")
    void interpretationWithoutStatementIsRefused() {
        assertThatThrownBy(() -> clause().interpret("   ",
                ContractClause.InterpretationSource.RULE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statement");
    }

    @Test
    @DisplayName("a rule-based reading may stand unconfirmed; a person's reading is theirs and confirmed")
    void theSchemaDecidesWhichReadingsMayStandUnverified() {
        // A rule produced the conclusion, so it may await a person's confirmation. That is the state
        // the review list exists for.
        ContractClause fromRule = clause();
        fromRule.interpret("Die Frist beträgt drei Monate zum Monatsende.",
                ContractClause.InterpretationSource.RULE, null);

        assertThat(fromRule.hasInterpretation()).isTrue();
        assertThat(fromRule.getInterpretationSource()).isEqualTo("RULE");
        assertThat(fromRule.isInterpretationVerified()).isFalse();
        assertThat(ContractService.interpretationState(fromRule)).isEqualTo("UNVERIFIED_INTERPRETATION");

        fromRule.verifyInterpretation(UUID.randomUUID());
        assertThat(ContractService.interpretationState(fromRule)).isEqualTo("VERIFIED_INTERPRETATION");

        // A conclusion a person drew is asserted by that person, so it is verified in the same act:
        // the database refuses a human reading without a verification, and a platform that recorded
        // one would be putting words in somebody's mouth.
        ContractClause fromHuman = clause();
        UUID author = UUID.randomUUID();
        fromHuman.interpret("Die Frist beträgt drei Monate zum Monatsende.",
                ContractClause.InterpretationSource.HUMAN, author);

        assertThat(fromHuman.getInterpretationSource()).isEqualTo("HUMAN");
        assertThat(fromHuman.isInterpretationVerified()).isTrue();
        assertThat(fromHuman.getInterpretationVerifiedBy()).isEqualTo(author);
    }

    @Test
    @DisplayName("a conclusion drawn by a person needs that person")
    void humanInterpretationNeedsAnAuthor() {
        assertThatThrownBy(() -> clause().interpret("Drei Monate zum Monatsende.",
                ContractClause.InterpretationSource.HUMAN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("person");
    }

    @Test
    @DisplayName("changing an interpretation invalidates the previous verification")
    void newInterpretationLosesTheVerification() {
        ContractClause clause = clause();
        clause.interpret("Drei Monate zum Monatsende.",
                ContractClause.InterpretationSource.RULE, null);
        clause.verifyInterpretation(UUID.randomUUID());

        clause.interpret("Drei Monate zum Quartalsende.",
                ContractClause.InterpretationSource.RULE, null);

        assertThat(clause.isInterpretationVerified())
                .as("the confirmation belonged to the earlier reading, not to this one")
                .isFalse();
        assertThat(ContractService.interpretationState(clause)).isEqualTo("UNVERIFIED_INTERPRETATION");
    }

    @Test
    @DisplayName("there is nothing to verify before an interpretation exists")
    void verificationNeedsAnInterpretation() {
        assertThatThrownBy(() -> clause().verifyInterpretation(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no interpretation");
    }

    @Test
    @DisplayName("the clause text is kept as the document writes it, never as a summary")
    void clauseTextIsVerbatim() {
        String verbatim = "Der Vertrag kann mit einer Frist von drei Monaten zum Monatsende gekündigt "
                + "werden.";
        ContractClause clause = new ContractClause(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "NOTICE_PERIOD", ContractClause.ClauseType.NOTICE_PERIOD, verbatim);

        assertThat(clause.getClauseText()).isEqualTo(verbatim);
    }
}
