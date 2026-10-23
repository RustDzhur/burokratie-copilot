package de.buerokratiecopilot.identity.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Verifies onboarding and the administrator bootstrap (spec §3, A-018).
 *
 * <p>Two things are checked beyond the happy path. Onboarding twice with the same slug returns the
 * account that exists rather than creating a second one — the second run is what happens when the first
 * one's output was lost, not a request for two installations. And the bootstrap token works exactly once,
 * because the second attempt is the attack a one-time token exists to stop.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class OnboardingIT {

    @Autowired
    private OnboardingService onboarding;

    @Autowired
    private JdbcTemplate jdbc;

    private String slug() {
        return "onb-" + UUID.randomUUID().toString().substring(0, 12);
    }

    private OnboardingService.Request request(String slug) {
        return new OnboardingService.Request(slug, "Muster GmbH",
                "admin-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de",
                "Erste Administratorin", "de", "Europe/Berlin", "DE", "Muster GmbH");
    }

    // ---------------------------------------------------------------- onboarding

    @Test
    @DisplayName("onboarding creates the account, its organisation, its workspace and its owner")
    void onboardingCreatesEverything() {
        OnboardingService.Account account = onboarding.onboard(request(slug()));

        assertThat(account.tenantId()).isNotNull();
        assertThat(account.organizationId()).isNotNull();
        assertThat(account.workspaceId()).isNotNull();
        assertThat(account.adminUserId()).isNotNull();
        assertThat(account.alreadyExisted()).isFalse();

        // The workspace is the default one, which the schema allows exactly once per tenant.
        assertThat(jdbc.queryForObject("""
                select is_default from workspaces where id = ?
                """, Boolean.class, account.workspaceId())).isTrue();
        assertThat(jdbc.queryForObject("""
                select is_owner from memberships where tenant_id = ? and user_id = ?
                """, Boolean.class, account.tenantId(), account.adminUserId())).isTrue();
    }

    @Test
    @DisplayName("onboarding twice with the same slug does not create a second account")
    void onboardingIsIdempotentBySlug() {
        String slug = slug();

        OnboardingService.Account first = onboarding.onboard(request(slug));
        OnboardingService.Account second = onboarding.onboard(request(slug));

        assertThat(second.tenantId()).isEqualTo(first.tenantId());
        assertThat(second.alreadyExisted())
                .as("the second run is what happens when the first one's output was lost")
                .isTrue();
        assertThat(jdbc.queryForObject("select count(*) from tenants where slug = ?", Integer.class, slug))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a slug the schema would refuse gets a sentence instead of a constraint violation")
    void malformedSlugIsRefused() {
        assertThatThrownBy(() -> onboarding.onboard(request("-bad-")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Kurzname");
    }

    @Test
    @DisplayName("an address that already belongs to somebody is refused")
    void existingAddressIsRefused() {
        String email = "doppelt-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        onboarding.onboard(new OnboardingService.Request(slug(), "Erster", email, null, "de",
                "Europe/Berlin", "DE", null));

        assertThatThrownBy(() -> onboarding.onboard(new OnboardingService.Request(slug(), "Zweiter",
                email, null, "de", "Europe/Berlin", "DE", null)))
                .as("taking over an existing account silently would be worse than refusing")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bereits");
    }

    @Test
    @DisplayName("the administrator cannot sign in before finishing the bootstrap")
    void theAdminHasNoPasswordYet() {
        OnboardingService.Account account = onboarding.onboard(request(slug()));

        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class,
                account.adminUserId())).isNull();
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class,
                account.adminUserId())).isEqualTo("PENDING_VERIFICATION");
    }

    // ---------------------------------------------------------------- the bootstrap

    @Test
    @DisplayName("the bootstrap hands out a token once and stores only its hash")
    void theBootstrapTokenIsStoredHashed() {
        OnboardingService.Bootstrap bootstrap = onboarding.bootstrapAdmin(request(slug()));

        String stored = jdbc.queryForObject("""
                select token_hash from one_time_tokens where user_id = ?
                """, String.class, bootstrap.account().adminUserId());

        assertThat(stored).doesNotContain(bootstrap.token());
        assertThat(stored).isEqualTo(OnboardingService.hash(bootstrap.token()));
        assertThat(String.valueOf(bootstrap.asMap().get("note"))).contains("nur einmal");
    }

    @Test
    @DisplayName("a bootstrap token sets the first password and activates the administrator")
    void theBootstrapCompletes() {
        OnboardingService.Bootstrap bootstrap = onboarding.bootstrapAdmin(request(slug()));

        UUID userId = onboarding.completeBootstrap(bootstrap.token(), "EinSicheresPasswort-2026!", null);

        assertThat(userId).isEqualTo(bootstrap.account().adminUserId());
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, userId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class,
                userId))
                .as("the password is stored as a hash, never as the password")
                .isNotEqualTo("EinSicheresPasswort-2026!")
                .isNotNull();
        assertThat(onboarding.hasFinishedAdministrator()).isTrue();
    }

    @Test
    @DisplayName("a bootstrap token works exactly once")
    void theBootstrapTokenIsSingleUse() {
        OnboardingService.Bootstrap bootstrap = onboarding.bootstrapAdmin(request(slug()));
        onboarding.completeBootstrap(bootstrap.token(), "EinSicheresPasswort-2026!", null);

        assertThatThrownBy(() -> onboarding.completeBootstrap(bootstrap.token(),
                "EinAnderesPasswort-2026!", null))
                .as("the second attempt is the attack a one-time token exists to stop")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bereits verwendet");
    }

    @Test
    @DisplayName("a token that was never issued is not found")
    void unknownTokenIsNotFound() {
        assertThatThrownBy(() -> onboarding.completeBootstrap("bcboot_0000", "EinSicheresPasswort-2026!",
                null)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an expired token is refused")
    void expiredTokenIsRefused() {
        OnboardingService.Bootstrap bootstrap = onboarding.bootstrapAdmin(request(slug()));
        jdbc.update("""
                update one_time_tokens set expires_at = ? where user_id = ?
                """, java.sql.Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)),
                bootstrap.account().adminUserId());

        assertThatThrownBy(() -> onboarding.completeBootstrap(bootstrap.token(),
                "EinSicheresPasswort-2026!", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("abgelaufen");
    }

    @Test
    @DisplayName("a weak password is refused")
    void weakPasswordIsRefused() {
        OnboardingService.Bootstrap bootstrap = onboarding.bootstrapAdmin(request(slug()));

        assertThatThrownBy(() -> onboarding.completeBootstrap(bootstrap.token(), "kurz", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("12 Zeichen");
    }

    @Test
    @DisplayName("a bootstrap run for an existing account hands back a fresh token")
    void bootstrappingAgainIssuesAFreshToken() {
        String slug = slug();
        OnboardingService.Bootstrap first = onboarding.bootstrapAdmin(request(slug));
        OnboardingService.Bootstrap second = onboarding.bootstrapAdmin(request(slug));

        assertThat(second.alreadyExisted()).isTrue();
        assertThat(second.account().tenantId()).isEqualTo(first.account().tenantId());
        // The account is not created twice; the operator gets a token that works now.
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(jdbc.queryForObject("select count(*) from tenants where slug = ?", Integer.class, slug))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an administrator whose token was not used cannot sign in")
    void anUnfinishedAdministratorCannotSignIn() {
        String slug = slug();
        OnboardingService.Bootstrap bootstrap = onboarding.bootstrapAdmin(request(slug));
        UUID adminUserId = bootstrap.account().adminUserId();

        // The question is about this administrator, not about the installation: hasFinishedAdministrator
        // is answered for the whole installation and other tests here have finished one.
        assertThat(jdbc.queryForObject("select password_hash from users where id = ?", String.class,
                adminUserId)).isNull();
        assertThat(jdbc.queryForObject("""
                select count(*) from users where id = ? and status = 'ACTIVE'
                """, Integer.class, adminUserId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from one_time_tokens where user_id = ? and consumed_at is null
                """, Integer.class, adminUserId))
                .as("the token is still waiting to be used")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the account can be described by its slug")
    void theAccountCanBeDescribed() {
        String slug = slug();
        onboarding.onboard(request(slug));

        OnboardingService.Account described = onboarding.describeBySlug(slug);

        assertThat(described.alreadyExisted()).isTrue();
        assertThat(jdbc.queryForObject("select slug from tenants where id = ?", String.class,
                described.tenantId())).isEqualTo(slug);
    }

    @Test
    @DisplayName("an unknown slug is reported")
    void unknownSlugIsReported() {
        assertThatThrownBy(() -> onboarding.describeBySlug("gibt-es-nicht-" + UUID.randomUUID()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("onboarding is recorded in the audit trail")
    void onboardingIsAudited() {
        OnboardingService.Account account = onboarding.onboard(request(slug()));

        var entries = jdbc.queryForList("""
                select action from audit_events where tenant_id = ? and action = 'ACCOUNT_ONBOARDED'
                """, account.tenantId());

        assertThat(entries).hasSize(1);
    }

    @Test
    @DisplayName("onboarding configures no module of its own: the catalogue is the starting point")
    void theAccountStartsFromItsTypeDefaults() {
        OnboardingService.Account account = onboarding.onboard(request(slug()));

        // H-001 owns the rule; this only checks that onboarding leaves the account in a state where the
        // rule applies, rather than configuring modules of its own.
        assertThat(RequestContext.runAs(account.tenantId(),
                () -> jdbc.queryForObject("select count(*) from feature_flags where tenant_id = ?",
                        Integer.class, account.tenantId())))
                .as("onboarding configures nothing itself; the module catalogue decides")
                .isZero();
    }
}
