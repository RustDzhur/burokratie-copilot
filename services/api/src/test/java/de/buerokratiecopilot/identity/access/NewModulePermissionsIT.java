package de.buerokratiecopilot.identity.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The rights of the memory, radar and autopilot modules exist and follow the role matrix. */
@SpringBootTest
@ActiveProfiles("test")
class NewModulePermissionsIT {

    @Autowired
    private JdbcTemplate jdbc;

    private List<String> rightsOf(String role) {
        return jdbc.queryForList("""
                select p.code from role_permissions rp
                join roles r on r.id = rp.role_id and r.tenant_id is null and r.code = ?
                join permissions p on p.id = rp.permission_id
                where p.area in ('memory', 'radar', 'autopilot', 'approvals')
                """, String.class, role);
    }

    @Test
    @DisplayName("the nine rights exist, and owner and administrator hold all of them")
    void allRightsExist() {
        List<String> codes = jdbc.queryForList(
                "select code from permissions where area in ('memory', 'radar', 'autopilot', 'approvals')", String.class);
        assertThat(codes).containsExactlyInAnyOrder("memory.view", "memory.confirm", "memory.reject", "radar.view",
                "radar.triage", "radar.manage_rules", "autopilot.plan", "autopilot.execute", "approvals.decide");
        assertThat(rightsOf("OWNER")).hasSize(9);
        assertThat(rightsOf("ADMIN")).hasSize(9);
    }

    @Test
    @DisplayName("an employee only looks; a manager does everything except managing the rules; a family member has none")
    void roleMatrix() {
        assertThat(rightsOf("EMPLOYEE")).containsExactlyInAnyOrder("memory.view", "radar.view");
        assertThat(rightsOf("MANAGER")).hasSize(8).doesNotContain("radar.manage_rules");
        assertThat(rightsOf("FAMILY_MEMBER")).isEmpty();
        assertThat(rightsOf("EXTERNAL_ADVISOR")).isEmpty();
        assertThat(rightsOf("READ_ONLY")).containsExactlyInAnyOrder("memory.view", "radar.view");
    }
}
