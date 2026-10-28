package de.buerokratiecopilot.agents.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.shared.storage.BlobStore;

class AgentArtifactStoreUnitTest {

    private BlobStore blobs;
    private JdbcTemplate jdbc;
    private AgentArtifactStore store;
    private final UUID tenantId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        blobs = mock(BlobStore.class);
        jdbc = mock(JdbcTemplate.class);
        store = new AgentArtifactStore(blobs, jdbc, new ObjectMapper());
    }

    @Test
    void anExistingSourceKeyReturnsTheStoredArtifactWithoutWritingAgain() {
        when(jdbc.queryForList(contains("from generated_documents"), any(Object.class),
                any(Object.class), any(Object.class))).thenReturn(List.of(Map.of(
                "id", documentId,
                "field_values", "{\"storageKey\":\"tenants/key/a.csv\",\"filename\":\"a.csv\","
                        + "\"contentType\":\"text/csv\",\"size\":12}")));

        AgentArtifactStore.Artifact artifact = store.store(tenantId, actorId, "same-key", "Titel",
                "a.csv", "text/csv", "TXT", "a,b\n1,2\n");

        assertThat(artifact.created()).isFalse();
        assertThat(artifact.storageKey()).isEqualTo("tenants/key/a.csv");
        assertThat(artifact.sizeBytes()).isEqualTo(12);
        verifyNoInteractions(blobs);
    }

    @Test
    void aNewSourceKeyIsStoredInTheBlobStoreAndRegistered() {
        when(jdbc.queryForList(contains("from generated_documents"), any(Object.class),
                any(Object.class), any(Object.class))).thenReturn(List.of());
        when(blobs.store(eq(tenantId), any(), eq(6L), eq("text/csv")))
                .thenReturn(new BlobStore.StoredBlob("tenants/key/b.csv", "local-fs", 6, "hash"));
        when(jdbc.queryForObject(contains("insert into generated_documents"), eq(UUID.class),
                any(Object.class), any(Object.class), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class), any(Object.class)))
                .thenReturn(documentId);

        AgentArtifactStore.Artifact artifact = store.store(tenantId, actorId, "new-key", "Titel",
                "b.csv", "text/csv", "TXT", "a,b\n1\n");

        assertThat(artifact.created()).isTrue();
        assertThat(artifact.documentId()).isEqualTo(documentId);
        assertThat(artifact.storageKey()).isEqualTo("tenants/key/b.csv");
    }
}
