package de.buerokratiecopilot.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import de.buerokratiecopilot.shared.context.RequestContext;

/** The business blocks of the dashboard (S02-03): money owed to and by the business, and open tasks per person. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DashboardBusinessIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("receivables, payables and tasks per person are summed from what is stored, per currency, and only for this tenant")
    void businessBlocks() throws Exception {
        String email = "dash-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        String body = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"Erika\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(body, "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andReturn().getResponse().getCookie("bc_session");
        UUID tenant = jdbc.queryForObject("select tenant_id from memberships where user_id = ?", UUID.class, userId);

        RequestContext.runAs(tenant, () -> {
            invoice(tenant, "OUTGOING", "RECEIVED", "119.00", "19.00", "UNPAID", "2026-01-01");
            invoice(tenant, "OUTGOING", "PARTIALLY_PAID", "200.00", "50.00", "PARTIALLY_PAID", "2099-01-01");
            invoice(tenant, "OUTGOING", "PAID", "500.00", "500.00", "PAID", "2026-01-01");
            invoice(tenant, "INCOMING", "APPROVED", "80.00", "0", "UNPAID", "2026-01-01");
            UUID task = jdbc.queryForObject("""
                    insert into tasks (tenant_id, title, due_at) values (?, 'Mahnung prüfen', now() - interval '1 day') returning id
                    """, UUID.class, tenant);
            jdbc.update("insert into task_assignees (task_id, tenant_id, user_id) values (?, ?, ?)", task, tenant, userId);
        });

        String summary = mockMvc.perform(get("/api/v1/dashboard/summary").cookie(cookie)).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(summary, "$.business.receivables[0].open_amount")).doubleValue()).isEqualTo(250.0);
        assertThat((Integer) JsonPath.read(summary, "$.business.receivables[0].count")).isEqualTo(2);
        assertThat(((Number) JsonPath.read(summary, "$.business.receivables[0].overdue_amount")).doubleValue()).isEqualTo(100.0);
        assertThat(((Number) JsonPath.read(summary, "$.business.payables[0].open_amount")).doubleValue()).isEqualTo(80.0);
        assertThat((String) JsonPath.read(summary, "$.business.tasksByPerson[0].name")).isEqualTo("Erika");
        assertThat((Integer) JsonPath.read(summary, "$.business.tasksByPerson[0].overdue")).isEqualTo(1);
    }

    private void invoice(UUID tenant, String direction, String status, String gross, String paid, String paymentStatus, String due) {
        jdbc.update("""
                insert into invoices (tenant_id, direction, status, payment_status, gross_amount, paid_amount, due_date)
                values (?, ?, ?, ?, ?::numeric, ?::numeric, ?::date)
                """, tenant, direction, status, paymentStatus, gross, paid, due);
    }
}
