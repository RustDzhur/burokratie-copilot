package de.buerokratiecopilot.agents.processes;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The input rules of {@code approval}: actions, amounts, directions and decisions are normalised
 * strictly — an invalid value is reported, never guessed into a wrong authorisation.
 */
class ApprovalInputParserTest {

    @Test
    @DisplayName("the agent id matches the manifest id")
    void idMatchesManifest() {
        assertThat(new ApprovalAgent(null, null).id()).isEqualTo("approval");
    }

    @Test
    @DisplayName("action defaults to request, unknown actions are refused")
    void parsesAction() {
        assertThat(ApprovalInputParser.parseAction(null)).isEqualTo("request");
        assertThat(ApprovalInputParser.parseAction("  ")).isEqualTo("request");
        assertThat(ApprovalInputParser.parseAction("Decide")).isEqualTo("decide");
        assertThat(ApprovalInputParser.parseAction("STATUS")).isEqualTo("status");
        assertThat(ApprovalInputParser.parseAction("delete")).isNull();
    }

    @Test
    @DisplayName("amounts are decimal numbers, invalid text is a hard error")
    void parsesAmount() {
        assertThat(ApprovalInputParser.parseAmount(null)).isNull();
        assertThat(ApprovalInputParser.parseAmount(" ")).isNull();
        assertThat(ApprovalInputParser.parseAmount("1234,56")).isEqualTo(new BigDecimal("1234.56"));
        assertThat(ApprovalInputParser.parseAmount("12.5")).isEqualTo(new BigDecimal("12.5"));
        assertThat(ApprovalInputParser.parseAmount("viel")).isNull();
    }

    @Test
    @DisplayName("direction defaults to ANY, unknown directions are refused")
    void parsesDirection() {
        assertThat(ApprovalInputParser.parseDirection(null)).isEqualTo("ANY");
        assertThat(ApprovalInputParser.parseDirection("outgoing")).isEqualTo("OUTGOING");
        assertThat(ApprovalInputParser.parseDirection("ANY")).isEqualTo("ANY");
        assertThat(ApprovalInputParser.parseDirection("sideways")).isNull();
    }

    @Test
    @DisplayName("approved is true or false, never guessed")
    void parsesApproved() {
        assertThat(ApprovalInputParser.parseApproved(null)).isNull();
        assertThat(ApprovalInputParser.parseApproved("true")).isTrue();
        assertThat(ApprovalInputParser.parseApproved("FALSE")).isFalse();
        assertThat(ApprovalInputParser.parseApproved("maybe")).isNull();
    }

    @Test
    @DisplayName("expiry is ISO-8601 or absent, invalid text is refused")
    void parsesInstant() {
        assertThat(ApprovalInputParser.parseInstant(null)).isNull();
        assertThat(ApprovalInputParser.parseInstant("2026-11-01T09:00:00Z"))
                .isEqualTo(Instant.parse("2026-11-01T09:00:00Z"));
        assertThat(ApprovalInputParser.parseInstant("morgen")).isNull();
    }

    @Test
    @DisplayName("approval types are constrained to the schema values, unknown becomes OTHER")
    void normalizesApprovalType() {
        assertThat(ApprovalInputParser.normalizeApprovalType(null)).isEqualTo("OTHER");
        assertThat(ApprovalInputParser.normalizeApprovalType("contract_termination"))
                .isEqualTo("CONTRACT_TERMINATION");
        assertThat(ApprovalInputParser.normalizeApprovalType("WORKFLOW_PUBLISH"))
                .isEqualTo("WORKFLOW_PUBLISH");
        assertThat(ApprovalInputParser.normalizeApprovalType("anything_else")).isEqualTo("OTHER");
    }
}
