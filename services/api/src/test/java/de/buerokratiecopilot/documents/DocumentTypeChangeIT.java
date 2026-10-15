package de.buerokratiecopilot.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import de.buerokratiecopilot.documents.classification.DocumentResortService;
import de.buerokratiecopilot.shared.context.RequestContext;

/** The document page shows the risks that cite the document and the cases it belongs to (S02-14). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentTypeChangeIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DocumentResortService resort;

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

    @Test
    @DisplayName("the type chosen by a person is stored, shown, and kept when the rules sort everything again")
    void personChoosesTheType() throws Exception {
        Session owner = register();
        UUID doc = upload(owner, "scan.txt");
        UUID tenant = jdbc.queryForObject("select tenant_id from documents where id = ?", UUID.class, doc);

        String answer = mockMvc.perform(post("/api/v1/documents/" + doc + "/type").with(csrf()).cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"typeCode\":\"vertrag\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(answer, "$.documentTypeCode")).isEqualTo("vertrag");

        RequestContext.runAs(tenant, () -> resort.resort(tenant));
        String shown = mockMvc.perform(get("/api/v1/documents/" + doc).cookie(owner.cookie()))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(shown, "$.documentTypeCode")).as("re-sorting leaves a person's choice").isEqualTo("vertrag");

        mockMvc.perform(post("/api/v1/documents/" + doc + "/type").with(csrf()).cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"typeCode\":\"gibt-es-nicht\"}"))
                .andExpect(status().isNotFound());
        Session stranger = register();
        mockMvc.perform(post("/api/v1/documents/" + doc + "/type").with(csrf()).cookie(stranger.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"typeCode\":\"rechnung\"}"))
                .andExpect(status().isNotFound());
    }
}
