package de.buerokratiecopilot.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The Desktop Bridge can collect work and return a scan (spec §8.2).
 *
 * <p><strong>What this covers, and why it is a whole chain.</strong> {@code DeviceService.authenticate} and
 * {@code claimNext} were written and tested with no endpoint at all: a scanner could be registered and work
 * could be queued for it, and the scanner had no way to ask for that work. A service test could not see that,
 * because every part passed on its own. This walks the chain the way the workstation program does — key,
 * claim, upload — so a break anywhere in it fails here.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceAgentIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeviceService devices;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Brücke Test', 'COMPANY') returning id
                """, UUID.class, "brg-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Administrator', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "brg-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    private DeviceService.Registered bridge(UUID tenantId) {
        UUID who = person();
        DeviceService.Registered registered = RequestContext.runAs(tenantId, () -> devices.register(tenantId,
                "bridge-" + UUID.randomUUID().toString().substring(0, 8), "Brücke am Empfang", "DESKTOP_BRIDGE", "PULL", who));
        devices.activate(tenantId, registered.deviceId(), who);
        return registered;
    }

    /** A queued scan job, as the Devices screen would create it. */
    private UUID queuedJob(UUID tenantId, UUID deviceId) {
        UUID who = person();
        // Inside the tenant context, because device_jobs is protected by row level security: a row written
        // outside it is invisible to the very poll that is meant to find it.
        return RequestContext.runAs(tenantId, () -> jdbc.queryForObject("""
                insert into device_jobs (id, device_id, tenant_id, job_type, status, parameters, priority,
                                         attempt, max_attempts, created_by)
                values (gen_random_uuid(), ?, ?, 'SCAN', 'QUEUED', '{}'::jsonb, 100, 0, 3, ?)
                returning id
                """, UUID.class, deviceId, tenantId, who));
    }

    @Test
    @DisplayName("the bridge claims a queued job and returns the scan as a document")
    void theBridgeCollectsWorkAndReturnsAScan() throws Exception {
        UUID tenantId = tenant();
        DeviceService.Registered registered = bridge(tenantId);
        UUID jobId = queuedJob(tenantId, registered.deviceId());

        // 1. Who am I: the key is what the workstation presents on every call.
        mockMvc.perform(get("/api/v1/device-agent/me").header("X-Device-Key", registered.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(registered.deviceId().toString()));
        // 2. Claim: the half that was missing entirely.
        String claimBody = mockMvc.perform(post("/api/v1/device-agent/claim")
                        .header("X-Device-Key", registered.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(claimBody).contains(jobId.toString());

        // 3. A second claim finds nothing, and says so without an error: a poll that finds no work is normal.
        mockMvc.perform(post("/api/v1/device-agent/claim").header("X-Device-Key", registered.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").doesNotExist());

        // 4. The scan goes back as a document, through the same intake as an upload.
        // Unique content: two sheets from the same scanner are not copies, and the platform's duplicate
        // detection would otherwise refuse the second run of this test as a re-upload.
        MockMultipartFile scan = new MockMultipartFile("file", "scan.pdf", "application/pdf",
                ("%PDF-1.7 Brücke " + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/device-agent/jobs/" + jobId + "/result")
                        .file(scan)
                        .header("X-Device-Key", registered.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.documentId").exists());

        // 5. The job is finished and points at the document it produced.
        String jobStatus = jdbc.queryForObject("select status from device_jobs where id = ?", String.class, jobId);
        UUID documentId = jdbc.queryForObject("select document_id from device_jobs where id = ?", UUID.class, jobId);
        assertThat(jobStatus).isEqualTo("COMPLETED");
        assertThat(documentId).isNotNull();
        assertThat(RequestContext.runAs(tenantId, () -> jdbc.queryForObject(
                "select original_filename from documents where id = ?", String.class, documentId)))
                .isEqualTo("scan.pdf");
    }

    @Test
    @DisplayName("the bridge reports why it could not scan, and the job comes back for another try")
    void aFailedScanIsRecordedWithItsReason() throws Exception {
        UUID tenantId = tenant();
        DeviceService.Registered registered = bridge(tenantId);
        UUID jobId = queuedJob(tenantId, registered.deviceId());

        mockMvc.perform(post("/api/v1/device-agent/claim").header("X-Device-Key", registered.apiKey()))
                .andExpect(status().isOk());

        // No paper is worth another attempt later; a person walking past the machine may put a sheet in.
        mockMvc.perform(multipart("/api/v1/device-agent/jobs/" + jobId + "/result")
                        .param("errorCode", "NO_PAPER")
                        .param("errorMessage", "Der Einzug war leer")
                        .header("X-Device-Key", registered.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        assertThat(jdbc.queryForObject("select error_code from device_jobs where id = ?", String.class, jobId))
                .isEqualTo("NO_PAPER");
        // And it is claimable again, which is what "retryable" has to mean in practice.
        mockMvc.perform(post("/api/v1/device-agent/claim").header("X-Device-Key", registered.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));
    }

    @Test
    @DisplayName("an unknown device key is refused, and says so")
    void anUnknownKeyIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/device-agent/me").header("X-Device-Key", "not-a-real-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/device-agent/claim"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a device cannot collect another account's job")
    void aDeviceCannotReachAnotherAccountsJob() throws Exception {
        UUID ownerTenant = tenant();
        DeviceService.Registered ownerBridge = bridge(ownerTenant);
        UUID ownerJob = queuedJob(ownerTenant, ownerBridge.deviceId());

        UUID strangerTenant = tenant();
        DeviceService.Registered strangerBridge = bridge(strangerTenant);

        // The stranger's poll finds nothing of the other account's, even though the job exists.
        mockMvc.perform(post("/api/v1/device-agent/claim")
                        .header("X-Device-Key", strangerBridge.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").doesNotExist());

        // And it cannot complete it by guessing the id either.
        mockMvc.perform(multipart("/api/v1/device-agent/jobs/" + ownerJob + "/result")
                        .param("errorCode", "NO_PAPER")
                        .header("X-Device-Key", strangerBridge.apiKey()))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("select status from device_jobs where id = ?", String.class, ownerJob))
                .isEqualTo("QUEUED");
    }
}
