package de.buerokratiecopilot.accounts.sector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies that the public sector mode contains no automatic decisions about citizens' rights
 * (spec §3.1 and §35, H-006).
 *
 * <p>The acceptance criterion has two halves, and both are checked. Nothing in the mode may produce or
 * send a decision about somebody's rights — refused, not warned about, because a warning is something a
 * caller can ignore. And the legal analysis the mode requires is named, so that nobody can read this code
 * and conclude that question has been settled.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class PublicSectorGuardTest {

    @Autowired
    private PublicSectorGuard guard;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant(String accountType) {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Behörde Test', ?) returning id
                """, UUID.class, "ps-" + UUID.randomUUID().toString().substring(0, 12), accountType);
    }

    @Test
    @DisplayName("the mode is recognised by its account type")
    void theModeIsRecognised() {
        UUID authority = tenant("HIGH_SECURITY_ORGANIZATION");
        UUID company = tenant("COMPANY");

        assertThat(guard.isPublicSector(authority)).isTrue();
        assertThat(guard.isPublicSector(company)).isFalse();
        assertThat(guard.isPublicSector(null)).isFalse();
    }

    @Test
    @DisplayName("every automation that would decide about a person's rights is refused")
    void everyDecisionAutomationIsRefused() {
        UUID authority = tenant("HIGH_SECURITY_ORGANIZATION");

        for (String automation : PublicSectorGuard.prohibited()) {
            PublicSectorGuard.Verdict verdict = guard.check(authority, automation);

            assertThat(verdict.permitted())
                    .as("'%s' would be the platform making an administrative act", automation)
                    .isFalse();
            assertThat(verdict.reason())
                    .as("a refusal that does not say which automation was attempted teaches nothing")
                    .contains("nicht zulässig");
            assertThatThrownBy(() -> guard.requirePermitted(authority, automation))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("the automations that help a person decide are allowed")
    void helpingAutomationsAreAllowed() {
        UUID authority = tenant("HIGH_SECURITY_ORGANIZATION");

        for (String automation : PublicSectorGuard.permitted()) {
            assertThat(guard.check(authority, automation).permitted())
                    .as("a guard that stopped the platform sorting files would be removed within a week, "
                            + "taking the protection with it")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an unknown automation is not silently treated as a decision")
    void anUnknownAutomationRuns() {
        UUID authority = tenant("HIGH_SECURITY_ORGANIZATION");

        // The list of prohibited automations is a list of names, and a name that is not on it is not a
        // refusal. Claiming otherwise would make the guard a blanket ban wearing a list.
        assertThat(guard.check(authority, "SORT_BY_DATE").permitted()).isTrue();
        assertThat(PublicSectorGuard.prohibited()).doesNotContain("SORT_BY_DATE");
    }

    @Test
    @DisplayName("outside the public sector mode none of this applies")
    void outsideTheModeEverythingRuns() {
        UUID company = tenant("COMPANY");

        // A company deciding about its own invoices is deciding about its own money; refusing that would
        // be refusing the platform's main job.
        for (String automation : PublicSectorGuard.prohibited()) {
            assertThat(guard.check(company, automation).permitted()).isTrue();
        }
    }

    @Test
    @DisplayName("the required legal analysis is stated, not implied")
    void theLegalAnalysisIsStated() {
        String analysis = PublicSectorGuard.legalAnalysisRequired();

        assertThat(analysis)
                .as("the person who needs to know is the one about to switch the mode on")
                .contains("rechtliche Prüfung");
        assertThat(analysis).contains("keine automatischen Entscheidungen");
        assertThat(analysis).contains("dokumentieren");
    }

    @Test
    @DisplayName("the prohibited automations are named individually")
    void theProhibitedAutomationsAreNamed() {
        assertThat(PublicSectorGuard.prohibited())
                .contains("AUTOMATIC_ELIGIBILITY", "AUTOMATIC_REJECTION", "AUTOMATIC_APPROVAL",
                        "AUTO_SEND_DECISION")
                .hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("a prohibition is refused rather than warned about")
    void aProhibitionIsRefused() {
        UUID authority = tenant("HIGH_SECURITY_ORGANIZATION");

        assertThatThrownBy(() -> guard.requirePermitted(authority, "AUTOMATIC_REJECTION"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Person");
    }
}
