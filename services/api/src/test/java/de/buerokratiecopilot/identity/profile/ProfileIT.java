package de.buerokratiecopilot.identity.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.nio.charset.StandardCharsets;
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


/** The document page shows the risks that cite the document and the cases it belongs to (S02-14). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

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

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2, 3};

    @Test
    @DisplayName("name, language and time zone are changed and checked; the photo is stored, shown, replaced and removed; nobody else's is reachable")
    void ownProfile() throws Exception {
        Session me = register();

        mockMvc.perform(put("/api/v1/profile").with(csrf()).cookie(me.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Erika Weber\",\"locale\":\"ru\",\"timeZone\":\"Europe/Berlin\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Erika Weber"))
                .andExpect(jsonPath("$.locale").value("ru"));
        mockMvc.perform(put("/api/v1/profile").with(csrf()).cookie(me.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"E\",\"locale\":\"ru\",\"timeZone\":\"Europe/Berlin\"}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/profile").with(csrf()).cookie(me.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Erika\",\"locale\":\"xx\",\"timeZone\":\"Europe/Berlin\"}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/api/v1/profile").with(csrf()).cookie(me.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Erika\",\"locale\":\"de\",\"timeZone\":\"Mars/Olympus\"}")).andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/v1/profile/avatar").cookie(me.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/v1/profile/avatar").file(new MockMultipartFile("file", "x.png", "image/png", PNG))
                .with(r -> { r.setMethod("PUT"); return r; }).with(csrf()).cookie(me.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.avatarVersion").value(1));
        byte[] shown = mockMvc.perform(get("/api/v1/profile/avatar").cookie(me.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(shown).isEqualTo(PNG);
        mockMvc.perform(multipart("/api/v1/profile/avatar").file(new MockMultipartFile("file", "x.png", "image/png", PNG))
                .with(r -> { r.setMethod("PUT"); return r; }).with(csrf()).cookie(me.cookie()))
                .andExpect(jsonPath("$.avatarVersion").value(2));
        mockMvc.perform(get("/api/v1/auth/me").cookie(me.cookie())).andExpect(jsonPath("$.avatarVersion").value(2));

        // What is not a picture is refused, whatever it is called.
        mockMvc.perform(multipart("/api/v1/profile/avatar").file(new MockMultipartFile("file", "x.png", "image/png",
                "<svg onload=alert(1)></svg>".getBytes(StandardCharsets.UTF_8)))
                .with(r -> { r.setMethod("PUT"); return r; }).with(csrf()).cookie(me.cookie())).andExpect(status().isUnprocessableEntity());

        Session other = register();
        mockMvc.perform(get("/api/v1/profile/avatar").cookie(other.cookie())).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/profile/avatar").with(csrf()).cookie(me.cookie())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/profile/avatar").cookie(me.cookie())).andExpect(status().isNotFound());
    }
}
