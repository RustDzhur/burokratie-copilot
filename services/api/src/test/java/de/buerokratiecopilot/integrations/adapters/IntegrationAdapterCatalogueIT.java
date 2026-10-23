package de.buerokratiecopilot.integrations.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Verifies the adapter catalogue (spec §19, G-005).
 *
 * <p>The value of this class is that it tells the truth about what exists. Two checks carry that: every
 * provider it names is a provider the schema allows — no name is invented — and every adapter the
 * specification lists is marked either implemented or not, so that a customer reading the list can tell
 * which of them will work on Monday.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class IntegrationAdapterCatalogueIT {

    /**
     * Provider names the database CHECK still allows because an applied migration is history, but the
     * banking integrations behind them were removed with that module (Kanzlei edition, §4.3). The
     * catalogue must not offer them; the schema keeps accepting the names for old rows.
     */
    private static final Set<String> RETIRED_PROVIDERS = Set.of("BANK_FINTS", "BANK_AGGREGATOR");

    @Autowired
    private IntegrationAdapterCatalogue catalogue;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant(String accountType) {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Anbindung Test', ?) returning id
                """, UUID.class, "ia-" + UUID.randomUUID().toString().substring(0, 12), accountType);
    }

    private UUID person(UUID tenantId) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Person', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "ia-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
        jdbc.update("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', true)
                """, tenantId, userId);
        return userId;
    }

    /** The providers the schema itself allows, read from the database rather than typed here. */
    private List<String> providersTheSchemaAllows() {
        String definition = jdbc.queryForObject("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'integrations'::regclass and contype = 'c'
                  and pg_get_constraintdef(oid) like '%MICROSOFT365%'
                """, String.class);
        return java.util.regex.Pattern.compile("'([A-Z0-9_]+)'").matcher(definition).results()
                .map(match -> match.group(1)).distinct().toList();
    }

    @Test
    @DisplayName("no provider is invented: every name is one the schema allows")
    void noProviderIsInvented() {
        List<String> known = providersTheSchemaAllows();

        assertThat(IntegrationAdapterCatalogue.all()).extracting(IntegrationAdapterCatalogue.Adapter::provider)
                .as("a provider name the database refuses is a name no customer could ever use")
                .allSatisfy(provider -> assertThat(known).contains(provider));
    }

    @Test
    @DisplayName("every adapter the schema knows is described here")
    void everyKnownProviderIsDescribed() {
        List<String> described = IntegrationAdapterCatalogue.all().stream()
                .map(IntegrationAdapterCatalogue.Adapter::provider).toList();

        assertThat(described).containsAll(providersTheSchemaAllows().stream()
                .filter(provider -> !RETIRED_PROVIDERS.contains(provider))
                .toList());
    }

    @Test
    @DisplayName("every adapter says what it needs from the owner")
    void everyAdapterNamesWhatTheOwnerMustDo() {
        assertThat(IntegrationAdapterCatalogue.all())
                .as("an integration fails in most cases because nobody said who creates the application")
                .allSatisfy(adapter -> assertThat(adapter.ownerRequirements()).isNotEmpty());
        assertThat(IntegrationAdapterCatalogue.all())
                .allSatisfy(adapter -> assertThat(adapter.displayName()).isNotBlank());
    }

    @Test
    @DisplayName("an implemented adapter lists what it can do")
    void implementedAdaptersListCapabilities() {
        assertThat(IntegrationAdapterCatalogue.implemented()).isNotEmpty();
        assertThat(IntegrationAdapterCatalogue.implemented())
                .allSatisfy(adapter -> assertThat(adapter.capabilities()).isNotEmpty());
    }

    @Test
    @DisplayName("an adapter that is not implemented says so rather than looking unconfigured")
    void notImplementedAdaptersSaySo() {
        assertThat(IntegrationAdapterCatalogue.notImplemented())
                .as("'not set up yet' and 'does not exist' lead a person to do completely different things")
                .isNotEmpty();
        assertThat(IntegrationAdapterCatalogue.notImplemented())
                .allSatisfy(adapter -> assertThat(adapter.note()).contains("Nicht enthalten"));
    }

    @Test
    @DisplayName("what is really implemented is claimed as implemented")
    void whatIsRealIsClaimed() {
        assertThat(IntegrationAdapterCatalogue.implemented()).extracting(
                        IntegrationAdapterCatalogue.Adapter::provider)
                .as("these are the ones this repository actually contains")
                .contains("IMAP", "SMTP", "OCR_TESSERACT", "AI_OPENAI",
                        "E_INVOICING_PROVIDER", "SCANNER_BRIDGE", "WEBHOOK");
    }

    @Test
    @DisplayName("the OAuth mail adapters are implemented but say that no live provider run was made")
    void oauthAdaptersCarryTheirOwnHonestStatus() {
        assertThat(IntegrationAdapterCatalogue.implemented()).extracting(
                        IntegrationAdapterCatalogue.Adapter::provider)
                .as("the OAuth flow and the XOAUTH2 ingest exist in this repository")
                .contains("MICROSOFT365", "GOOGLE_WORKSPACE");

        for (String provider : List.of("MICROSOFT365", "GOOGLE_WORKSPACE")) {
            IntegrationAdapterCatalogue.Adapter adapter = IntegrationAdapterCatalogue.byProvider(provider);
            assertThat(adapter.implemented()).isTrue();
            assertThat(adapter.requiresAppRegistration())
                    .as("nobody inside the software can register the application at Microsoft or Google")
                    .isTrue();
            assertThat(adapter.implementationStatus())
                    .isEqualTo(IntegrationAdapterCatalogue.Adapter.NEEDS_APP_REGISTRATION);
            assertThat(adapter.note())
                    .as("the note must not turn 'tested against a mock' into 'works with the provider'")
                    .contains("nicht ausgeführt");
            assertThat(adapter.capabilities()).isNotEmpty();

            Map<String, Object> requirements = IntegrationAdapterCatalogue.requirementsOf(provider);
            assertThat(requirements).containsEntry("requiresAppRegistration", true);
            assertThat(String.valueOf(requirements.get("conclusion")))
                    .contains("noch nicht gegen den Anbieter ausgeführt");
        }
    }

    @Test
    @DisplayName("the ones this repository does not contain are not claimed")
    void whatIsAbsentIsNotClaimed() {
        assertThat(IntegrationAdapterCatalogue.notImplemented()).extracting(
                        IntegrationAdapterCatalogue.Adapter::provider)
                .as("no accounting hand-over and no signature exist here")
                .contains("ACCOUNTING_DATEV", "E_SIGNATURE_DOCUSIGN", "CRM_HUBSPOT");
    }

    @Test
    @DisplayName("asking for something that does not exist is recorded as not supported")
    void askingForTheAbsentIsRecordedHonestly() {
        UUID tenantId = tenant("COMPANY");
        UUID userId = person(tenantId);

        Map<String, Object> result = RequestContext.runAs(tenantId, () -> catalogue.declareUnsupported(
                tenantId, null, "ACCOUNTING_DATEV", userId));

        assertThat(result).containsEntry("status", "NOT_SUPPORTED");
        assertThat(jdbc.queryForObject("""
                select status from integrations where tenant_id = ? and provider = 'ACCOUNTING_DATEV'
                """, String.class, tenantId))
                .as("'not configured' would send somebody looking for a setting that does not exist")
                .isEqualTo("NOT_SUPPORTED");
        assertThat(jdbc.queryForObject("""
                select owner_action_hint from integrations where tenant_id = ? and provider = 'ACCOUNTING_DATEV'
                """, String.class, tenantId)).contains("Beraternummer");
    }

    @Test
    @DisplayName("an adapter that exists is not declared unsupported")
    void anExistingAdapterIsNotDeclaredUnsupported() {
        UUID tenantId = tenant("COMPANY");
        UUID userId = person(tenantId);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> catalogue.declareUnsupported(
                tenantId, null, "SMTP", userId)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("vorhanden");
    }

    @Test
    @DisplayName("declaring the same absent adapter twice does not create a second row")
    void declaringTwiceIsIdempotent() {
        UUID tenantId = tenant("COMPANY");
        UUID userId = person(tenantId);

        RequestContext.runAs(tenantId, () -> catalogue.declareUnsupported(tenantId, null, "CRM_HUBSPOT",
                userId));
        RequestContext.runAs(tenantId, () -> catalogue.declareUnsupported(tenantId, null, "CRM_HUBSPOT",
                userId));

        assertThat(jdbc.queryForObject("""
                select count(*) from integrations where tenant_id = ? and provider = 'CRM_HUBSPOT'
                """, Integer.class, tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown provider is refused rather than stored as a wish")
    void anUnknownProviderIsRefused() {
        UUID tenantId = tenant("COMPANY");
        UUID userId = person(tenantId);

        assertThatThrownBy(() -> RequestContext.runAs(tenantId, () -> catalogue.declareUnsupported(
                tenantId, null, "TELEPATHY", userId)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the requirements of an absent adapter say what the conclusion is")
    void requirementsOfAnAbsentAdapterAreHonest() {
        Map<String, Object> requirements = IntegrationAdapterCatalogue.requirementsOf("ACCOUNTING_DATEV");

        assertThat(requirements).containsEntry("implemented", false);
        assertThat(String.valueOf(requirements.get("conclusion")))
                .contains("nicht enthalten");
        assertThat((List<?>) requirements.get("steps")).isNotEmpty();
    }

    @Test
    @DisplayName("the requirements of a present adapter say it is present")
    void requirementsOfAPresentAdapterAreHonest() {
        Map<String, Object> requirements = IntegrationAdapterCatalogue.requirementsOf("IMAP");

        assertThat(requirements).containsEntry("implemented", true);
        assertThat(requirements).doesNotContainKey("conclusion");
        assertThat((List<?>) requirements.get("steps"))
                .as("the steps are the answer to the question that gets asked")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the catalogue is grouped by category and counted")
    void theCatalogueIsCounted() {
        Map<String, Object> catalogueView = catalogue.catalogue();

        assertThat(catalogueView).containsKeys("EMAIL", "AI", "E_INVOICING", "implementedCount",
                "notImplementedCount");
        assertThat((Integer) catalogueView.get("implementedCount"))
                .isEqualTo(IntegrationAdapterCatalogue.implemented().size());
    }

    @Test
    @DisplayName("what a tenant has connected is listed with its honest status")
    void connectionsAreListed() {
        UUID tenantId = tenant("COMPANY");
        UUID userId = person(tenantId);
        RequestContext.runAs(tenantId, () -> catalogue.declareUnsupported(tenantId, null, "AI_OLLAMA",
                userId));

        var connections = RequestContext.runAs(tenantId, () -> catalogue.connections(tenantId));

        assertThat(connections).hasSize(1);
        assertThat(connections.getFirst()).containsEntry("status", "NOT_SUPPORTED");
    }

    @Test
    @DisplayName("the same provider can be declared by two tenants")
    void twoTenantsCanEachDeclareIt() {
        UUID first = tenant("COMPANY");
        UUID second = tenant("COMPANY");

        RequestContext.runAs(first, () -> catalogue.declareUnsupported(first, null, "CLOUD_STORAGE_DRIVE",
                person(first)));
        RequestContext.runAs(second, () -> catalogue.declareUnsupported(second, null, "CLOUD_STORAGE_DRIVE",
                person(second)));

        assertThat(RequestContext.runAs(first, () -> catalogue.connections(first))).hasSize(1);
        assertThat(RequestContext.runAs(second, () -> catalogue.connections(second))).hasSize(1);
    }
}
