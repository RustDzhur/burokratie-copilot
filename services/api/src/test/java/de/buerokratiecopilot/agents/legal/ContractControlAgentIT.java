package de.buerokratiecopilot.agents.legal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * {@code legal-contract-control} against the contract register: a contract ending inside the
 * horizon is found and reported, a contract ending later is not.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContractControlAgentIT {

    @Autowired
    private ContractControlAgent agent;

    @Autowired
    private TestDataFactory factory;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reportsTheContractInsideTheHorizonOnly() {
        var tenant = factory.createTenantWithOwner("contract-agent@example.de");
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        LocalDate today = LocalDate.ofInstant(now, ZoneId.of("Europe/Berlin"));
        UUID inside = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        factory.inTenant(tenant.getId(), () -> {
            insertContract(inside, tenant.getId(), "Wartung Werkstatt", today.plusDays(20));
            insertContract(outside, tenant.getId(), "Miete Büro", today.plusDays(200));
            return null;
        });

        AgentContext context = new AgentContext(tenant.getId(), null, "manual", now,
                Map.of("horizonDays", "30", "includeObligations", "false"));
        AgentResult result = factory.inTenant(tenant.getId(), () -> agent.run(context));

        assertThat(result.ok()).isTrue();
        assertThat(result.meta()).containsEntry("expiringContracts", "1");
        assertThat(result.produced()).extracting(p -> p.ref()).containsExactly(inside.toString());
    }

    private void insertContract(UUID id, UUID tenantId, String title, LocalDate endDate) {
        jdbc.update("""
                insert into contracts (id, tenant_id, title, contract_type, status, end_date,
                                       cost_amount, cost_currency)
                values (?, ?, ?, 'MAINTENANCE', 'ACTIVE', ?, 120.00, 'EUR')
                """, id, tenantId, title, java.sql.Date.valueOf(endDate));
    }
}
