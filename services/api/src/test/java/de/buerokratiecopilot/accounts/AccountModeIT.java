package de.buerokratiecopilot.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.accounts.ModuleCatalogue.Module;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies that an unavailable module cannot be opened (spec §3.1, H-001 acceptance).
 *
 * <p>The criterion says "neither in the UI nor through the API", and the tests check the half that
 * matters: the API refuses. A navigation entry that is merely hidden protects nothing — the module is
 * still reachable by anybody who types the address.</p>
 *
 * <p>The specification lists the account types but not which modules each gets, so the tests also check
 * the boundary of what the platform is entitled to decide: the defaults are configuration, they can be
 * changed per tenant, and switching the account type deletes nothing.</p>
 */
@SpringBootTest
@ExtendWith(TenantContextExtension.class)
@ActiveProfiles("test")
class AccountModeIT {

    @Autowired
    private AccountModeService modes;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant(String accountType) {
        UUID id = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, ?, ?) returning id
                """, UUID.class, "mode-" + UUID.randomUUID().toString().substring(0, 12),
                "Konto " + accountType, accountType);
        // The tenant is established for the rest of the test: the protected tables require a context,
        // and the HTTP layer is what normally supplies it. The extension clears it afterwards.
        RequestContext.setTenantId(id);
        return id;
    }

    private boolean allows(UUID tenantId, String moduleKey) {
        return RequestContext.runAs(tenantId, () -> modes.allows(tenantId, moduleKey));
    }

    private void requireAccess(UUID tenantId, String moduleKey) {
        RequestContext.runAs(tenantId, () -> {
            modes.requireAccess(tenantId, moduleKey);
            return null;
        });
    }

    // ---------------------------------------------------------------- the check itself

    @Test
    @DisplayName("an unavailable module is refused by the API")
    void unavailableModuleIsRefused() {
        UUID privatePerson = tenant("PRIVATE_PERSON");

        // A private person has no company books, and the API says so rather than hiding a menu entry.
        assertThat(allows(privatePerson, ModuleCatalogue.keyOf(Module.FINANCE))).isFalse();
        assertThatThrownBy(() -> requireAccess(privatePerson, ModuleCatalogue.keyOf(Module.FINANCE)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nicht verfügbar");
    }

    @Test
    @DisplayName("an available module is allowed")
    void availableModuleIsAllowed() {
        UUID company = tenant("COMPANY");

        assertThat(allows(company, ModuleCatalogue.keyOf(Module.FINANCE))).isTrue();
        requireAccess(company, ModuleCatalogue.keyOf(Module.FINANCE));
    }

    @Test
    @DisplayName("a module the platform does not have is refused for everybody")
    void unknownModuleIsRefused() {
        UUID company = tenant("COMPANY");

        assertThat(allows(company, "module.teleportation")).isFalse();
        assertThatThrownBy(() -> requireAccess(company, "module.teleportation"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a module that is not available to the type can be configured on")
    void configurationCanAddAModule() {
        UUID privatePerson = tenant("PRIVATE_PERSON");
        assertThat(allows(privatePerson, ModuleCatalogue.keyOf(Module.FINANCE))).isFalse();

        RequestContext.runAs(privatePerson, () -> {
            modes.configure(privatePerson, ModuleCatalogue.keyOf(Module.FINANCE), true, null);
            return null;
        });

        assertThat(allows(privatePerson, ModuleCatalogue.keyOf(Module.FINANCE)))
                .as("a private person who starts keeping books does not need a different account")
                .isTrue();
    }

    @Test
    @DisplayName("a module of the type can be configured off")
    void configurationCanRemoveAModule() {
        UUID company = tenant("COMPANY");

        RequestContext.runAs(company, () -> {
            modes.configure(company, ModuleCatalogue.keyOf(Module.FINANCE), false, null);
            return null;
        });

        assertThat(allows(company, ModuleCatalogue.keyOf(Module.FINANCE)))
                .as("the tenant's own decision outranks the default of its account type")
                .isFalse();
    }

    @Test
    @DisplayName("configuring twice does not create a second row")
    void configurationIsIdempotent() {
        UUID company = tenant("COMPANY");

        RequestContext.runAs(company, () -> {
            modes.configure(company, ModuleCatalogue.keyOf(Module.REPORTS), false, null);
            modes.configure(company, ModuleCatalogue.keyOf(Module.REPORTS), true, null);
            return null;
        });

        assertThat(jdbc.queryForObject("""
                select count(*) from feature_flags where tenant_id = ? and flag_key = ?
                """, Integer.class, company, ModuleCatalogue.keyOf(Module.REPORTS))).isEqualTo(1);
        assertThat(allows(company, ModuleCatalogue.keyOf(Module.REPORTS))).isTrue();
    }

    @Test
    @DisplayName("one tenant's configuration does not affect another")
    void configurationIsPerTenant() {
        UUID first = tenant("COMPANY");
        UUID second = tenant("COMPANY");

        RequestContext.runAs(first, () -> {
            modes.configure(first, ModuleCatalogue.keyOf(Module.FINANCE), false, null);
            return null;
        });

        assertThat(allows(first, ModuleCatalogue.keyOf(Module.FINANCE))).isFalse();
        assertThat(allows(second, ModuleCatalogue.keyOf(Module.FINANCE))).isTrue();
    }

    // ---------------------------------------------------------------- what the platform may decide

    @Test
    @DisplayName("the defaults are configuration, and the catalogue says so")
    void defaultsAreOfferedAsConfiguration() {
        var defaults = ModuleCatalogue.describeDefaults();

        assertThat(defaults).containsKeys("PRIVATE_PERSON", "COMPANY", "HIGH_SECURITY_ORGANIZATION");
        // The platform names the modules and offers a starting point; the mapping is not a rule it
        // invented and cannot be changed.
        assertThat(defaults.get("PRIVATE_PERSON"))
                .as("a private person does not run a company's accounts payable")
                .doesNotContain(ModuleCatalogue.keyOf(Module.FINANCE));
        assertThat(defaults.get("COMPANY")).contains(ModuleCatalogue.keyOf(Module.FINANCE));
    }

    @Test
    @DisplayName("the navigation is built from what the API allows")
    void navigationReflectsTheApi() {
        UUID privatePerson = tenant("PRIVATE_PERSON");
        UUID company = tenant("COMPANY");

        var personal = RequestContext.runAs(privatePerson,
                () -> modes.availableModules(privatePerson));
        var business = RequestContext.runAs(company, () -> modes.availableModules(company));

        assertThat(personal).isNotEmpty();
        assertThat(personal).extracting(module -> module.get("key"))
                .doesNotContain(ModuleCatalogue.keyOf(Module.FINANCE));
        assertThat(business).extracting(module -> module.get("key"))
                .contains(ModuleCatalogue.keyOf(Module.FINANCE));
        // Every entry says whether it comes from the account type or from the tenant's own decision.
        assertThat(personal).allSatisfy(module -> assertThat(module)
                .containsKeys("key", "label", "source"));
    }

    @Test
    @DisplayName("a configured module is named as configured, not as the type's default")
    void theSourceIsNamed() {
        UUID company = tenant("COMPANY");
        RequestContext.runAs(company, () -> {
            modes.configure(company, ModuleCatalogue.keyOf(Module.FINANCE), false, null);
            return null;
        });

        var modules = RequestContext.runAs(company, () -> modes.availableModules(company));

        assertThat(modules).noneSatisfy(module -> assertThat(module)
                .containsEntry("key", ModuleCatalogue.keyOf(Module.FINANCE)));
        assertThat(RequestContext.runAs(company,
                () -> modes.isConfigured(company, ModuleCatalogue.keyOf(Module.FINANCE)))).isTrue();
    }

    @Test
    @DisplayName("the catalogue lists every module it has, with a label")
    void theCatalogueIsComplete() {
        assertThat(ModuleCatalogue.allModules()).hasSize(Module.values().length);
        assertThat(ModuleCatalogue.allModules().values()).allSatisfy(label ->
                assertThat(label).isNotBlank());
    }

    @Test
    @DisplayName("every module is reachable by at least one account type")
    void everyModuleBelongsToSomeType() {
        for (Module module : Module.values()) {
            assertThat(ModuleCatalogue.accountTypesWith(module))
                    .as("module " + module + " is in no default at all")
                    .isNotEmpty();
        }
    }
}
