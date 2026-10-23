package de.buerokratiecopilot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

/**
 * The Kanzlei edition has one kind of cabinet (§4.3). Two things have to stay true for that to mean anything:
 * registration creates that one cabinet whatever an old client sends, and the routes of the removed modules and
 * of the removed cabinet switch are gone — a screen that is deleted while its endpoint still answers is a product
 * that is hidden, not removed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KanzleiRoutesIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private record Account(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Account register(String body) throws Exception {
        String email = "kanzlei-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult created = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andReturn().getResponse().getCookie("bc_session");
        return new Account(tenantId, userId, cookie);
    }

    private static final String REGISTER =
            "{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"Kanzlei Test\"}";

    @Test
    @DisplayName("registration creates the Kanzlei cabinet and ignores a type an old client still sends")
    void registrationCreatesTheOneCabinet() throws Exception {
        // A client built before §4.3 may still send a type. It must not be able to create a second kind of
        // cabinet: the body is ignored and the one cabinet is created.
        Account account = register("""
                {"email":"%s","password":"EinSicheresPasswort-2026!","displayName":"Kanzlei Test",
                 "accountType":"PRIVATE_PERSON"}
                """);

        assertThat(jdbc.queryForObject("select account_type from tenants where id = ?", String.class,
                account.tenantId())).as("the one kind of cabinet").isEqualTo("COMPANY");
        assertThat(jdbc.queryForObject("select mode from workspaces where tenant_id = ? and is_default",
                String.class, account.tenantId())).as("the workspace mode that belongs to it").isEqualTo("BUSINESS");
        assertThat(jdbc.queryForObject("select plan_code from subscriptions where tenant_id = ?", String.class,
                account.tenantId())).as("the plan a Kanzlei starts on").isEqualTo("BUSINESS");

        // The session no longer carries a cabinet kind at all: there is nothing to branch on in the interface.
        mockMvc.perform(get("/api/v1/auth/me").cookie(account.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceName").exists())
                .andExpect(jsonPath("$.accountType").doesNotExist());
    }

    @Test
    @DisplayName("the household routes of the removed family module answer 404")
    void familyRoutesAreGone() throws Exception {
        Account account = register(REGISTER);
        for (String path : List.of("/api/v1/family/documents", "/api/v1/family/documents/personal",
                "/api/v1/family/members", "/api/v1/family/access-bases")) {
            mockMvc.perform(get(path).cookie(account.cookie()))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(post("/api/v1/family/documents/" + UUID.randomUUID() + "/personal").with(csrf())
                        .cookie(account.cookie()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the cabinet can no longer be switched: the route is gone and the menu has one shape")
    void theCabinetCannotBeSwitched() throws Exception {
        Account account = register(REGISTER);

        mockMvc.perform(post("/api/v1/account/type").with(csrf())
                        .cookie(account.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountType\":\"PRIVATE_PERSON\"}"))
                .andExpect(status().isNotFound());

        // The type-listing routes described the removed kinds; they are gone with them.
        mockMvc.perform(get("/api/v1/account/types").cookie(account.cookie()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select account_type from tenants where id = ?", String.class,
                account.tenantId())).isEqualTo("COMPANY");
    }
}
