package de.buerokratiecopilot.identity.access;

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
 * Verifies invitations, revocation and temporary access (spec §33, A-017).
 *
 * <p>Three rules are checked beyond the happy path, and each is a way the feature could be quietly
 * useless: an invitation token exists once and is stored hashed, because it is a credential; a temporary
 * right stops working by itself when its date has passed, because access that depends on somebody
 * remembering to remove it outlives its reason; and every change of access is recorded, because "how did
 * this person get to see this file" is asked precisely when something has gone wrong.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class InvitationAccessIT {

    @Autowired
    private InvitationService invitations;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant(String accountType) {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Einladung Test', ?) returning id
                """, UUID.class, "inv-" + UUID.randomUUID().toString().substring(0, 12), accountType);
    }

    private UUID person(String emailPrefix) {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Eingeladene Person', 'de', 'Europe/Berlin', 0,
                        false)
                returning id
                """, UUID.class, emailPrefix + "-" + UUID.randomUUID().toString().substring(0, 10)
                + "@example.de");
    }

    private UUID owner(UUID tenantId) {
        UUID userId = person("owner");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', true)
                """, tenantId, userId);
        return userId;
    }

    private UUID caseFor(UUID tenantId) {
        UUID caseId = jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title, status)
                values (gen_random_uuid(), ?, ?, 'Ein Verfahren', 'NEW')
                returning id
                """, UUID.class, tenantId, "AZ-" + UUID.randomUUID().toString().substring(0, 8));
        return caseId;
    }

    private InvitationService.Invitation invite(UUID tenantId, String email, UUID invitedBy) {
        return RequestContext.runAs(tenantId, () -> invitations.invite(tenantId, email, null, null,
                "Willkommen", null, invitedBy));
    }

    // ---------------------------------------------------------------- invitations

    @Test
    @DisplayName("an invitation is shown once and stored only as a hash")
    void theTokenIsStoredHashed() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);

        InvitationService.Invitation invitation = invite(tenantId, "neu@example.de", inviter);

        String stored = jdbc.queryForObject("select token_hash from invitations where id = ?", String.class,
                invitation.invitationId());
        assertThat(stored)
                .as("whoever holds the token can join the tenant, so it is a credential")
                .doesNotContain(invitation.token());
        assertThat(stored).isEqualTo(InvitationService.hash(invitation.token()));
        assertThat(invitation.asMap()).containsKey("note");
    }

    @Test
    @DisplayName("only one invitation per address may be open")
    void onlyOneOpenInvitationPerAddress() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        invite(tenantId, "neu@example.de", inviter);

        assertThatThrownBy(() -> invite(tenantId, "neu@example.de", inviter))
                .as("two live links for one address is one link too many, and nobody knows which was used")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("accepting an invitation makes the person a member and gives the role")
    void acceptingMakesAMember() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        UUID roleId = jdbc.queryForObject("select id from roles limit 1", UUID.class);
        InvitationService.Invitation invitation = RequestContext.runAs(tenantId,
                () -> invitations.invite(tenantId, "neu@example.de", roleId, null, null, null, inviter));
        UUID newcomer = person("neu");

        UUID membershipId = RequestContext.runAs(tenantId,
                () -> invitations.accept(invitation.token(), newcomer));

        assertThat(membershipId).isNotNull();
        assertThat(jdbc.queryForObject("""
                select status from memberships where id = ?
                """, String.class, membershipId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                select count(*) from membership_roles where membership_id = ?
                """, Integer.class, membershipId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from invitations where id = ?", String.class,
                invitation.invitationId())).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("an expired invitation is refused and marked as expired")
    void expiredInvitationIsRefused() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        InvitationService.Invitation invitation = RequestContext.runAs(tenantId,
                () -> invitations.invite(tenantId, "neu@example.de", null, null, null,
                        Instant.now().minus(1, ChronoUnit.HOURS), inviter));
        UUID newcomer = person("neu");

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> invitations.accept(invitation.token(), newcomer)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("abgelaufen");

        assertThat(jdbc.queryForObject("select status from invitations where id = ?", String.class,
                invitation.invitationId()))
                .as("an expired invitation must not keep looking open")
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("a withdrawn invitation stops working at once")
    void revokedInvitationStopsWorking() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        InvitationService.Invitation invitation = invite(tenantId, "neu@example.de", inviter);
        UUID newcomer = person("neu");

        RequestContext.runAs(tenantId, () -> {
            invitations.revokeInvitation(tenantId, invitation.invitationId(), inviter);
            return null;
        });

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> invitations.accept(invitation.token(), newcomer)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nicht mehr offen");
    }

    @Test
    @DisplayName("a token that was never issued is not found")
    void unknownTokenIsNotFound() {
        UUID tenantId = tenant("COMPANY");

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> invitations.accept("bcinv_0000000000000000", person("x"))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("inviting somebody who is already a member is refused")
    void invitingAnExistingMemberIsRefused() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        String email = jdbc.queryForObject("select email from users where id = ?", String.class, inviter);

        assertThatThrownBy(() -> invite(tenantId, email, inviter))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bereits");
    }

    @Test
    @DisplayName("an invitation of another tenant is not usable here")
    void anotherTenantsInvitationIsNotUsable() {
        UUID first = tenant("COMPANY");
        UUID second = tenant("COMPANY");
        InvitationService.Invitation invitation = invite(first, "neu@example.de", owner(first));

        // The token is looked up by hash without a tenant filter, which is correct - the person accepting
        // does not know the tenant yet - but the membership is created in the tenant the invitation names.
        UUID membershipId = RequestContext.runAs(first,
                () -> invitations.accept(invitation.token(), person("neu")));

        assertThat(jdbc.queryForObject("select tenant_id from memberships where id = ?", UUID.class,
                membershipId)).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from memberships where tenant_id = ?",
                Integer.class, second)).isZero();
    }

    // ---------------------------------------------------------------- temporary case access

    @Test
    @DisplayName("a temporary right to a case works and then expires by itself")
    void temporaryAccessExpiresByItself() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        UUID member = person("member");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                """, tenantId, member);
        UUID caseId = caseFor(tenantId);

        RequestContext.runAs(tenantId, () -> {
            invitations.grantCaseAccess(tenantId, caseId, member, "EXTERNAL_ADVISOR", "READ",
                    Instant.now().plus(1, ChronoUnit.HOURS), inviter);
            return null;
        });
        assertThat(RequestContext.runAs(tenantId, () -> invitations.mayReadCase(tenantId, member, caseId)))
                .isTrue();

        // The same grant, with a date that has passed: nothing has to be cleaned up for it to stop.
        RequestContext.runAs(tenantId, () -> {
            invitations.grantCaseAccess(tenantId, caseId, member, "EXTERNAL_ADVISOR", "READ",
                    Instant.now().minus(1, ChronoUnit.MINUTES), inviter);
            return null;
        });

        assertThat(RequestContext.runAs(tenantId, () -> invitations.mayReadCase(tenantId, member, caseId)))
                .as("access that depends on somebody removing it is access that outlives its reason")
                .isFalse();
        assertThat(RequestContext.runAs(tenantId,
                () -> invitations.caseAccess(tenantId, member, caseId))).isEmpty();
    }

    @Test
    @DisplayName("a withdrawn case right stops working at once")
    void revokedCaseAccessStops() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        UUID member = person("member");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                """, tenantId, member);
        UUID caseId = caseFor(tenantId);
        RequestContext.runAs(tenantId, () -> {
            invitations.grantCaseAccess(tenantId, caseId, member, "PARTICIPANT", "WRITE", null, inviter);
            return null;
        });

        RequestContext.runAs(tenantId, () -> {
            invitations.revokeCaseAccess(tenantId, caseId, member, inviter);
            return null;
        });

        assertThat(RequestContext.runAs(tenantId, () -> invitations.mayReadCase(tenantId, member, caseId)))
                .isFalse();
    }

    @Test
    @DisplayName("a person without a case right may not read the case")
    void withoutAGrantThereIsNoAccess() {
        UUID tenantId = tenant("COMPANY");
        UUID member = person("member");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                """, tenantId, member);
        UUID caseId = caseFor(tenantId);

        assertThat(RequestContext.runAs(tenantId, () -> invitations.mayReadCase(tenantId, member, caseId)))
                .as("a membership is not a right to every case")
                .isFalse();
    }

    @Test
    @DisplayName("a case right cannot be granted to somebody who is not a member")
    void accessNeedsMembership() {
        UUID tenantId = tenant("COMPANY");
        UUID caseId = caseFor(tenantId);
        UUID stranger = person("fremd");

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            invitations.grantCaseAccess(tenantId, caseId, stranger, "PARTICIPANT", "READ", null,
                    owner(tenantId));
            return null;
        })).isInstanceOf(ApiException.class).hasMessageContaining("Mitgliedschaft");
    }

    @Test
    @DisplayName("a case of another tenant is not reachable")
    void anotherTenantsCaseIsNotReachable() {
        UUID first = tenant("COMPANY");
        UUID second = tenant("COMPANY");
        UUID caseId = caseFor(first);
        UUID member = person("member");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                """, second, member);

        assertThatThrownBy(() -> RequestContext.runAs(second, () -> {
            invitations.grantCaseAccess(second, caseId, member, "PARTICIPANT", "READ", null, null);
            return null;
        })).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an unknown role or access level is refused")
    void unknownRoleOrLevelIsRefused() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        UUID member = person("member");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                """, tenantId, member);
        UUID caseId = caseFor(tenantId);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            invitations.grantCaseAccess(tenantId, caseId, member, "GODMODE", "READ", null, inviter);
            return null;
        })).isInstanceOf(ApiException.class).hasMessageContaining("Rolle");
        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            invitations.grantCaseAccess(tenantId, caseId, member, "PARTICIPANT", "EVERYTHING", null,
                    inviter);
            return null;
        })).isInstanceOf(ApiException.class).hasMessageContaining("Zugriffsstufe");
    }

    // ---------------------------------------------------------------- the record

    @Test
    @DisplayName("every change of access is recorded")
    void everyChangeIsRecorded() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        InvitationService.Invitation invitation = invite(tenantId, "neu@example.de", inviter);
        UUID newcomer = person("neu");
        RequestContext.runAs(tenantId, () -> invitations.accept(invitation.token(), newcomer));
        RequestContext.runAs(tenantId, () -> {
            invitations.suspendMember(tenantId, newcomer, inviter);
            return null;
        });

        var history = RequestContext.runAs(tenantId,
                () -> invitations.history(tenantId, newcomer));

        assertThat(history).extracting(row -> row.get("change_type"))
                .as("'how did this person get to see this file' is asked when something has gone wrong")
                .contains("INVITATION_ACCEPTED", "MEMBER_SUSPENDED");
    }

    @Test
    @DisplayName("a case grant and its withdrawal are recorded")
    void caseGrantsAreRecorded() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        UUID member = person("member");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                """, tenantId, member);
        UUID caseId = caseFor(tenantId);
        RequestContext.runAs(tenantId, () -> {
            invitations.grantCaseAccess(tenantId, caseId, member, "PARTICIPANT", "WRITE",
                    Instant.now().plus(1, ChronoUnit.DAYS), inviter);
            invitations.revokeCaseAccess(tenantId, caseId, member, inviter);
            return null;
        });

        var history = RequestContext.runAs(tenantId, () -> invitations.history(tenantId, member));

        assertThat(history).extracting(row -> row.get("change_type"))
                .contains("TEMPORARY_ACCESS_SET", "ACCESS_REVOKED");
    }

    @Test
    @DisplayName("suspending a member takes their access away without deleting what they did")
    void suspendingTakesAccessAway() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        UUID member = person("member");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false)
                """, tenantId, member);

        RequestContext.runAs(tenantId, () -> {
            invitations.suspendMember(tenantId, member, inviter);
            return null;
        });

        assertThat(jdbc.queryForObject("""
                select status from memberships where tenant_id = ? and user_id = ?
                """, String.class, tenantId, member)).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("select count(*) from memberships where tenant_id = ? and user_id = ?",
                Integer.class, tenantId, member))
                .as("the record of having been part of the tenant is kept")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the open invitations of a tenant are listed without a token")
    void openInvitationsAreListed() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        InvitationService.Invitation invitation = invite(tenantId, "neu@example.de", inviter);

        var pending = RequestContext.runAs(tenantId, () -> invitations.pendingInvitations(tenantId));

        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().values().stream().map(String::valueOf).toList())
                .allSatisfy(value -> assertThat(value).doesNotContain(invitation.token()));
    }

    @Test
    @DisplayName("overdue invitations can be closed in one step")
    void overdueInvitationsCanBeClosed() {
        UUID tenantId = tenant("COMPANY");
        UUID inviter = owner(tenantId);
        RequestContext.runAs(tenantId, () -> invitations.invite(tenantId, "alt@example.de", null, null,
                null, Instant.now().minus(1, ChronoUnit.DAYS), inviter));

        int expired = RequestContext.runAs(tenantId,
                () -> invitations.expireOverdueInvitations(tenantId));

        assertThat(expired).isEqualTo(1);
        assertThat(RequestContext.runAs(tenantId, () -> invitations.pendingInvitations(tenantId)))
                .isEmpty();
    }
}
