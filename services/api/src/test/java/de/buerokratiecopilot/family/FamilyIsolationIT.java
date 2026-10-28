package de.buerokratiecopilot.family;

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

import de.buerokratiecopilot.family.FamilyAccessService.Basis;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies that a family member does not automatically see another member's personal documents
 * (spec §3.1, H-003 acceptance).
 *
 * <p>Two things make that criterion mean something, and both are tested. A document is shared unless
 * somebody marks it, because the platform must not turn an ordinary family file into a private one by
 * accident. And once marked, another member is told the document does not exist rather than that it is
 * not theirs — a refusal that says "this exists but is not yours" tells a family member what the others
 * keep, and in a household that is itself information.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class FamilyIsolationIT {

    @Autowired
    private FamilyAccessService family;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID household() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Familie Test', 'FAMILY_HOUSEHOLD') returning id
                """, UUID.class, "fam-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID member(UUID tenantId, String name, boolean owner) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', ?, 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "fm-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de",
                name);
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', ?)
                """, tenantId, userId, owner);
        return userId;
    }

    private UUID document(UUID tenantId, String name) {
        String hash = "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        return jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, ?, ?, 'application/pdf', 100, ?, 'UPLOAD', 'READY',
                        'NOT_REQUIRED')
                returning id
                """, UUID.class, tenantId, name, name, hash);
    }

    private FamilyAccessService.Decision decide(UUID tenantId, UUID userId, UUID documentId) {
        return RequestContext.runAs(tenantId, () -> family.decide(tenantId, userId, documentId));
    }

    private void markPersonal(UUID tenantId, UUID documentId, UUID owner, UUID markedBy) {
        RequestContext.runAs(tenantId, () -> {
            family.markPersonal(tenantId, documentId, owner, markedBy, null);
            return null;
        });
    }

    // ---------------------------------------------------------------- the criterion

    @Test
    @DisplayName("a member does not see another member's personal document")
    void anotherMembersPersonalDocumentIsNotVisible() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID documentId = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, documentId, mother, mother);

        FamilyAccessService.Decision decision = decide(tenantId, father, documentId);

        assertThat(decision.readable()).isFalse();
        assertThat(decision.basis()).isEqualTo(Basis.NOT_PERMITTED);
        assertThat(decision.ownerUserId()).isEqualTo(mother);
    }

    @Test
    @DisplayName("another member is told the document does not exist, not that it is not theirs")
    void refusalDoesNotRevealThatItExists() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID documentId = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, documentId, mother, mother);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> family.requireRead(tenantId, father, documentId)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found")
                .satisfies(ex -> assertThat(((ApiException) ex).errorCode())
                        .as("'this exists but is not yours' tells a family member what the others keep")
                        .isEqualTo(de.buerokratiecopilot.shared.error.ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("the member it belongs to sees it")
    void theOwnerSeesIt() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID documentId = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, documentId, mother, mother);

        FamilyAccessService.Decision decision = decide(tenantId, mother, documentId);

        assertThat(decision.readable()).isTrue();
        assertThat(decision.basis()).isEqualTo(Basis.OWN);
    }

    @Test
    @DisplayName("a document nobody marked is shared with the household")
    void unmarkedDocumentIsShared() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID documentId = document(tenantId, "stromrechnung.pdf");

        assertThat(decide(tenantId, father, documentId).readable())
                .as("the platform must not turn an ordinary family file into a private one by accident")
                .isTrue();
        assertThat(decide(tenantId, mother, documentId).basis()).isEqualTo(Basis.SHARED);
    }

    @Test
    @DisplayName("the owner can share a personal document, and then it is visible")
    void anExplicitShareMakesItVisible() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID documentId = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, documentId, mother, mother);

        RequestContext.runAs(tenantId, () -> {
            family.share(tenantId, documentId, mother, father, null);
            return null;
        });

        FamilyAccessService.Decision decision = decide(tenantId, father, documentId);
        assertThat(decision.readable()).isTrue();
        assertThat(decision.basis()).isEqualTo(Basis.SHARED_EXPLICITLY);
    }

    @Test
    @DisplayName("somebody else cannot share what is not theirs")
    void onlyTheOwnerCanShare() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID child = member(tenantId, "Kind", false);
        UUID documentId = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, documentId, mother, mother);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            family.share(tenantId, documentId, father, child, null);
            return null;
        })).isInstanceOf(ApiException.class)
                .hasMessageContaining("gekennzeichnet");
    }

    @Test
    @DisplayName("a withdrawn share closes the door again")
    void withdrawnShareClosesTheDoor() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID documentId = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, documentId, mother, mother);
        RequestContext.runAs(tenantId, () -> {
            family.share(tenantId, documentId, mother, father, null);
            family.revokeShare(tenantId, documentId, father);
            return null;
        });

        assertThat(decide(tenantId, father, documentId).readable()).isFalse();
    }

    @Test
    @DisplayName("an expired share no longer opens anything")
    void expiredShareDoesNotOpen() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID documentId = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, documentId, mother, mother);

        RequestContext.runAs(tenantId, () -> {
            family.share(tenantId, documentId, mother, father,
                    Instant.now().minus(1, ChronoUnit.HOURS));
            return null;
        });

        assertThat(decide(tenantId, father, documentId).readable()).isFalse();
    }

    @Test
    @DisplayName("marking personal can be undone, and then the household sees it again")
    void markingPersonalCanBeUndone() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID documentId = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, documentId, mother, mother);

        RequestContext.runAs(tenantId, () -> {
            family.markShared(tenantId, documentId, mother);
            return null;
        });

        assertThat(decide(tenantId, father, documentId).readable()).isTrue();
        assertThat(family.isPersonal(tenantId, documentId)).isFalse();
    }

    // ---------------------------------------------------------------- the household boundary

    @Test
    @DisplayName("a member of another household sees nothing")
    void anotherHouseholdSeesNothing() {
        UUID first = household();
        UUID second = household();
        UUID mother = member(first, "Mutter", true);
        UUID stranger = member(second, "Fremd", true);
        UUID documentId = document(first, "arztbrief.pdf");

        assertThatThrownBy(() -> RequestContext.runAs(second,
                () -> family.requireRead(second, stranger, documentId)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("somebody who is not a member of the household sees nothing")
    void aNonMemberSeesNothing() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID documentId = document(tenantId, "stromrechnung.pdf");

        FamilyAccessService.Decision decision = decide(tenantId, UUID.randomUUID(), documentId);

        assertThat(decision.readable()).isFalse();
    }

    @Test
    @DisplayName("a suspended member sees nothing")
    void aSuspendedMemberSeesNothing() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID documentId = document(tenantId, "stromrechnung.pdf");
        jdbc.update("update memberships set status = 'SUSPENDED' where tenant_id = ? and user_id = ?",
                tenantId, father);

        assertThat(decide(tenantId, father, documentId).readable()).isFalse();
    }

    // ---------------------------------------------------------------- lists and people

    @Test
    @DisplayName("the list of readable documents leaves out what is not readable")
    void theListLeavesOutPersonalDocuments() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID shared = document(tenantId, "stromrechnung.pdf");
        UUID personal = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, personal, mother, mother);

        var forFather = RequestContext.runAs(tenantId,
                () -> family.readableDocumentIds(tenantId, father, 50));
        var forMother = RequestContext.runAs(tenantId,
                () -> family.readableDocumentIds(tenantId, mother, 50));

        assertThat(forFather).extracting(row -> row.get("id")).contains(shared).doesNotContain(personal);
        assertThat(forMother).extracting(row -> row.get("id")).contains(shared, personal);
    }

    @Test
    @DisplayName("the list says why each document is readable")
    void theListNamesTheBasis() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID documentId = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, documentId, mother, mother);
        RequestContext.runAs(tenantId, () -> {
            family.share(tenantId, documentId, mother, father, null);
            return null;
        });

        var forFather = RequestContext.runAs(tenantId,
                () -> family.readableDocumentIds(tenantId, father, 50));

        assertThat(forFather).hasSize(1);
        assertThat(forFather.getFirst()).containsEntry("basis", "SHARED_EXPLICITLY");
    }

    @Test
    @DisplayName("the members of the household are listed by name")
    void membersAreListed() {
        UUID tenantId = household();
        member(tenantId, "Mutter", true);
        member(tenantId, "Vater", false);

        var members = RequestContext.runAs(tenantId, () -> family.members(tenantId));

        assertThat(members).hasSize(2);
        assertThat(members).extracting(row -> row.get("display_name"))
                .containsExactlyInAnyOrder("Mutter", "Vater");
    }

    @Test
    @DisplayName("a member can list their own personal documents")
    void personalDocumentsAreListed() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID documentId = document(tenantId, "arztbrief.pdf");
        markPersonal(tenantId, documentId, mother, mother);

        var personal = RequestContext.runAs(tenantId,
                () -> family.personalDocuments(tenantId, mother));

        assertThat(personal).hasSize(1);
        assertThat(personal.getFirst()).containsEntry("original_filename", "arztbrief.pdf");
    }

    // ---------------------------------------------------------------- what may be marked

    @Test
    @DisplayName("marking needs a member of this household")
    void markingNeedsAMemberOfThisHousehold() {
        UUID tenantId = household();
        UUID other = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID stranger = member(other, "Fremd", true);
        UUID documentId = document(tenantId, "arztbrief.pdf");

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            family.markPersonal(tenantId, documentId, stranger, mother, null);
            return null;
        })).isInstanceOf(ApiException.class).hasMessageContaining("Haushalt");
    }

    @Test
    @DisplayName("marking a document that does not exist is reported")
    void markingAnUnknownDocumentIsReported() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            family.markPersonal(tenantId, UUID.randomUUID(), mother, mother, null);
            return null;
        })).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("sharing a document that is not personal is refused as unnecessary")
    void sharingASharedDocumentIsRefused() {
        UUID tenantId = household();
        UUID mother = member(tenantId, "Mutter", true);
        UUID father = member(tenantId, "Vater", false);
        UUID documentId = document(tenantId, "stromrechnung.pdf");

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> {
            family.share(tenantId, documentId, mother, father, null);
            return null;
        })).isInstanceOf(ApiException.class).hasMessageContaining("nicht persönlich");
    }

    @Test
    @DisplayName("every basis a decision can have is named")
    void everyBasisIsNamed() {
        assertThat(FamilyAccessService.bases()).containsExactlyInAnyOrder(Basis.SHARED, Basis.OWN,
                Basis.SHARED_EXPLICITLY, Basis.NOT_PERMITTED);
    }
}
