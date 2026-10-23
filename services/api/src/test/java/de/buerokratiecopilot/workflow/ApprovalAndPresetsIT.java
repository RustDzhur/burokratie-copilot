package de.buerokratiecopilot.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
import de.buerokratiecopilot.workflow.approval.ApprovalService;

/**
 * Verifies approvals and escalation (spec §32, F-003) and the preset processes (§16.2, F-005).
 *
 * <p>The acceptance criterion is that a level-4 action is impossible without the confirmation of an
 * authorised person. What makes that true is checked here: nobody approves their own request, a request
 * needs one decision, and an overdue request escalates instead of waiting forever.</p>
 *
 * <p>The amount-based authority matrix was part of the payment module and was removed with it (§4.3);
 * every request now needs one deciding person, checked against the membership the platform already knows.</p>
 */
@SpringBootTest
@ExtendWith(TenantContextExtension.class)
@ActiveProfiles("test")
class ApprovalAndPresetsIT {

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private WorkflowService workflows;

    @Autowired
    private WorkflowEngine engine;

    @Autowired
    private PresetProcesses presets;

    @Autowired
    private JdbcTemplate jdbc;

    /** A tenant with a requester, an approver and a third person. */
    private record Tenant(UUID tenantId, UUID requester, UUID approver, UUID outsider) {
    }

