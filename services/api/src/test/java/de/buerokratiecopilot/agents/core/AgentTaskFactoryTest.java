package de.buerokratiecopilot.agents.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.identity.domain.Tenant;
import de.buerokratiecopilot.support.TestDataFactory;
import de.buerokratiecopilot.tasks.domain.Task;

/**
 * {@link AgentTaskFactory} against the real task table: the same title creates one task, a second
 * run finds it, and the task carries its completion criterion and the SYSTEM source.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentTaskFactoryTest {

    @Autowired
    private AgentTaskFactory factory;

    @Autowired
    private TestDataFactory testData;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsOneTaskPerTitleAndReusesItOnARerun() {
        Tenant tenant = testData.createTenantWithOwner("task-factory-" + UUID.randomUUID()
                + "@example.de");
        String title = "Wissensartikel prüfen: " + UUID.randomUUID();
        AgentContext context = new AgentContext(tenant.getId(), null, "manual", Instant.now(),
                Map.of());
        AgentTaskFactory.Request request = new AgentTaskFactory.Request(title,
                "Prüfung der Quelle", "Der Artikel ist geprüft.", Instant.now().plusSeconds(86400),
                Task.Priority.NORMAL, List.of("wissensbasis"), null, null);

        Optional<Task> first = testData.inTenant(tenant.getId(),
                () -> factory.createIfAbsent(context, request));
        Optional<Task> second = testData.inTenant(tenant.getId(),
                () -> factory.createIfAbsent(context, request));

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        Long rows = testData.inTenant(tenant.getId(), () -> jdbc.queryForObject("""
                select count(*) from tasks
                where tenant_id = ? and title = ? and source = 'SYSTEM'
                """, Long.class, tenant.getId(), title));
        assertThat(rows).isEqualTo(1L);
        String criteria = testData.inTenant(tenant.getId(), () -> jdbc.queryForObject("""
                select completion_criteria from tasks where id = ?
                """, String.class, first.orElseThrow().getId()));
        assertThat(criteria).isEqualTo("Der Artikel ist geprüft.");
    }
}
