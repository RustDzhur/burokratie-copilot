package de.buerokratiecopilot.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/** BM-06: an obligation nobody owns and no task serves is found by a query; a task can be linked and unlinked. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ObligationLinkIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;

    private record Account(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "obl-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Obligation Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.userId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn();
        return new Account(tenantId, userId, login.getResponse().getCookie("bc_session"));
    }

    @Test
    @DisplayName("neglected obligations are found; a linked open task serves one; unlinking and other tenants behave")
    void obligationsAndTasks() throws Exception {
        Account a = newTenant();
        Account b = newTenant();
        UUID[] ids = RequestContext.runAs(a.tenantId(), () -> {
            UUID contract = jdbc.queryForObject("""
                    insert into contracts (id, tenant_id, title, contract_type, status)
                    values (gen_random_uuid(), ?, 'Internet Musterstadt', 'INTERNET', 'ACTIVE') returning id
                    """, UUID.class, a.tenantId());
            UUID neglected = jdbc.queryForObject("""
                    insert into contract_obligations (contract_id, tenant_id, obligation_type, title, due_date)
                    values (?, ?, 'NOTICE', 'Kündigungsfrist beachten', current_date + 30) returning id
                    """, UUID.class, contract, a.tenantId());
            UUID owned = jdbc.queryForObject("""
                    insert into contract_obligations (contract_id, tenant_id, obligation_type, title, due_date, responsible_user_id)
                    values (?, ?, 'PAYMENT', 'Monatsbeitrag', current_date + 5, ?) returning id
                    """, UUID.class, contract, a.tenantId(), a.userId());
            UUID task = jdbc.queryForObject("""
                    insert into tasks (id, tenant_id, title) values (gen_random_uuid(), ?, 'Kündigung prüfen') returning id
                    """, UUID.class, a.tenantId());
            return new UUID[] { neglected, owned, task };
        });

        String neglected = mockMvc.perform(get("/api/v1/memory/obligations?unowned=true&withoutTask=true").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(neglected, "$")).hasSize(1);
        assertThat((Object) JsonPath.read(neglected, "$[0].id")).isEqualTo(ids[0].toString());
        assertThat((Object) JsonPath.read(neglected, "$[0].contract_title")).isEqualTo("Internet Musterstadt");

        String all = mockMvc.perform(get("/api/v1/memory/obligations").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(all, "$[0].id")).as("soonest first").isEqualTo(ids[1].toString());

        // Another tenant can neither see nor link.
        assertThat(mockMvc.perform(get("/api/v1/memory/obligations").cookie(b.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
        mockMvc.perform(post("/api/v1/memory/obligations/" + ids[0] + "/tasks/" + ids[2]).with(csrf()).cookie(b.cookie()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/memory/obligations/" + ids[0] + "/tasks/" + ids[2]).with(csrf()).cookie(a.cookie()))
                .andExpect(status().isOk());
        String served = mockMvc.perform(get("/api/v1/memory/obligations?unowned=true&withoutTask=true").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(served).as("an open task serves the obligation").isEqualTo("[]");

        mockMvc.perform(delete("/api/v1/memory/obligations/" + ids[0] + "/tasks/" + ids[2]).with(csrf()).cookie(a.cookie()))
                .andExpect(status().isOk());
        String again = mockMvc.perform(get("/api/v1/memory/obligations?unowned=true&withoutTask=true").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(again, "$")).hasSize(1);
    }
}
