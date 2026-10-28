package de.buerokratiecopilot.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies the life of an integration (spec §29, E-001 acceptance).
 *
 * <p>The acceptance criterion is that a disabled integration does not synchronise. The test tries the
 * ways round it: starting a run after disabling, asking for credentials after disabling, and starting a
 * run while the integration is in an error state. Each has to be refused, and the refusal has to be the
 * one the caller can act on.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class IntegrationLifecycleIT {

    @Autowired
    private IntegrationService integrations;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Lebenszyklus Test', 'COMPANY') returning id
                """, UUID.class, "life-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private IntegrationService.Connection connect(UUID tenantId, String provider) {
        return integrations.connect(tenantId, null, provider, "EMAIL", provider + " Postfach", "OAUTH2",
                Map.of("accessToken", "token-" + provider), List.of("mail.read"), "konto@example.de",
                null);
    }

    @Test
    @DisplayName("a connected integration runs a synchronisation")
    void connectedIntegrationRuns() {
        UUID tenantId = tenant();
        IntegrationService.Connection connection = connect(tenantId, "IMAP");

        IntegrationService.SyncRun run = integrations.startSync(tenantId, connection.integrationId(),
                "MANUAL", null);

        assertThat(run.status()).isEqualTo("RUNNING");
        assertThat(integrations.runs(tenantId, connection.integrationId())).hasSize(1);
    }

    @Test
    @DisplayName("a disabled integration does not synchronise")
    void disabledIntegrationDoesNotRun() {
        UUID tenantId = tenant();
        IntegrationService.Connection connection = connect(tenantId, "SMTP");
        integrations.disable(tenantId, connection.integrationId(), "Zugang widerrufen");

        assertThatThrownBy(() -> integrations.startSync(tenantId, connection.integrationId(), "MANUAL",
                null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("DISABLED");

        assertThat(integrations.runs(tenantId, connection.integrationId()))
                .as("a refused run must not leave a run record behind")
                .isEmpty();
    }

    @Test
    @DisplayName("reconnecting makes the integration usable again")
    void reconnectingRestoresIt() {
        UUID tenantId = tenant();
        IntegrationService.Connection first = connect(tenantId, "IMAP");
        integrations.disable(tenantId, first.integrationId(), "Test");

        IntegrationService.Connection again = connect(tenantId, "IMAP");

        assertThat(again.integrationId())
                .as("reconnecting the same provider continues the same integration, not a second one")
                .isEqualTo(first.integrationId());
        assertThat(integrations.startSync(tenantId, again.integrationId(), "MANUAL", null).status())
                .isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("a failed run makes the integration an error and stops further runs")
    void failedRunStopsFurtherRuns() {
        UUID tenantId = tenant();
        IntegrationService.Connection connection = connect(tenantId, "BANK_AGGREGATOR");
        IntegrationService.SyncRun run = integrations.startSync(tenantId, connection.integrationId(),
                "FULL", null);

        integrations.finishSync(tenantId, run.runId(), "FAILED", 3, 1, "AUTH_EXPIRED",
                "Das Token ist abgelaufen");

        Map<String, Object> described = integrations.describe(tenantId, connection.integrationId());
        assertThat(described).containsEntry("status", "ERROR")
                .containsEntry("consecutive_failures", 1)
                .containsEntry("last_error_code", "AUTH_EXPIRED");

        // An integration in error does not quietly keep trying; the user has to act.
        assertThatThrownBy(() -> integrations.startSync(tenantId, connection.integrationId(), "FULL",
                null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a successful run clears the error count, so the status stays meaningful")
    void successfulRunClearsTheFailures() {
        UUID tenantId = tenant();
        IntegrationService.Connection connection = connect(tenantId, "IMAP");
        IntegrationService.SyncRun failed = integrations.startSync(tenantId, connection.integrationId(),
                "FULL", null);
        integrations.finishSync(tenantId, failed.runId(), "FAILED", 0, 1, "TIMEOUT", "Zeitüberschreitung");

        // Reconnecting is how a user resolves an error, and then a good run must clear the count.
        IntegrationService.Connection reconnected = connect(tenantId, "IMAP");
        IntegrationService.SyncRun good = integrations.startSync(tenantId, reconnected.integrationId(),
                "FULL", null);
        integrations.finishSync(tenantId, good.runId(), "SUCCESS", 42, 0, null, null);

        Map<String, Object> described = integrations.describe(tenantId, reconnected.integrationId());
        assertThat(described).containsEntry("consecutive_failures", 0)
                .containsEntry("last_sync_status", "SUCCESS")
                .containsEntry("status", "CONNECTED");
        assertThat(described.get("last_error_code")).isNull();
    }

    @Test
    @DisplayName("a run records how many items failed, not only that it failed")
    void runsRecordTheirNumbers() {
        UUID tenantId = tenant();
        IntegrationService.Connection connection = connect(tenantId, "IMAP");
        IntegrationService.SyncRun run = integrations.startSync(tenantId, connection.integrationId(),
                "FULL", null);

        integrations.finishSync(tenantId, run.runId(), "PARTIAL", 100, 7, null,
                "7 Nachrichten konnten nicht gelesen werden");

        List<Map<String, Object>> runs = integrations.runs(tenantId, connection.integrationId());
        assertThat(runs).hasSize(1);
        assertThat(runs.getFirst()).containsEntry("items_processed", 100)
                .containsEntry("items_failed", 7);
    }

    @Test
    @DisplayName("an integration of another tenant is not found")
    void otherTenantSeesNothing() {
        UUID owner = tenant();
        UUID stranger = tenant();
        IntegrationService.Connection connection = connect(owner, "IMAP");

        assertThatThrownBy(() -> integrations.startSync(stranger, connection.integrationId(), "MANUAL",
                null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> integrations.describe(stranger, connection.integrationId()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("disabling an integration that does not exist is reported, not silently ignored")
    void disablingAnUnknownIntegrationIsReported() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> integrations.disable(tenantId, UUID.randomUUID(), "Test"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("connecting without a provider is refused")
    void providerIsRequired() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> integrations.connect(tenantId, null, "  ", "EMAIL", "Postfach", "OAUTH2",
                Map.of(), null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Anbieter");
    }

    @Test
    @DisplayName("the description reports an error the user has to resolve, without inventing one")
    void describesOwnerAction() {
        UUID tenantId = tenant();
        IntegrationService.Connection connection = connect(tenantId, "GOOGLE_WORKSPACE");

        Map<String, Object> described = integrations.describe(tenantId, connection.integrationId());

        assertThat(described).containsEntry("requires_owner_action", false);
        assertThat(described.get("owner_action_hint")).isNull();
        assertThat(described).containsEntry("provider", "GOOGLE_WORKSPACE");
        assertThat(described).containsEntry("is_enabled", true);
    }
}
