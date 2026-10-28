package de.buerokratiecopilot.agents.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.identity.domain.Tenant;
import de.buerokratiecopilot.shared.storage.BlobStore;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * {@link AgentArtifactStore} against the real database and the configured blob storage: the
 * migration, the row-level security and the idempotent write path are all exercised.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentArtifactStoreTest {

    @Autowired
    private AgentArtifactStore store;

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private BlobStore blobs;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void storesTheArtifactReusesTheSameContentAndVersionsAChange() {
        Tenant tenant = factory.createTenantWithOwner("artifact-" + UUID.randomUUID()
                + "@example.de");
        String sourceKey = "knowledge-index:" + UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", 2);
        payload.put("name", "Wissensindex");

        AgentArtifactStore.Artifact first = factory.inTenant(tenant.getId(),
                () -> store.put(tenant.getId(), null, "test-knowledge-index", sourceKey, payload));

        assertThat(first.reused()).isFalse();
        assertThat(blobs.exists(first.storageKey())).isTrue();

        AgentArtifactStore.Artifact second = factory.inTenant(tenant.getId(),
                () -> store.put(tenant.getId(), null, "test-knowledge-index", sourceKey, payload));

        assertThat(second.reused()).isTrue();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.contentHash()).isEqualTo(first.contentHash());

        payload.put("count", 3);
        AgentArtifactStore.Artifact changed = factory.inTenant(tenant.getId(),
                () -> store.put(tenant.getId(), null, "test-knowledge-index", sourceKey, payload));

        assertThat(changed.reused()).isFalse();
        assertThat(changed.id()).isEqualTo(first.id());
        assertThat(changed.contentHash()).isNotEqualTo(first.contentHash());

        Long rows = factory.inTenant(tenant.getId(), () -> jdbc.queryForObject("""
                select count(*) from agent_artifacts
                where tenant_id = ? and artifact_kind = ? and source_key = ?
                """, Long.class, tenant.getId(), "test-knowledge-index", sourceKey));
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    void theArtifactIsNotVisibleToAnotherTenant() {
        Tenant first = factory.createTenantWithOwner("artifact-a-" + UUID.randomUUID()
                + "@example.de");
        Tenant second = factory.createTenantWithOwner("artifact-b-" + UUID.randomUUID()
                + "@example.de");
        String sourceKey = "shared-key";
        factory.inTenant(first.getId(), () -> store.put(first.getId(), null, "test-kind",
                sourceKey, Map.of("tenant", "a")));

        var fromSecond = factory.inTenant(second.getId(),
                () -> store.find(second.getId(), "test-kind", sourceKey));

        assertThat(fromSecond).isEmpty();
    }
}
