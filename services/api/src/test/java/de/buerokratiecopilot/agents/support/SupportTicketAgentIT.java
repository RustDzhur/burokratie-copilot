package de.buerokratiecopilot.agents.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * {@code support-ticket} against the real database: one ticket creates one task, and a repeated
 * run with the same title creates none.
 */
@SpringBootTest
@ActiveProfiles("test")
class SupportTicketAgentIT {

    @Autowired
    private SupportTicketAgent agent;

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void createsOneTaskAndDoesNotDuplicateIt() {
        var tenant = factory.createTenantWithOwner("support-agent@example.de");
        var owner = factory.ownerOf(tenant);
        AgentContext context = new AgentContext(tenant.getId(), owner.getId(), "manual",
                Instant.parse("2026-10-26T09:00:00Z"),
                Map.of("subject", "Rechnung doppelt", "description", "Bitte prüfen, dringend",
                        "channel", "EMAIL", "customerEmail", "kunde@example.de"));

        AgentResult first = factory.inTenant(tenant.getId(), () -> agent.run(context));

        assertThat(first.ok()).isTrue();
        assertThat(first.meta()).containsEntry("topic", "RECHNUNG")
                .containsEntry("priority", "HIGH");
        Integer tasks = jdbc.queryForObject("""
                select count(*) from tasks where tenant_id = ? and title = 'Support: Rechnung doppelt'
                """, Integer.class, tenant.getId());
        assertThat(tasks).isEqualTo(1);

        AgentResult second = factory.inTenant(tenant.getId(), () -> agent.run(context));

        assertThat(second.ok()).isFalse();
        assertThat(second.summary()).contains("bereits");
        Integer after = jdbc.queryForObject("""
                select count(*) from tasks where tenant_id = ? and title = 'Support: Rechnung doppelt'
                """, Integer.class, tenant.getId());
        assertThat(after).isEqualTo(1);
    }
}
