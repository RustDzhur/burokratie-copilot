package de.buerokratiecopilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.shared.context.RequestContext;

/** A demo cabinet opens without a password, is filled, cannot reach the outside, and is gone without a trace when left. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoCabinetIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DemoService demo;

    private Cookie start(String persona, String ip) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/public/demo/start").with(csrf())
                        .with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content("{\"persona\":\"" + persona + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.next").value("/dashboard?tour=1")).andReturn();
        Cookie cookie = result.getResponse().getCookie("bc_session");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private UUID tenantOf(Cookie cookie) throws Exception {
        String me = mockMvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(me, "$.tenantId"));
    }

    private int count(String sql, UUID tenant) {
        Integer value = RequestContext.runAs(tenant, () -> jdbc.queryForObject(sql, Integer.class, tenant));
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("a family demo opens without a password, with letters read and sorted, work to do, and a status that says it is a demo")
    void familyDemoIsFilled() throws Exception {
        Cookie cookie = start("family", "10.1.0.1");
        UUID tenant = tenantOf(cookie);

        assertThat(count("select count(*) from documents where tenant_id = ? and document_type_id is not null", tenant))
                .as("letters are sorted by the platform's own rules").isGreaterThanOrEqualTo(3);
        assertThat(count("select count(*) from documents where tenant_id = ? and processing_status = 'REVIEW_REQUIRED'", tenant))
                .as("and the unsure ones wait for a person, which the visitor can try").isGreaterThan(0);
        assertThat(count("select count(*) from tasks where tenant_id = ?", tenant)).as("work follows from the letters").isGreaterThan(0);
        assertThat(count("select count(*) from parties where tenant_id = ?", tenant)).isEqualTo(3);
        assertThat(count("select count(*) from documents where tenant_id = ? and sender is not null and sender_contact_id is not null", tenant))
                .as("the senders are read from the letters and kept as contacts").isGreaterThanOrEqualTo(3);
        assertThat(count("select count(*) from contracts where tenant_id = ?", tenant)).isGreaterThan(0);

        mockMvc.perform(get("/api/v1/demo/status").cookie(cookie)).andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.persona").value("FAMILY")).andExpect(jsonPath("$.expiresAt").exists());
        // The account has no password: the only way in is the button.
        String email = jdbc.queryForObject("select u.email from users u join memberships m on m.user_id = u.id where m.tenant_id = ? and m.is_owner",
                String.class, tenant);
        assertThat(email).endsWith("@demo.invalid");
        assertThat(jdbc.queryForObject("select password_hash from users where email = ?", String.class, email)).isNull();
    }

    @Test
    @DisplayName("in a demo cabinet everything that reaches the outside or changes the way in is closed")
    void outsideIsClosed() throws Exception {
        Cookie cookie = start("freelancer", "10.1.0.2");
        for (String path : new String[] {"/api/v1/mail/messages", "/api/v1/integrations", "/api/v1/billing/subscription", "/api/v1/settings/security", "/api/v1/admin/overview"}) {
            mockMvc.perform(get(path).cookie(cookie)).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/v1/documents").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("leaving deletes the cabinet, its documents, the person and the session; an expired cabinet is deleted by the job")
    void leavingLeavesNothing() throws Exception {
        Cookie cookie = start("person", "10.1.0.3");
        UUID tenant = tenantOf(cookie);
        UUID user = jdbc.queryForObject("select user_id from memberships where tenant_id = ? and is_owner", UUID.class, tenant);
        // The visitor changes things: a document is deleted, a task closed. None of it survives.
        mockMvc.perform(post("/api/v1/demo/exit").with(csrf()).cookie(cookie)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from tenants where id = ?", Integer.class, tenant)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from documents where tenant_id = ?", Integer.class, tenant)).isZero();
        mockMvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());

        Cookie second = start("person", "10.1.0.4");
        UUID secondTenant = tenantOf(second);
        jdbc.update("update tenants set created_at = now() - interval '5 hours' where id = ?", secondTenant);
        assertThat(demo.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenants where id = ?", Integer.class, secondTenant)).isZero();
    }

    @Test
    @DisplayName("the advice-centre demo holds several client companies, each with its own letters, and the switcher is there")
    void advisorHasClients() throws Exception {
        Cookie cookie = start("advisor", "10.1.0.5");
        mockMvc.perform(get("/api/v1/workspaces").cookie(cookie)).andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.capabilities.professionalPlan").value(true));
        UUID home = tenantOf(cookie);
        UUID client = jdbc.queryForObject("""
                select t.id from tenants t join memberships m on m.tenant_id = t.id
                where m.user_id = (select user_id from memberships where tenant_id = ? and is_owner) and t.id <> ? order by t.name limit 1
                """, UUID.class, home, home);
        mockMvc.perform(post("/api/v1/workspaces/" + client + "/switch").with(csrf()).cookie(cookie)).andExpect(status().isNoContent());
        assertThat(count("select count(*) from documents where tenant_id = ?", client)).isEqualTo(3);
        // Leaving removes the office and all its clients.
        mockMvc.perform(post("/api/v1/demo/exit").with(csrf()).cookie(cookie)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from tenants where id in (?, ?)", Integer.class, home, client)).isZero();
    }

    @Test
    @DisplayName("an unknown cabinet is refused, and one address cannot open a cabinet again and again")
    void startIsLimited() throws Exception {
        mockMvc.perform(post("/api/v1/public/demo/start").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"persona\":\"nonsense\"}")).andExpect(status().isUnprocessableEntity());
        for (int i = 0; i < DemoService.MAX_STARTS_PER_IP_PER_HOUR; i++) {
            Cookie cookie = start("person", "10.1.9.9");
            mockMvc.perform(post("/api/v1/demo/exit").with(csrf()).cookie(cookie)).andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/public/demo/start").with(csrf()).with(request -> { request.setRemoteAddr("10.1.9.9"); return request; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"persona\":\"person\"}")).andExpect(status().isTooManyRequests());
    }
}
