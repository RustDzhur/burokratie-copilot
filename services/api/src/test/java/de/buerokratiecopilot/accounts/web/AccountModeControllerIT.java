package de.buerokratiecopilot.accounts.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;

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

/**
 * Account modes over HTTP (spec §3.1, §31; H-001).
 *
 * <p>What this checks beyond the routes: the account type supplies a <strong>starting point</strong> and the
 * account's own decision overrides it, and <strong>switching the type deletes nothing</strong> — a module
 * that becomes unavailable is no reason to destroy what was filed under it.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountModeControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "mode-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Modus Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));

        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    /** A module the account type does not imply, so that configuring it is a real change. */
    private String moduleKey(Session session, boolean implied) throws Exception {
        MvcResult listed = mockMvc.perform(get("/api/v1/account").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        java.util.Map<String, Object> modules = JsonPath.read(
                listed.getResponse().getContentAsString(), "$.modules");
        for (java.util.Map.Entry<String, Object> entry : modules.entrySet()) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> values = (java.util.Map<String, Object>) entry.getValue();
            boolean allowed = Boolean.TRUE.equals(values.get("allowed"));
            if (allowed == implied) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Kein Modul mit allowed=" + implied);
    }

    // ---------------------------------------------------------------- the routes

    @Test
    @DisplayName("the account type and its modules are served")
    void theAccountIsServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/account").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountType").value("COMPANY"))
                .andExpect(jsonPath("$.defaultsOfType").isArray())
                .andExpect(jsonPath("$.modules").isMap());
    }

    @Test
    @DisplayName("a module says whether it is allowed and where that came from")
    void aModuleSaysWhereItCameFrom() throws Exception {
        Session session = register();
        String implied = moduleKey(session, true);

        // A module available because the account type implies it, and one the account switched on itself,
        // are different facts: only the second survives a change of type.
        mockMvc.perform(get("/api/v1/account/modules/{key}", implied).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.source").value("ACCOUNT_TYPE"));
    }

    // ---------------------------------------------------------------- the override

    @Test
    @DisplayName("the account can switch on a module its type does not imply")
    void aModuleCanBeSwitchedOn() throws Exception {
        Session session = register();
        String notImplied = moduleKey(session, false);

        mockMvc.perform(post("/api/v1/account/modules/{key}", notImplied).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.configured").value(true));

        // A private person who starts keeping books does not need a different account.
        mockMvc.perform(get("/api/v1/account/modules/{key}", notImplied).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("ACCOUNT"));
    }

    @Test
    @DisplayName("the account can switch off a module its type implies")
    void aModuleCanBeSwitchedOff() throws Exception {
        Session session = register();
        String implied = moduleKey(session, true);

        mockMvc.perform(post("/api/v1/account/modules/{key}", implied).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));

        mockMvc.perform(get("/api/v1/account/modules/{key}", implied).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));
    }

    @Test
    @DisplayName("an unknown module is refused with a sentence")
    void anUnknownModuleIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/account/modules/{key}", "gibt.es.nicht").cookie(session.cookie()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/account/modules/{key}", "gibt.es.nicht").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or changed")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/account")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/account/modules/{key}", "documents")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an account's modules are its own")
    void accountsAreSeparated() throws Exception {
        Session first = register();
        Session second = register();
        String module = moduleKey(first, true);
        mockMvc.perform(post("/api/v1/account/modules/{key}", module).with(csrf())
                        .cookie(first.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        // The second account did not ask for this and must not be affected by it.
        mockMvc.perform(get("/api/v1/account/modules/{key}", module).cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("ACCOUNT_TYPE"));
    }

    @Test
    @DisplayName("the settings permissions exist and are held, which is what makes these reachable")
    void theSettingsPermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('settings.view','settings.edit')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the settings permissions must be granted to OWNER and ADMIN, or these are dead")
                .isEqualTo(4);
    }
}
