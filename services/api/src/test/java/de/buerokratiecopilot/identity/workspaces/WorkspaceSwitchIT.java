package de.buerokratiecopilot.identity.workspaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import de.buerokratiecopilot.billing.PlanFeatures;

/** The document page shows the risks that cite the document and the cases it belongs to (S02-14). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkspaceSwitchIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlanFeatures planFeatures;

    private record Session(Cookie cookie, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "ins-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        String body = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"P\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(body, "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andReturn().getResponse().getCookie("bc_session");
        return new Session(cookie, userId);
    }

    private UUID upload(Session s, String name) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", name, "text/plain", ("Rechnung " + name + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)))
                .with(csrf()).cookie(s.cookie())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.document.id"));
    }

    private int documentCount(Session s) throws Exception {
        String body = mockMvc.perform(get("/api/v1/documents").cookie(s.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.totalElements")).intValue();
    }

    @Test
    @DisplayName("a Professional owner creates client workspaces and switches between them; each keeps its own documents")
    void accountantKeepsClientsApart() throws Exception {
        Session accountant = register();
        UUID home = jdbc.queryForObject("select tenant_id from memberships where user_id = ?", UUID.class, accountant.userId());
        // Without the Professional plan nothing can be created.
        mockMvc.perform(post("/api/v1/workspaces").with(csrf()).cookie(accountant.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Müller GmbH\"}")).andExpect(status().isForbidden());

        jdbc.update("update subscriptions set plan_code = 'PROFESSIONAL' where tenant_id = ?", home);
        planFeatures.forget(home);
        mockMvc.perform(get("/api/v1/workspaces").cookie(accountant.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities.canCreateClients").value(true))
                .andExpect(jsonPath("$.items.length()").value(1));

        String created = mockMvc.perform(post("/api/v1/workspaces").with(csrf()).cookie(accountant.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Müller GmbH\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID client = UUID.fromString(JsonPath.read(created, "$.tenantId"));
        assertThat((String) JsonPath.read(created, "$.planCode")).isEqualTo("PROFESSIONAL");
        mockMvc.perform(get("/api/v1/workspaces").cookie(accountant.cookie()))
                .andExpect(jsonPath("$.items.length()").value(2));

        upload(accountant, "eigene-firma.txt");
        assertThat(documentCount(accountant)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/workspaces/" + client + "/switch").with(csrf()).cookie(accountant.cookie()))
                .andExpect(status().isNoContent());
        assertThat(documentCount(accountant)).as("the client's workspace starts empty").isZero();
        upload(accountant, "mueller-rechnung.txt");
        assertThat(documentCount(accountant)).isEqualTo(1);

        mockMvc.perform(post("/api/v1/workspaces/" + home + "/switch").with(csrf()).cookie(accountant.cookie()))
                .andExpect(status().isNoContent());
        String own = mockMvc.perform(get("/api/v1/documents").cookie(accountant.cookie())).andReturn().getResponse().getContentAsString();
        assertThat(own).contains("eigene-firma.txt").doesNotContain("mueller-rechnung.txt");

        Session stranger = register();
        mockMvc.perform(post("/api/v1/workspaces/" + client + "/switch").with(csrf()).cookie(stranger.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/workspaces").cookie(stranger.cookie()))
                .andExpect(jsonPath("$.items.length()").value(1));
    }
}
