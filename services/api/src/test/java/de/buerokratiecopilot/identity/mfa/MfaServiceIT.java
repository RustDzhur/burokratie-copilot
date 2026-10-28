package de.buerokratiecopilot.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
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
 * Verifies the second factor (spec §33, A-014).
 *
 * <p>Two things are tested beyond the arithmetic of the algorithm. An enrolment that has not been
 * confirmed is not a factor — treating it as one locks people out of their own accounts — and a recovery
 * code works exactly once, because a code that can be used twice is a password somebody wrote down.</p>
 *
 * <p>The codes are computed by the same implementation under test, which is a real limitation: it proves
 * the round trip and the window, not agreement with an authenticator application. The algorithm is
 * RFC 6238 as published, and the test that would prove interoperability is a device, not a unit test.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class MfaServiceIT {

    @Autowired
    private MfaService mfa;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'MFA Test', 'COMPANY') returning id
                """, UUID.class, "mfa-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID user(UUID tenantId) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "mf-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                """, tenantId, userId);
        return userId;
    }

    private MfaService.Enrolment enrol(UUID tenantId, UUID userId) {
        return RequestContext.runAs(tenantId, () -> mfa.enrol(tenantId, userId, "Authenticator"));
    }

    /** The code an authenticator application would show for a secret now. */
    private String currentCode(String secretBase32) {
        return Totp.code(MfaService.decodeBase32(secretBase32), Totp.stepAt(Instant.now().getEpochSecond()));
    }

    private MfaService.Confirmation confirm(UUID tenantId, UUID userId, MfaService.Enrolment enrolment) {
        return RequestContext.runAs(tenantId, () -> mfa.confirm(tenantId, userId, enrolment.methodId(),
                currentCode(enrolment.secretBase32())));
    }

    // ---------------------------------------------------------------- the algorithm, on its own

    @Test
    @DisplayName("the code for a step is six digits and stable")
    void codeIsStable() {
        byte[] secret = Totp.newSecret();

        String first = Totp.code(secret, 12345);

        assertThat(first).hasSize(6).matches("\\d{6}");
        assertThat(Totp.code(secret, 12345)).isEqualTo(first);
    }

    @Test
    @DisplayName("a different step gives a different code")
    void differentStepsDiffer() {
        byte[] secret = Totp.newSecret();

        assertThat(Totp.code(secret, 100)).isNotEqualTo(Totp.code(secret, 101));
    }

    @Test
    @DisplayName("the drift window accepts the step before and after, and no more")
    void driftWindowIsBounded() {
        byte[] secret = Totp.newSecret();
        long now = 1_772_000_000L;
        long step = Totp.stepAt(now);

        assertThat(Totp.isValid(secret, Totp.code(secret, step), now)).isTrue();
        assertThat(Totp.isValid(secret, Totp.code(secret, step - 1), now))
                .as("clocks are slightly wrong, so one step either side is accepted")
                .isTrue();
        assertThat(Totp.isValid(secret, Totp.code(secret, step + 1), now)).isTrue();
        assertThat(Totp.isValid(secret, Totp.code(secret, step - 2), now))
                .as("widening this would multiply the codes an attacker may try")
                .isFalse();
        assertThat(Totp.isValid(secret, Totp.code(secret, step + 2), now)).isFalse();
    }

    @Test
    @DisplayName("a malformed code is refused without comparing anything")
    void malformedCodeIsRefused() {
        byte[] secret = Totp.newSecret();
        long now = 1_772_000_000L;

        assertThat(Totp.isValid(secret, null, now)).isFalse();
        assertThat(Totp.isValid(secret, "", now)).isFalse();
        assertThat(Totp.isValid(secret, "abcdef", now)).isFalse();
        assertThat(Totp.isValid(secret, "12345", now)).isFalse();
        assertThat(Totp.isValid(null, "123456", now)).isFalse();
    }

    @Test
    @DisplayName("the provisioning URI is the format authenticator applications read")
    void provisioningUriIsStandard() {
        byte[] secret = Totp.newSecret();

        String uri = Totp.provisioningUri("Bürokratie-Copilot", "test@example.de", secret);

        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=" + Totp.base32(secret));
        assertThat(uri).contains("algorithm=SHA1").contains("digits=6").contains("period=30");
    }

    @Test
    @DisplayName("base32 encodes and decodes back to the same bytes")
    void base32RoundTrips() {
        byte[] secret = Totp.newSecret();

        assertThat(MfaService.decodeBase32(Totp.base32(secret))).isEqualTo(secret);
    }

    // ---------------------------------------------------------------- enrolment

    @Test
    @DisplayName("an enrolment is not a factor until a code from it is confirmed")
    void enrolmentIsNotAFactorYet() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);

        MfaService.Enrolment enrolment = enrol(tenantId, userId);

        assertThat(RequestContext.runAs(tenantId, () -> mfa.hasActiveFactor(userId)))
                .as("treating an unconfirmed secret as a factor locks people out of their own accounts")
                .isFalse();
        assertThat(enrolment.asMap()).containsKey("provisioningUri");
        assertThat(String.valueOf(enrolment.asMap().get("note"))).contains("bestätigt");
    }

    @Test
    @DisplayName("the secret is stored encrypted")
    void theSecretIsStoredEncrypted() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);

        String stored = jdbc.queryForObject("select secret_encrypted from mfa_methods where id = ?",
                String.class, enrolment.methodId());

        assertThat(stored).doesNotContain(enrolment.secretBase32());
        assertThat(stored).startsWith("v1.");
    }

    @Test
    @DisplayName("confirming with the right code activates the factor and issues recovery codes")
    void confirmActivates() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);

        MfaService.Confirmation confirmation = confirm(tenantId, userId, enrolment);

        assertThat(confirmation.recoveryCodes()).hasSize(10);
        assertThat(RequestContext.runAs(tenantId, () -> mfa.hasActiveFactor(userId))).isTrue();
        assertThat(RequestContext.runAs(tenantId, () -> mfa.remainingRecoveryCodes(userId)))
                .isEqualTo(10);
    }

    @Test
    @DisplayName("confirming with a wrong code leaves the factor inactive")
    void wrongCodeDoesNotActivate() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> mfa.confirm(tenantId, userId,
                enrolment.methodId(), "000000")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("stimmt nicht");

        assertThat(RequestContext.runAs(tenantId, () -> mfa.hasActiveFactor(userId))).isFalse();
    }

    @Test
    @DisplayName("a factor cannot be confirmed twice")
    void confirmationHappensOnce() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);
        confirm(tenantId, userId, enrolment);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> mfa.confirm(tenantId, userId,
                enrolment.methodId(), currentCode(enrolment.secretBase32()))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bereits bestätigt");
    }

    // ---------------------------------------------------------------- verification and recovery

    @Test
    @DisplayName("a valid code verifies")
    void validCodeVerifies() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);
        confirm(tenantId, userId, enrolment);

        MfaService.Verification verification = RequestContext.runAs(tenantId, () -> mfa.verify(tenantId,
                userId, currentCode(enrolment.secretBase32())));

        assertThat(verification.valid()).isTrue();
        assertThat(verification.method()).isEqualTo("TOTP");
    }

    @Test
    @DisplayName("a wrong code does not verify")
    void wrongCodeDoesNotVerify() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);
        confirm(tenantId, userId, enrolment);

        assertThat(RequestContext.runAs(tenantId, () -> mfa.verify(tenantId, userId, "000000")).valid())
                .isFalse();
    }

    @Test
    @DisplayName("a recovery code verifies exactly once")
    void recoveryCodeWorksOnce() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);
        String recoveryCode = confirm(tenantId, userId, enrolment).recoveryCodes().getFirst();

        assertThat(RequestContext.runAs(tenantId, () -> mfa.verify(tenantId, userId, recoveryCode)).valid())
                .as("it exists for the telephone that was lost")
                .isTrue();
        assertThat(RequestContext.runAs(tenantId, () -> mfa.verify(tenantId, userId, recoveryCode)).valid())
                .as("a recovery code that works twice is a password somebody wrote down")
                .isFalse();
        assertThat(RequestContext.runAs(tenantId, () -> mfa.remainingRecoveryCodes(userId)))
                .isEqualTo(9);
    }

    @Test
    @DisplayName("recovery codes are stored hashed")
    void recoveryCodesAreHashed() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);
        String recoveryCode = confirm(tenantId, userId, enrolment).recoveryCodes().getFirst();

        List<String> stored = jdbc.query("""
                select code_hash from mfa_recovery_codes where user_id = ?
                """, (rs, row) -> rs.getString("code_hash"), userId);

        assertThat(stored).allSatisfy(hash -> assertThat(hash).doesNotContain(recoveryCode));
        assertThat(stored).allSatisfy(hash -> assertThat(hash).hasSize(64));
    }

    @Test
    @DisplayName("re-issuing recovery codes replaces the old ones")
    void reissuingReplacesOldCodes() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment first = enrol(tenantId, userId);
        String oldCode = confirm(tenantId, userId, first).recoveryCodes().getFirst();

        // A second enrolment is a new device, and the old pile of codes must not stay usable.
        MfaService.Enrolment second = enrol(tenantId, userId);
        confirm(tenantId, userId, second);

        assertThat(RequestContext.runAs(tenantId, () -> mfa.verify(tenantId, userId, oldCode)).valid())
                .as("a growing pile of forgotten codes is a growing pile of ways in")
                .isFalse();
        assertThat(RequestContext.runAs(tenantId, () -> mfa.remainingRecoveryCodes(userId)))
                .isEqualTo(10);
    }

    // ---------------------------------------------------------------- switching it off and requiring it

    @Test
    @DisplayName("disabling a factor removes the secret and invalidates the recovery codes")
    void disablingRemovesEverything() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);
        String recoveryCode = confirm(tenantId, userId, enrolment).recoveryCodes().getFirst();

        RequestContext.runAs(tenantId, () -> {
            mfa.disable(tenantId, userId, enrolment.methodId());
            return null;
        });

        assertThat(RequestContext.runAs(tenantId, () -> mfa.hasActiveFactor(userId))).isFalse();
        assertThat(jdbc.queryForObject("select disabled_at from mfa_methods where id = ?",
                java.sql.Timestamp.class, enrolment.methodId())).isNotNull();
        assertThat(jdbc.queryForObject("select secret_encrypted from mfa_methods where id = ?",
                String.class, enrolment.methodId()))
                .as("the schema ties the secret to the type, so it stays - encrypted - and the factor is "
                        + "off because disabled_at says so")
                .isNotNull();
        assertThat(RequestContext.runAs(tenantId, () -> mfa.verify(tenantId, userId, recoveryCode)).valid())
                .isFalse();
    }

    @Test
    @DisplayName("a factor of another user is not reachable")
    void anotherUsersFactorIsNotReachable() {
        UUID tenantId = tenant();
        UUID owner = user(tenantId);
        UUID stranger = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, owner);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> mfa.confirm(tenantId, stranger,
                enrolment.methodId(), currentCode(enrolment.secretBase32()))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a tenant can require a second factor, and the setting is per tenant")
    void mfaCanBeRequiredPerTenant() {
        UUID first = tenant();
        UUID second = tenant();
        UUID userId = user(first);

        assertThat(RequestContext.runAs(first, () -> mfa.isMfaRequired(first))).isFalse();

        RequestContext.runAs(first, () -> {
            mfa.requireMfa(first, true, userId);
            return null;
        });

        assertThat(RequestContext.runAs(first, () -> mfa.isMfaRequired(first))).isTrue();
        assertThat(RequestContext.runAs(second, () -> mfa.isMfaRequired(second)))
                .as("one tenant requiring a second factor must not require it of another")
                .isFalse();
    }

    @Test
    @DisplayName("enrolling for a user who is not a member of the tenant is refused")
    void enrollingOutsideTheTenantIsRefused() {
        UUID tenantId = tenant();
        UUID other = tenant();
        UUID stranger = user(other);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> mfa.enrol(tenantId, stranger, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the list of factors contains no secret")
    void theListContainsNoSecret() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);

        var methods = RequestContext.runAs(tenantId, () -> mfa.list(userId));

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst()).containsEntry("has_secret", true);
        assertThat(methods.getFirst().values().stream().map(String::valueOf).toList())
                .allSatisfy(value -> assertThat(value).doesNotContain(enrolment.secretBase32()));
    }

    @Test
    @DisplayName("enabling and disabling a factor are recorded in the audit trail")
    void mfaChangesAreAudited() {
        UUID tenantId = tenant();
        UUID userId = user(tenantId);
        MfaService.Enrolment enrolment = enrol(tenantId, userId);
        confirm(tenantId, userId, enrolment);
        RequestContext.runAs(tenantId, () -> {
            mfa.disable(tenantId, userId, enrolment.methodId());
            return null;
        });

        var entries = jdbc.queryForList("""
                select action from audit_events where tenant_id = ? and entity_id = ? order by created_at
                """, tenantId, userId);

        assertThat(entries).extracting(row -> row.get("action"))
                .contains("MFA_ENABLED", "MFA_DISABLED");
    }
}
