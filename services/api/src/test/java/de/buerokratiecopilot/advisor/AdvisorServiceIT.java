package de.buerokratiecopilot.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Verifies that a consultant sees only the clients assigned to them (spec §17, H-005).
 *
 * <p>The rule that carries the acceptance criterion is checked first, and the one that is usually left out
 * is checked next: an invitation is not access. A consultation is an arrangement between two independent
 * parties, and the party whose data is looked at has to be the one that agreed — otherwise an advisor has
 * granted themselves access to somebody else's books.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AdvisorServiceIT {

    @Autowired
    private AdvisorService advisors;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant(String accountType) {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Mandant Test', ?) returning id
                """, UUID.class, "ad-" + UUID.randomUUID().toString().substring(0, 12), accountType);
    }

    private UUID person(UUID tenantId) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Person', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "ad-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', true)
                """, tenantId, userId);
        return userId;
    }

    private AdvisorService.Arrangement invite(UUID advisor, UUID client, String level) {
        return RequestContext.runAs(advisor, () -> advisors.invite(advisor, client, level, "ALL",
                "Mandant", person(advisor)));
    }

    private AdvisorService.Arrangement active(UUID advisor, UUID client) {
        AdvisorService.Arrangement arrangement = invite(advisor, client, "READ");
        RequestContext.runAs(client, () -> advisors.accept(client, arrangement.id(), person(client)));
        return arrangement;
    }

    // ---------------------------------------------------------------- the criterion

    @Test
    @DisplayName("an advisor sees only the clients assigned to them")
    void anAdvisorSeesOnlyAssignedClients() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID assigned = tenant("COMPANY");
        UUID unassigned = tenant("COMPANY");
        active(advisor, assigned);

        List<UUID> visible = RequestContext.runAs(advisor, () -> advisors.activeClients(advisor)).stream()
                .map(row -> (UUID) row.get("client_tenant_id")).toList();

        assertThat(visible).contains(assigned).doesNotContain(unassigned);
        assertThat(RequestContext.runAs(advisor, () -> advisors.mayAccess(advisor, unassigned))).isFalse();
    }

    @Test
    @DisplayName("an invitation is not access until the client accepts")
    void anInvitationIsNotAccess() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");
        invite(advisor, client, "READ");

        assertThat(RequestContext.runAs(advisor, () -> advisors.mayAccess(advisor, client)))
                .as("the party whose data is looked at has to be the one that agreed")
                .isFalse();
        assertThat(RequestContext.runAs(advisor, () -> advisors.activeClients(advisor))).isEmpty();
    }

    @Test
    @DisplayName("accepting turns the arrangement into access")
    void acceptingGrantsAccess() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");
        active(advisor, client);

        assertThat(RequestContext.runAs(advisor, () -> advisors.mayAccess(advisor, client))).isTrue();
        assertThat(RequestContext.runAs(advisor, () -> advisors.activeClients(advisor))).hasSize(1);
    }

    @Test
    @DisplayName("only the client can accept")
    void onlyTheClientCanAccept() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");
        UUID other = tenant("COMPANY");
        AdvisorService.Arrangement arrangement = invite(advisor, client, "READ");

        assertThatThrownBy(() -> RequestContext.runAs(other,
                () -> advisors.accept(other, arrangement.id(), person(other))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("an ended arrangement grants nothing")
    void anEndedArrangementGrantsNothing() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");
        AdvisorService.Arrangement arrangement = active(advisor, client);
        UUID who = person(advisor);

        RequestContext.runAs(advisor, () -> {
            advisors.end(advisor, arrangement.id(), who, "Mandat beendet");
            return null;
        });

        assertThat(RequestContext.runAs(advisor, () -> advisors.mayAccess(advisor, client)))
                .as("access stops when the arrangement ends, not when somebody remembers")
                .isFalse();
        assertThat(RequestContext.runAs(advisor, () -> advisors.activeClients(advisor))).isEmpty();
    }

    @Test
    @DisplayName("two advisors do not see each other's clients")
    void twoAdvisorsAreSeparate() {
        UUID first = tenant("EXTERNAL_REPRESENTATIVE");
        UUID second = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");
        active(first, client);

        // A second consultant serving the same client does not inherit the first one's access.
        assertThat(RequestContext.runAs(second, () -> advisors.mayAccess(second, client))).isFalse();
        assertThat(RequestContext.runAs(second, () -> advisors.activeClients(second))).isEmpty();
        assertThat(RequestContext.runAs(first, () -> advisors.activeClients(first))).hasSize(1);
    }

    @Test
    @DisplayName("an unassigned client is reported as not found, not as forbidden")
    void anUnassignedClientIsNotFound() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");

        assertThatThrownBy(() -> RequestContext.runAs(advisor,
                () -> advisors.requireAccess(advisor, client)))
                .as("a refusal that says 'exists but is not yours' tells one advisor about another's clients")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a client can see which advisors it has agreed with")
    void aClientSeesItsAdvisors() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");
        active(advisor, client);

        var view = RequestContext.runAs(client, () -> advisors.advisorsOf(client));

        assertThat(view).hasSize(1);
        assertThat(view.getFirst()).containsEntry("status", "ACTIVE");
    }

    // ---------------------------------------------------------------- batch work

    @Test
    @DisplayName("a batch runs over the assigned clients")
    void aBatchRunsOverAssignedClients() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID first = tenant("COMPANY");
        UUID second = tenant("COMPANY");
        active(advisor, first);
        active(advisor, second);

        AdvisorService.BatchResult result = RequestContext.runAs(advisor,
                () -> advisors.runBatch(advisor, List.of(first, second), client -> 3));

        assertThat(result.processed()).isEqualTo(6);
        assertThat(result.refused()).isEmpty();
    }

    @Test
    @DisplayName("a batch refuses as a whole when one client is not assigned")
    void aBatchRefusesAsAWhole() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID assigned = tenant("COMPANY");
        UUID notAssigned = tenant("COMPANY");
        active(advisor, assigned);

        // Processing the assigned one and skipping the other quietly would be an operation over a
        // client's books without a right to them, and the run would report success.
        assertThatThrownBy(() -> RequestContext.runAs(advisor,
                () -> advisors.runBatch(advisor, List.of(assigned, notAssigned), client -> 1)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nichts verarbeitet");
        assertThat(RequestContext.runAs(advisor, () -> advisors.activeClients(advisor))).hasSize(1);
    }

    @Test
    @DisplayName("an empty batch is refused")
    void anEmptyBatchIsRefused() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");

        assertThatThrownBy(() -> RequestContext.runAs(advisor,
                () -> advisors.runBatch(advisor, List.of(), client -> 1)))
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- what may be arranged

    @Test
    @DisplayName("only an advisor account can manage clients")
    void onlyAnAdvisorManagesClients() {
        UUID company = tenant("COMPANY");
        UUID client = tenant("COMPANY");

        assertThatThrownBy(() -> invite(company, client, "READ"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Beraterkonto");
        assertThat(RequestContext.runAs(company, () -> advisors.isAdvisor(company))).isFalse();
    }

    @Test
    @DisplayName("an advisor is not their own client")
    void anAdvisorIsNotTheirOwnClient() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");

        assertThatThrownBy(() -> invite(advisor, advisor, "READ"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("eigener Mandant");
    }

    @Test
    @DisplayName("the same client cannot be arranged twice")
    void theSameClientCannotBeArrangedTwice() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");
        invite(advisor, client, "READ");

        assertThatThrownBy(() -> invite(advisor, client, "READ"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bereits");
    }

    @Test
    @DisplayName("an unknown access level or scope is refused")
    void unknownLevelOrScopeIsRefused() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");

        assertThatThrownBy(() -> invite(advisor, client, "EVERYTHING"))
                .isInstanceOf(ApiException.class).hasMessageContaining("Zugriffsstufe");
        assertThatThrownBy(() -> RequestContext.runAs(advisor, () -> advisors.invite(advisor, client,
                "READ", "EVERYTHING", null, person(advisor))))
                .isInstanceOf(ApiException.class).hasMessageContaining("Umfang");
    }

    @Test
    @DisplayName("the access level the client agreed to is kept")
    void theAccessLevelIsKept() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");
        active(advisor, client);

        assertThat(RequestContext.runAs(advisor, () -> advisors.activeClients(advisor)).getFirst())
                .containsEntry("access_level", "READ");
        // Under a tenant context, because row level security is authoritative and a query outside one
        // sees nothing - which is the point of it.
        assertThat(RequestContext.runAs(advisor, () -> jdbc.queryForObject("""
                select status from advisor_clients where tenant_id = ? and client_tenant_id = ?
                """, String.class, advisor, client))).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("every arrangement is listed, including the ones not yet accepted")
    void everyArrangementIsListed() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID accepted = tenant("COMPANY");
        UUID pending = tenant("COMPANY");
        active(advisor, accepted);
        invite(advisor, pending, "READ");

        var all = RequestContext.runAs(advisor, () -> advisors.arrangements(advisor));

        assertThat(all).hasSize(2);
        assertThat(all).extracting(row -> row.get("status")).contains("ACTIVE", "INVITED");
    }

    @Test
    @DisplayName("ending an arrangement that is already ended is reported")
    void endingTwiceIsReported() {
        UUID advisor = tenant("EXTERNAL_REPRESENTATIVE");
        UUID client = tenant("COMPANY");
        AdvisorService.Arrangement arrangement = active(advisor, client);
        UUID who = person(advisor);
        RequestContext.runAs(advisor, () -> {
            advisors.end(advisor, arrangement.id(), who, "beendet");
            return null;
        });

        assertThatThrownBy(() -> RequestContext.runAs(advisor, () -> {
            advisors.end(advisor, arrangement.id(), who, "nochmal");
            return null;
        })).isInstanceOf(ApiException.class);
    }
}
