package de.buerokratiecopilot.agents.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.buerokratiecopilot.agents.it.AccessReviewAgent.Finding;
import de.buerokratiecopilot.agents.it.AccessReviewAgent.ReviewRow;

/** Verifies the rule set of {@code it-access-review} as a pure function. */
class AccessReviewAgentTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private static ReviewRow row(Instant lastLogin, String userStatus, String membershipStatus,
                                 Instant validUntil, String roles, boolean mfa) {
        return new ReviewRow(USER, "user@example.de", "Nutzer", lastLogin, userStatus,
                membershipStatus, validUntil, roles, mfa);
    }

    @Test
    void aFreshHealthyAccountHasNoFindings() {
        List<Finding> findings = AccessReviewAgent.evaluate(
                row(NOW.minus(3, ChronoUnit.DAYS), "ACTIVE", "ACTIVE", null, "EMPLOYEE", true),
                NOW, 90, true);

        assertThat(findings).isEmpty();
    }

    @Test
    void staleLoginAndMissingMfaAreTwoDifferentFindings() {
        List<Finding> findings = AccessReviewAgent.evaluate(
                row(NOW.minus(120, ChronoUnit.DAYS), "ACTIVE", "ACTIVE", null, "EMPLOYEE", false),
                NOW, 90, true);

        assertThat(findings).extracting(Finding::code)
                .containsExactly("STALE_LOGIN", "MISSING_MFA");
    }

    @Test
    void aStalePrivilegedAccountIsHighSeverity() {
        List<Finding> findings = AccessReviewAgent.evaluate(
                row(NOW.minus(200, ChronoUnit.DAYS), "ACTIVE", "ACTIVE", null, "OWNER,ADMIN", true),
                NOW, 90, false);

        assertThat(findings).extracting(Finding::code).containsExactly("STALE_LOGIN");
        assertThat(findings.get(0).severity()).isEqualTo("HIGH");
        assertThat(findings.get(0).message()).contains("privilegierte");
    }

    @Test
    void neverLoggedInExpiredAccessAndMissingRoleAreReported() {
        List<Finding> findings = AccessReviewAgent.evaluate(
                row(null, "ACTIVE", "ACTIVE", NOW.minus(2, ChronoUnit.DAYS), "", false),
                NOW, 90, false);

        assertThat(findings).extracting(Finding::code)
                .containsExactlyInAnyOrder("NEVER_LOGGED_IN", "EXPIRED_ACCESS", "NO_ROLE");
    }

    @Test
    void invitedAccountsAreFlaggedButNotAskedForMfa() {
        List<Finding> findings = AccessReviewAgent.evaluate(
                row(null, "PENDING_VERIFICATION", "INVITED", null, "", false), NOW, 90, true);

        assertThat(findings).extracting(Finding::code)
                .contains("INVITED_NOT_ACTIVE", "ACCOUNT_UNVERIFIED");
        assertThat(findings).extracting(Finding::code).doesNotContain("MISSING_MFA");
    }
}
