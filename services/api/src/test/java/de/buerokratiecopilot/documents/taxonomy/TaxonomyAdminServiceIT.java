package de.buerokratiecopilot.documents.taxonomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies the administrator interface to the taxonomy (spec §6, B-008b).
 *
 * <p>The rule that carries this is that a rule which has not been verified does not run. A regular
 * expression that matches too broadly does not report an error — it files documents in the wrong place,
 * and that is discovered much later as a stack of mis-filed papers. So a pattern whose failure would be
 * invisible is created inactive and waits for a second person, and the person who wrote it cannot be that
 * second person.</p>
 */
@SpringBootTest
@ExtendWith(TenantContextExtension.class)
@ExtendWith(TenantContextExtension.class)
@ActiveProfiles("test")
class TaxonomyAdminServiceIT {

    @Autowired
    private TaxonomyAdminService taxonomy;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Taxonomie Test', 'COMPANY') returning id
                """, UUID.class, "tx-" + UUID.randomUUID().toString().substring(0, 12));
    }

    /** A person, optionally with the permission the endpoint asks for. */
    private UUID person(UUID tenantId, boolean withPermission) {
        return RequestContext.runAs(tenantId, () -> {
            UUID userId = jdbc.queryForObject("""
                    insert into users (id, email, status, display_name, locale, time_zone,
                                       failed_login_attempts, must_change_password)
                    values (gen_random_uuid(), ?, 'ACTIVE', 'Person', 'de', 'Europe/Berlin', 0, false)
                    returning id
                    """, UUID.class, "tx-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
            UUID membershipId = jdbc.queryForObject("""
                    insert into memberships (id, tenant_id, user_id, status, is_owner)
                    values (gen_random_uuid(), ?, ?, 'ACTIVE', false) returning id
                    """, UUID.class, tenantId, userId);
            if (withPermission) {
                UUID roleId = jdbc.queryForObject("""
                        insert into roles (id, tenant_id, code, name)
                        values (gen_random_uuid(), ?, ?, '{"de":"Taxonomie"}'::jsonb) returning id
                        """, UUID.class, tenantId, "tax-" + UUID.randomUUID().toString().substring(0, 8));
                // Read, not created: creating it here is what let a permission nobody holds pass the service
                // tests while every endpoint answered 403.
                String permissionCode = "taxonomy.manage";
                UUID permissionId = jdbc.queryForObject("""
                        select id from permissions where code = ?
                        """, UUID.class, permissionCode);
                jdbc.update("insert into role_permissions (role_id, permission_id) values (?, ?)", roleId,
                        permissionId);
                jdbc.update("""
                        insert into membership_roles (membership_id, role_id, granted_at) values (?, ?, now())
                        """, membershipId, roleId);
            }
            return userId;
        });
    }

    /** A real user, because the audit trail names an actor and the column is a foreign key. */
    private UUID anyUser() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Administrator', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "tx-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    private String code() {
        return "typ_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A type, created by a real person; the record goes to the tenant that person belongs to. */
    private UUID type(UUID actor) {
        return RequestContext.runAs(null, () -> taxonomy.createType(code(), "FINANCE", "Testtyp", null,
                null, false, 100, actor)).id();
    }

    // ---------------------------------------------------------------- the types

    @Test
    @DisplayName("a document type can be created and is listed")
    void aTypeCanBeCreated() {
        UUID actor = anyUser();
        String code = code();

        TaxonomyAdminService.DocumentType created = RequestContext.runAs(null, () -> taxonomy.createType(
                code, "GOVERNMENT", "Behördenschreiben", "Schreiben von Behörden", null, true, 20, actor));

        assertThat(created.code()).isEqualTo(code);
        assertThat(created.category()).isEqualTo("GOVERNMENT");
        assertThat(created.requiresReview()).isTrue();
        assertThat(RequestContext.runAs(null, () -> taxonomy.listTypes(true)))
                .extracting(row -> row.get("code")).contains(code);
    }

    @Test
    @DisplayName("the code is normalised and quoted into a sentence when it cannot be")
    void theCodeIsChecked() {
        UUID actor = anyUser();

        String suffix = UUID.randomUUID().toString().substring(0, 6);
        assertThat(RequestContext.runAs(null, () -> taxonomy.createType("Mein Typ " + suffix, "OTHER",
                "x", null, null, false, 0, actor)).code()).isEqualTo("mein_typ_" + suffix);
        assertThatThrownBy(() -> RequestContext.runAs(null, () -> taxonomy.createType("-", "OTHER", "x",
                null, null, false, 0, actor)))
                .isInstanceOf(ApiException.class).hasMessageContaining("Code");
    }

    @Test
    @DisplayName("a type that already exists is not created twice")
    void aDuplicateTypeIsRefused() {
        UUID actor = anyUser();
        String code = code();
        RequestContext.runAs(null, () -> taxonomy.createType(code, "OTHER", "x", null, null, false, 0, actor));

        assertThatThrownBy(() -> RequestContext.runAs(null, () -> taxonomy.createType(code, "OTHER", "y",
                null, null, false, 0, actor)))
                .isInstanceOf(ApiException.class).hasMessageContaining("bereits");
    }

    @Test
    @DisplayName("a type can be switched off, and the code cannot be changed")
    void aTypeCanBeSwitchedOff() {
        UUID actor = anyUser();
        UUID typeId = type(actor);

        TaxonomyAdminService.DocumentType updated = RequestContext.runAs(null, () -> taxonomy.updateType(
                typeId, false, true, 5, null, null, actor));

        assertThat(updated.active()).isFalse();
        assertThat(updated.requiresReview()).isTrue();
        assertThat(updated.sortOrder()).isEqualTo(5);
        // The code is not among the parameters: documents refer to it.
        assertThat(RequestContext.runAs(null, () -> taxonomy.updateType(typeId, true, null, null, null, null,
                actor)).code()).isNotBlank();
    }

    @Test
    @DisplayName("a type that does not exist is reported")
    void anUnknownTypeIsReported() {
        assertThatThrownBy(() -> RequestContext.runAs(null, () -> taxonomy.updateType(UUID.randomUUID(),
                false, null, null, null, null, anyUser())))
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- the rules

    @Test
    @DisplayName("a keyword rule takes effect at once, because it cannot fail invisibly")
    void aKeywordRuleIsActiveAtOnce() {
        UUID tenantId = tenant();
        UUID actor = person(tenantId, true);
        UUID typeId = type(actor);

        TaxonomyAdminService.Rule rule = RequestContext.runAs(tenantId, () -> taxonomy.addRule(tenantId,
                typeId, "KEYWORD", "Rechnung", 0.8, false, actor));

        assertThat(rule.active()).isTrue();
        assertThat(rule.verified()).isTrue();
        assertThat(rule.asMap().get("note")).isNull();
    }

    @Test
    @DisplayName("a pattern rule does not run until a second person has looked at it")
    void aPatternRuleWaitsForVerification() {
        UUID tenantId = tenant();
        UUID actor = person(tenantId, true);
        UUID typeId = type(actor);

        TaxonomyAdminService.Rule rule = RequestContext.runAs(tenantId, () -> taxonomy.addRule(tenantId,
                typeId, "REGEX", "RE-\\d{4}-\\d+", 0.9, false, actor));

        assertThat(rule.active())
                .as("a pattern that matches too broadly files documents in the wrong place and reports "
                        + "nothing")
                .isFalse();
        assertThat(rule.verified()).isFalse();
        assertThat(String.valueOf(rule.asMap().get("note"))).contains("zweite Person");
        assertThat(RequestContext.runAs(tenantId, () -> taxonomy.awaitingVerification(tenantId)))
                .extracting(row -> row.get("id")).contains(rule.id());
    }

    @Test
    @DisplayName("nobody confirms their own rule")
    void nobodyConfirmsTheirOwnRule() {
        UUID tenantId = tenant();
        UUID author = person(tenantId, true);
        UUID typeId = type(author);
        TaxonomyAdminService.Rule rule = RequestContext.runAs(tenantId, () -> taxonomy.addRule(tenantId,
                typeId, "REGEX", "RE-\\d+", 0.5, false, author));

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> taxonomy.verifyRule(tenantId, rule.id(), author)))
                .as("a rule one person both wrote and approved is a rule nobody checked")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("eigene");
    }

    @Test
    @DisplayName("a second person confirms the rule and it starts working")
    void aSecondPersonConfirmsTheRule() {
        UUID tenantId = tenant();
        UUID author = person(tenantId, true);
        UUID verifier = person(tenantId, true);
        UUID typeId = type(author);
        TaxonomyAdminService.Rule rule = RequestContext.runAs(tenantId, () -> taxonomy.addRule(tenantId,
                typeId, "REGEX", "RE-\\d+", 0.5, false, author));

        TaxonomyAdminService.Rule verified = RequestContext.runAs(tenantId,
                () -> taxonomy.verifyRule(tenantId, rule.id(), verifier));

        assertThat(verified.active()).isTrue();
        assertThat(verified.verified()).isTrue();
        assertThat(RequestContext.runAs(tenantId, () -> taxonomy.awaitingVerification(tenantId)))
                .extracting(row -> row.get("id")).doesNotContain(rule.id());
    }

    @Test
    @DisplayName("a broken regular expression is refused now, not at classification time")
    void aBrokenPatternIsRefusedNow() {
        UUID tenantId = tenant();
        UUID actor = person(tenantId, true);
        UUID typeId = type(actor);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> taxonomy.addRule(tenantId, typeId,
                "REGEX", "RE-(\\d+", 0.5, false, actor)))
                .as("refused now, because at classification time nobody would connect the two")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Ausdruck");
    }

    @Test
    @DisplayName("a pattern that is too short, an unknown kind and an impossible weight are refused")
    void theRuleValuesAreChecked() {
        UUID tenantId = tenant();
        UUID actor = person(tenantId, true);
        UUID typeId = type(actor);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> taxonomy.addRule(tenantId, typeId, "KEYWORD", "x", 0.5, false, actor)))
                .isInstanceOf(ApiException.class).hasMessageContaining("zwei Zeichen");
        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> taxonomy.addRule(tenantId, typeId, "TELEPATHY", "abc", 0.5, false, actor)))
                .isInstanceOf(ApiException.class).hasMessageContaining("Regelart");
        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> taxonomy.addRule(tenantId, typeId, "KEYWORD", "abc", 1.5, false, actor)))
                .isInstanceOf(ApiException.class).hasMessageContaining("Gewicht");
    }

    @Test
    @DisplayName("a rule can be switched off and on again")
    void aRuleCanBeSwitchedOff() {
        UUID tenantId = tenant();
        UUID actor = person(tenantId, true);
        UUID typeId = type(actor);
        TaxonomyAdminService.Rule rule = RequestContext.runAs(tenantId, () -> taxonomy.addRule(tenantId,
                typeId, "KEYWORD", "Mahnung", 0.7, false, actor));

        RequestContext.runAs(tenantId, () -> {
            taxonomy.setRuleActive(tenantId, rule.id(), false, actor);
            return null;
        });
        assertThat(RequestContext.runAs(tenantId, () -> jdbc.queryForObject(
                "select is_active from document_type_rules where id = ?",
                Boolean.class, rule.id()))).isFalse();

        RequestContext.runAs(tenantId, () -> {
            taxonomy.setRuleActive(tenantId, rule.id(), true, actor);
            return null;
        });
        assertThat(RequestContext.runAs(tenantId, () -> jdbc.queryForObject(
                "select is_active from document_type_rules where id = ?",
                Boolean.class, rule.id()))).isTrue();
    }

    @Test
    @DisplayName("a rule can be removed and is then gone")
    void aRuleCanBeRemoved() {
        UUID tenantId = tenant();
        UUID actor = person(tenantId, true);
        UUID typeId = type(actor);
        TaxonomyAdminService.Rule rule = RequestContext.runAs(tenantId, () -> taxonomy.addRule(tenantId,
                typeId, "KEYWORD", "Kündigung", 0.9, false, actor));

        RequestContext.runAs(tenantId, () -> {
            taxonomy.deleteRule(tenantId, rule.id(), actor);
            return null;
        });

        assertThat(jdbc.queryForObject("select count(*) from document_type_rules where id = ?",
                Integer.class, rule.id())).isZero();
    }

    // ---------------------------------------------------------------- who may

    @Test
    @DisplayName("without the permission the taxonomy cannot be administered")
    void withoutThePermissionNobodyAdministersIt() {
        UUID tenantId = tenant();
        UUID outsider = person(tenantId, false);

        assertThat(RequestContext.runAs(tenantId, () -> taxonomy.mayManage(tenantId, outsider))).isFalse();
        assertThatThrownBy(() -> RequestContext.runAs(tenantId,
                () -> taxonomy.requireManage(tenantId, outsider)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("taxonomy.manage");
    }

    @Test
    @DisplayName("with the permission it can")
    void withThePermissionItCan() {
        UUID tenantId = tenant();
        UUID allowed = person(tenantId, true);

        assertThat(RequestContext.runAs(tenantId, () -> taxonomy.mayManage(tenantId, allowed))).isTrue();
        RequestContext.runAs(tenantId, () -> {
            taxonomy.requireManage(tenantId, allowed);
            return null;
        });
    }

    @Test
    @DisplayName("a permission of another tenant does not help")
    void anotherTenantsPermissionDoesNotHelp() {
        UUID first = tenant();
        UUID second = tenant();
        UUID person = person(second, true);

        assertThat(RequestContext.runAs(first, () -> taxonomy.mayManage(first, person))).isFalse();
    }

    // ---------------------------------------------------------------- the record

    @Test
    @DisplayName("every change to the taxonomy is recorded")
    void everyChangeIsRecorded() {
        UUID tenantId = tenant();
        UUID actor = person(tenantId, true);
        UUID typeId = type(actor);
        RequestContext.runAs(tenantId, () -> taxonomy.addRule(tenantId, typeId, "KEYWORD", "Vertrag", 0.6,
                false, actor));

        var history = RequestContext.runAs(tenantId, () -> taxonomy.history(tenantId, 50));

        assertThat(history).extracting(row -> row.get("action"))
                .as("classification decides where a document ends up, and a change with no record is one "
                        + "nobody can find later")
                .contains("DOCUMENT_TYPE_CREATED", "DOCUMENT_TYPE_RULE_ADDED");
    }

    // ---------------------------------------------------------------- the boundary

    @Test
    @DisplayName("a tenant sees its own rules and the platform's, and not another tenant's")
    void rulesAreScoped() {
        UUID first = tenant();
        UUID second = tenant();
        UUID actor = person(first, true);
        UUID typeId = type(actor);

        RequestContext.runAs(first, () -> taxonomy.addRule(first, typeId, "KEYWORD", "NurErster", 0.5,
                false, actor));
        RequestContext.runAs(second, () -> taxonomy.addRule(second, typeId, "KEYWORD", "NurZweiter", 0.5,
                false, person(second, true)));

        assertThat(RequestContext.runAs(first, () -> taxonomy.rulesOf(typeId, first)))
                .extracting(row -> row.get("pattern")).contains("NurErster").doesNotContain("NurZweiter");
    }

    @Test
    @DisplayName("a rule of another tenant is not reachable")
    void anotherTenantsRuleIsNotReachable() {
        UUID first = tenant();
        UUID second = tenant();
        UUID actor = person(first, true);
        UUID typeId = type(actor);
        TaxonomyAdminService.Rule rule = RequestContext.runAs(first, () -> taxonomy.addRule(first, typeId,
                "KEYWORD", "Privat", 0.5, false, actor));

        assertThatThrownBy(() -> RequestContext.runAs(second,
                () -> taxonomy.deleteRule(second, rule.id(), person(second, true))))
                .isInstanceOf(ApiException.class);
        assertThat(RequestContext.runAs(first, () -> jdbc.queryForObject(
                "select count(*) from document_type_rules where id = ?",
                Integer.class, rule.id()))).isEqualTo(1);
    }

    @Test
    @DisplayName("the categories and rule kinds offered are the ones the schema allows")
    void theOfferedValuesAreTheSchemaValues() {
        // A category the schema does not allow would be a choice that fails on save.
        assertThat(TaxonomyAdminService.CATEGORIES).containsExactlyInAnyOrder("FINANCE", "CONTRACTS",
                "GOVERNMENT", "HOUSING", "EMPLOYMENT", "FAMILY_EDUCATION_HEALTH", "LEGAL", "OTHER");
        assertThat(TaxonomyAdminService.RULE_TYPES).containsExactlyInAnyOrder("KEYWORD", "REGEX",
                "SENDER_DOMAIN", "FILENAME");
    }
}
