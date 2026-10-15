package de.buerokratiecopilot.publicstats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import de.buerokratiecopilot.shared.context.RequestContext;

/** The landing page numbers are the real totals, they need no sign-in, and a demo cabinet's invented data is not in them. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicStatsIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PublicStatsController controller;

    private UUID tenant(boolean demo) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into tenants (id, slug, name, account_type, settings) values (?, ?, 'T', 'COMPANY', ?::jsonb)", id,
                "t-" + id.toString().substring(0, 8), demo ? "{\"demo\":true}" : "{}");
        return id;
    }

    private void document(UUID tenant) {
        RequestContext.runAs(tenant, () -> jdbc.update("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type, size_bytes, content_hash, source,
                                       processing_status, review_status)
                values (gen_random_uuid(), ?, 'a.pdf', 'a.pdf', 'application/pdf', 1, ?, 'UPLOAD', 'READY', 'NOT_REQUIRED')
                """, tenant, "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong())));
    }

    private long documents() throws Exception {
        String body = mockMvc.perform(get("/api/v1/public/stats")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.documents")).longValue();
    }

    @Test
    @DisplayName("the total counts documents of real workspaces and leaves out a demo cabinet; it needs no sign-in")
    void totalsAreReal() throws Exception {
        long before = documents();
        UUID real = tenant(false);
        UUID demo = tenant(true);
        document(real);
        document(real);
        document(demo);

        // The answer is kept for a minute; for the test the keeping is cleared.
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "cache", new java.util.concurrent.atomic.AtomicReference<>());
        assertThat(documents()).isEqualTo(before + 2);
        String body = mockMvc.perform(get("/api/v1/public/stats")).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(body, "$.cases")).isNotNull();
        assertThat((Object) JsonPath.read(body, "$.tasksDone")).isNotNull();
        assertThat(body).doesNotContain("a.pdf");
    }
}
