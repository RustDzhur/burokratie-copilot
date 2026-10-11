package de.buerokratiecopilot.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.ingest.IngestService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies that what the bridge collects reaches the inbox (spec §8, G-002).
 *
 * <p>The folder source stands in for the device, and it is the same code path the SANE source uses after a
 * scan: pages come out of {@code readFolder} exactly as they come out of {@code scan}, and they are handed
 * to the same ingestion. What is therefore verified is everything from the folder to the stored document.
 * What is <em>not</em> verified is a scan from real hardware, because this machine has none — and the
 * diagnosis for that case is what {@code BridgeConfigTest} checks.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ScanImportIT {

    @Autowired
    private DesktopBridge bridge;

    @Autowired
    private IngestService ingest;

    @Autowired
    private JdbcTemplate jdbc;

    private record Tenant(UUID tenantId, UUID workspaceId, UUID userId) {
    }

    private Tenant tenant() {
        UUID tenantId = jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Brücke Test', 'COMPANY') returning id
                """, UUID.class, "br-" + UUID.randomUUID().toString().substring(0, 12));
        UUID organizationId = jdbc.queryForObject("""
                insert into organizations (id, tenant_id, name, country_code, is_own_entity)
                values (gen_random_uuid(), ?, 'Brücke GmbH', 'DE', true) returning id
                """, UUID.class, tenantId);
        UUID workspaceId = jdbc.queryForObject("""
                insert into workspaces (id, tenant_id, organization_id, name, mode, is_default, settings)
                values (gen_random_uuid(), ?, ?, 'Standard', 'BUSINESS', true, '{}'::jsonb) returning id
                """, UUID.class, tenantId, organizationId);
        UUID userId = jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Person', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "br-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
        jdbc.update("""
                insert into memberships (id, tenant_id, workspace_id, user_id, status, is_owner)
                values (gen_random_uuid(), ?, ?, ?, 'ACTIVE', true)
                """, tenantId, workspaceId, userId);
        return new Tenant(tenantId, workspaceId, userId);
    }

    private IngestService.Caller callerFor(Tenant tenant) {
        var token = RequestContext.runAs(tenant.tenantId(), () -> ingest.issueToken(tenant.tenantId(),
                tenant.workspaceId(), "Brücke", List.of("documents:write"), null, null, tenant.userId()));
        return RequestContext.runAs(tenant.tenantId(), () -> ingest.authenticate(token.token()));
    }

    private DesktopBridge.BridgeConfig folderConfig(Path folder) {
        return new DesktopBridge.BridgeConfig("FOLDER", null, 300, "Color", "ADF", folder, "pair", 3);
    }

    @Test
    @DisplayName("a folder the bridge watches is diagnosed as ready")
    void theFolderIsReady(@TempDir Path folder) {
        DesktopBridge.Diagnostics diagnostics = bridge.diagnose(folderConfig(folder));

        assertThat(diagnostics.ready()).isTrue();
        assertThat(diagnostics.summary()).contains("bereit");
    }

    @Test
    @DisplayName("pages collected in the folder reach the inbox")
    void pagesReachTheInbox(@TempDir Path folder) throws Exception {
        Tenant tenant = tenant();
        IngestService.Caller caller = callerFor(tenant);

        // A stack of pages, as an automatic document feeder would produce them.
        byte[] first = "Seite eins: Bescheid".getBytes(StandardCharsets.UTF_8);
        byte[] second = "Seite zwei: Anlage".getBytes(StandardCharsets.UTF_8);
        Files.write(folder.resolve("scan-001.png"), first);
        Files.write(folder.resolve("scan-002.png"), second);

        List<byte[]> pages = bridge.readFolder(folderConfig(folder));
        assertThat(pages).hasSize(2);

        UUID firstDocument = RequestContext.runAs(tenant.tenantId(), () -> ingest.ingest(caller,
                "scan-001", "scan-001.png", "image/png", sha256(pages.get(0)), pages.get(0),
                tenant.workspaceId())).documentId();
        UUID secondDocument = RequestContext.runAs(tenant.tenantId(), () -> ingest.ingest(caller,
                "scan-002", "scan-002.png", "image/png", sha256(pages.get(1)), pages.get(1),
                tenant.workspaceId())).documentId();

        assertThat(firstDocument).isNotNull();
        assertThat(secondDocument).isNotEqualTo(firstDocument);
        assertThat(RequestContext.runAs(tenant.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from documents where tenant_id = ? and source = 'API'
                """, Integer.class, tenant.tenantId())))
                .as("both pages of the stack are in the inbox")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a page that is sent twice is recognised, so a lost answer does not double it")
    void aPageSentTwiceIsRecognised(@TempDir Path folder) throws Exception {
        Tenant tenant = tenant();
        IngestService.Caller caller = callerFor(tenant);
        byte[] page = "Seite eins".getBytes(StandardCharsets.UTF_8);
        Files.write(folder.resolve("scan-001.png"), page);
        List<byte[]> pages = bridge.readFolder(folderConfig(folder));

        RequestContext.runAs(tenant.tenantId(), () -> ingest.ingest(caller, "key-a", "scan-001.png",
                "image/png", sha256(pages.get(0)), pages.get(0), tenant.workspaceId()));
        // The upload was retried after a timeout: the page already arrived, and the bridge must not create
        // a second copy of a scanned sheet.
        IngestService.Ingested again = RequestContext.runAs(tenant.tenantId(), () -> ingest.ingest(caller,
                "key-b", "scan-001.png", "image/png", sha256(pages.get(0)), pages.get(0),
                tenant.workspaceId()));

        assertThat(again.duplicate()).isTrue();
    }

    @Test
    @DisplayName("a retried upload of the same key does not create a second document")
    void aRetriedUploadDoesNotDouble(@TempDir Path folder) throws Exception {
        Tenant tenant = tenant();
        IngestService.Caller caller = callerFor(tenant);
        byte[] page = "Seite eins".getBytes(StandardCharsets.UTF_8);
        Files.write(folder.resolve("scan-001.png"), page);
        List<byte[]> pages = bridge.readFolder(folderConfig(folder));

        UUID first = RequestContext.runAs(tenant.tenantId(), () -> ingest.ingest(caller, "same-key",
                "scan-001.png", "image/png", sha256(pages.get(0)), pages.get(0),
                tenant.workspaceId())).documentId();
        UUID second = RequestContext.runAs(tenant.tenantId(), () -> ingest.ingest(caller, "same-key",
                "scan-001.png", "image/png", sha256(pages.get(0)), pages.get(0),
                tenant.workspaceId())).documentId();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("the bridge sends through the same retry path the uploader uses")
    void theBridgeSendsThroughTheRetryPath(@TempDir Path folder) throws Exception {
        Tenant tenant = tenant();
        IngestService.Caller caller = callerFor(tenant);
        Files.write(folder.resolve("scan-001.png"), "Seite".getBytes(StandardCharsets.UTF_8));
        List<byte[]> pages = bridge.readFolder(folderConfig(folder));

        boolean sent = bridge.upload(pages.getFirst(), "scan-001.png", "image/png", 3, page ->
                RequestContext.runAs(tenant.tenantId(), () -> {
                    ingest.ingest(caller, "bridge-key", "scan-001.png", "image/png", sha256(page), page,
                            tenant.workspaceId());
                    return true;
                }));

        assertThat(sent).isTrue();
        assertThat(RequestContext.runAs(tenant.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from documents where tenant_id = ?
                """, Integer.class, tenant.tenantId()))).isEqualTo(1);
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content));
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
