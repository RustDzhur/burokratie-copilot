package de.buerokratiecopilot.agents.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.devices.DeviceService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * {@code doc-scan} against the real database: an active device receives a queued job exactly once,
 * a non-active or unknown device is refused, and a folder that does not exist is reported.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocScanAgentIT {

    @Autowired
    private DocScanAgent agent;

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private DeviceService devices;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID seedActiveDevice(UUID tenantId, UUID userId) {
        DeviceService.Registered registered = RequestContext.runAs(tenantId, () -> devices.register(
                tenantId, "scanner-1", "Scanner EG", "SCANNER", null, userId));
        RequestContext.runAs(tenantId, () -> devices.activate(tenantId, registered.deviceId(), userId));
        return registered.deviceId();
    }

    @Test
    @DisplayName("an active device receives a queued SCAN job, and the same order is not queued twice")
    void queuesJobIdempotently() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID userId = RequestContext.runAs(tenantId, () -> jdbc.queryForObject(
                "select user_id from memberships where tenant_id = ? limit 1", UUID.class, tenantId));
        UUID deviceId = seedActiveDevice(tenantId, userId);

        AgentContext context = new AgentContext(tenantId, userId, "manual", null, Map.of(
                "deviceId", deviceId.toString(), "sourceKey", "scan-batch-1", "label", "Posteingang"));
        AgentResult result = agent.run(context);

        assertThat(result.ok()).isTrue();
        assertThat(result.meta().get("jobType")).isEqualTo("SCAN");
        assertThat(result.produced()).anySatisfy(produced ->
                assertThat(produced.kind()).isEqualTo("scan-job"));
        assertThat(queuedJobs(tenantId, deviceId)).isEqualTo(1);

        // Idempotence: the same sourceKey does not queue a second job.
        AgentResult second = agent.run(context);
        assertThat(second.ok()).isFalse();
        assertThat(second.summary()).contains("bereits");
        assertThat(queuedJobs(tenantId, deviceId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a device that is not active receives no work, with the reason")
    void pendingDeviceIsRefused() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        UUID userId = RequestContext.runAs(tenantId, () -> jdbc.queryForObject(
                "select user_id from memberships where tenant_id = ? limit 1", UUID.class, tenantId));
        UUID deviceId = RequestContext.runAs(tenantId, () -> devices.register(tenantId,
                "scanner-2", "Scanner OG", "SCANNER", null, userId).deviceId());

        AgentResult result = agent.run(new AgentContext(tenantId, userId, "manual", null,
                Map.of("deviceId", deviceId.toString(), "sourceKey", "scan-batch-2")));

        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("erhält keine Aufträge");
        assertThat(queuedJobs(tenantId, deviceId)).isZero();
    }

    @Test
    @DisplayName("an unknown device is reported")
    void unknownDeviceFails() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("deviceId", UUID.randomUUID().toString(), "sourceKey", "scan-x")));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("nicht gefunden");
    }

    @Test
    @DisplayName("a folder that does not exist is reported, not pretended to be imported")
    void missingFolderFails() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null,
                Map.of("folderPath", "/tmp/does-not-exist-" + UUID.randomUUID(), "sourceKey", "f-1")));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("konnte nicht eingelesen werden");
    }

    @Test
    @DisplayName("without a device or a folder there is nothing to accept")
    void nothingGivenIsSkipped() {
        UUID tenantId = DocumentsAgentItSupport.seedTenantWithUser(factory);
        AgentResult result = agent.run(new AgentContext(tenantId, null, "manual", null, Map.of()));
        assertThat(result.ok()).isFalse();
        assertThat(result.summary()).contains("Weder deviceId noch folderPath");
    }

    private long queuedJobs(UUID tenantId, UUID deviceId) {
        Long count = RequestContext.runAs(tenantId, () -> jdbc.queryForObject("""
                select count(*) from device_jobs
                where device_id = ? and status in ('QUEUED', 'CLAIMED', 'RUNNING')
                """, Long.class, deviceId));
        return count == null ? 0 : count;
    }
}