    private Tenant tenant() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Freigabe Test', 'COMPANY') returning id
                """, UUID.class, "ap-" + UUID.randomUUID().toString().substring(0, 12));
        RequestContext.setTenantId(tenantId);

        UUID requester = person(tenantId);
        UUID approver = person(tenantId);
        UUID outsider = person(tenantId);

        return new Tenant(tenantId, requester, approver, outsider);
    }

    private UUID person(UUID tenantId) {
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Person', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "ap-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
        jdbc.queryForObject("""
                insert into memberships (id, tenant_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, 'ACTIVE', false) returning id
                """, UUID.class, tenantId, userId);
        return userId;
    }

    private ApprovalService.Request request(Tenant tenant, Instant expiresAt) {
        return RequestContext.runAs(tenant.tenantId(), () -> approvals.request(tenant.tenantId(),
                "invoice", UUID.randomUUID(), "INVOICE_APPROVAL", "Rechnung prüfen", "Betrag prüfen",
                expiresAt, tenant.requester()));
    }

    // ---------------------------------------------------------------- F-003: authority

    @Test
    @DisplayName("nobody approves their own request")
    void nobodyApprovesTheirOwnRequest() {
        Tenant tenant = tenant();
        ApprovalService.Request request = request(tenant, null);

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(),
                () -> approvals.decide(tenant.tenantId(), request.approvalId(), tenant.requester(), true,
                        "Passt schon")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("eigene");
    }

    @Test
    @DisplayName("a single rejection ends the request")
    void oneRejectionEndsIt() {
        Tenant tenant = tenant();
        ApprovalService.Request request = request(tenant, null);

        ApprovalService.Decision decision = RequestContext.runAs(tenant.tenantId(),
                () -> approvals.decide(tenant.tenantId(), request.approvalId(), tenant.approver(), false,
                        "Betrag stimmt nicht"));

        assertThat(decision.status()).isEqualTo("REJECTED");
        assertThat(RequestContext.runAs(tenant.tenantId(),
                () -> approvals.pending(tenant.tenantId()))).isEmpty();
    }

    @Test
    @DisplayName("an overdue request escalates instead of blocking a process quietly")
    void overdueRequestsEscalate() {
        Tenant tenant = tenant();
        ApprovalService.Request request = request(tenant, Instant.now().minus(1, ChronoUnit.HOURS));

        List<UUID> escalated = RequestContext.runAs(tenant.tenantId(),
                () -> approvals.escalateOverdue(tenant.tenantId()));

        assertThat(escalated).contains(request.approvalId());
        assertThat(jdbc.queryForObject("select status from approvals where id = ?", String.class,
                request.approvalId())).isEqualTo("EXPIRED");
        assertThat(RequestContext.runAs(tenant.tenantId(),
                () -> approvals.history(tenant.tenantId(), request.approvalId())))
                .extracting(entry -> entry.get("event_type"))
                .as("silence is not agreement, and it has to end up somewhere visible")
                .contains("ESCALATED");
    }

    @Test
    @DisplayName("every decision is in the journal")
    void everyDecisionIsJournaled() {
        Tenant tenant = tenant();
        ApprovalService.Request request = request(tenant, null);
        RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(tenant.tenantId(),
                request.approvalId(), tenant.approver(), true, "Geprüft und in Ordnung"));

        var history = RequestContext.runAs(tenant.tenantId(),
                () -> approvals.history(tenant.tenantId(), request.approvalId()));

        assertThat(history).extracting(entry -> entry.get("event_type"))
                .contains("REQUESTED", "APPROVED");
        assertThat(history).anySatisfy(entry -> assertThat(String.valueOf(entry.get("comment")))
                .contains("in Ordnung"));
    }

    @Test
    @DisplayName("an approved request is not decided a second time")
    void aDecidedRequestIsClosed() {
        Tenant tenant = tenant();
        ApprovalService.Request request = request(tenant, null);
        RequestContext.runAs(tenant.tenantId(), () -> approvals.decide(tenant.tenantId(),
                request.approvalId(), tenant.approver(), true, null));

        assertThatThrownBy(() -> RequestContext.runAs(tenant.tenantId(),
                () -> approvals.decide(tenant.tenantId(), request.approvalId(), tenant.approver(), true,
                        null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nicht mehr offen");
    }

    @Test
    @DisplayName("an approval of another tenant is not reachable")
    void anotherTenantsApprovalIsNotReachable() {
        Tenant owner = tenant();
        Tenant stranger = tenant();
        ApprovalService.Request request = request(owner, null);

        assertThatThrownBy(() -> RequestContext.runAs(stranger.tenantId(),
                () -> approvals.decide(stranger.tenantId(), request.approvalId(), stranger.approver(), true,
                        null)))
                .isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- F-005: the presets

    @Test
    @DisplayName("each required process exists as a definition")
    void eachPresetExists() {
        assertThat(PresetProcesses.all()).extracting(PresetProcesses.Preset::code)
                .containsExactlyInAnyOrder("incoming_invoice", "authority_letter", "contract_review");
    }

    @Test
    @DisplayName("every preset is a valid definition, which is what publishing checks")
    void everyPresetValidates() {
        for (PresetProcesses.Preset preset : PresetProcesses.all()) {
            WorkflowDefinition.Validation validation =
                    WorkflowDefinition.validate(WorkflowDefinition.parse(preset.definition()));
            assertThat(validation.isValid())
                    .as("Preset '%s': %s", preset.code(), validation.summary())
                    .isTrue();
            assertThat(preset.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("the presets are installed once and published as versions")
    void presetsAreInstalledAndPublished() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Vorlagen', 'COMPANY') returning id
                """, UUID.class, "pre-" + UUID.randomUUID().toString().substring(0, 12));
        RequestContext.setTenantId(tenantId);
        UUID installer = person(tenantId);

        Map<String, Object> first = RequestContext.runAs(tenantId,
                () -> presets.install(tenantId, installer));
        Map<String, Object> second = RequestContext.runAs(tenantId,
                () -> presets.install(tenantId, installer));

        assertThat((List<?>) first.get("installed")).hasSize(3);
        assertThat((List<?>) second.get("alreadyPresent"))
                .as("an installation that ran twice must not publish a second version of a process the "
                        + "customer has since edited")
                .hasSize(3);
        assertThat((List<?>) second.get("installed")).isEmpty();
        assertThat(RequestContext.runAs(tenantId, () -> workflows.list(tenantId)))
                .hasSize(3)
                .allSatisfy(workflow -> assertThat(((Number) workflow.get("current_version")).intValue())
                        .isEqualTo(1));
    }

    @Test
    @DisplayName("an incoming invoice runs through the engine end to end")
    void theInvoiceProcessRuns() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Durchlauf', 'COMPANY') returning id
                """, UUID.class, "pre-" + UUID.randomUUID().toString().substring(0, 12));
        RequestContext.setTenantId(tenantId);
        UUID installer = person(tenantId);
        RequestContext.runAs(tenantId, () -> presets.install(tenantId, installer));

        UUID definitionId = RequestContext.runAs(tenantId, () -> workflows.list(tenantId)).stream()
                .filter(row -> "incoming_invoice".equals(row.get("code")))
                .map(row -> (UUID) row.get("id")).findFirst().orElseThrow();

        // Without approval: receive -> check -> needs_approval -> publish -> payment_task -> end.
        WorkflowEngine.Run run = RequestContext.runAs(tenantId, () -> engine.start(tenantId, definitionId,
                "MANUAL", Map.of("requiresApproval", "false"), "invoice", null, null, "preset-run", null));
        for (int step = 0; step < 6; step++) {
            RequestContext.runAs(tenantId, () -> engine.advance(tenantId, run.runId()));
        }

        assertThat(jdbc.queryForObject("select status from workflow_runs where id = ?", String.class,
                run.runId()))
                .as("der vorgegebene Prozess läuft mit der Maschine, die jeder andere Prozess benutzt")
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("the invoice process stops for an approval when the amount needs one")
    void theInvoiceProcessStopsForAnApproval() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Halt', 'COMPANY') returning id
                """, UUID.class, "pre-" + UUID.randomUUID().toString().substring(0, 12));
        RequestContext.setTenantId(tenantId);
        UUID installer = person(tenantId);
        RequestContext.runAs(tenantId, () -> presets.install(tenantId, installer));

        UUID definitionId = RequestContext.runAs(tenantId, () -> workflows.list(tenantId)).stream()
                .filter(row -> "incoming_invoice".equals(row.get("code")))
                .map(row -> (UUID) row.get("id")).findFirst().orElseThrow();

        WorkflowEngine.Run run = RequestContext.runAs(tenantId, () -> engine.start(tenantId, definitionId,
                "MANUAL", Map.of("requiresApproval", "true"), "invoice", null, null, "preset-approval",
                null));
        // receive -> check -> needs_approval -> approve, and only then does it wait for a person.
        for (int step = 0; step < 4; step++) {
            RequestContext.runAs(tenantId, () -> engine.advance(tenantId, run.runId()));
        }

        assertThat(jdbc.queryForObject("select status from workflow_runs where id = ?", String.class,
                run.runId()))
                .as("the process stops where a person has to decide, rather than deciding for them")
                .isEqualTo("WAITING_APPROVAL");
    }

    @Test
    @DisplayName("every preset is reachable by its code")
    void presetsAreReachableByCode() {
        assertThat(PresetProcesses.byCode("contract_review").name()).isNotBlank();
        assertThatThrownBy(() -> PresetProcesses.byCode("gibt-es-nicht"))
                .isInstanceOf(ApiException.class);
    }
}
