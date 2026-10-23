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

import de.buerokratiecopilot.shared.context.RequestContext;

/** The document page shows the cases the document belongs to (S02-14). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentInsightsIT {

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

    private UUID upload(Session s, String name) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", name, "text/plain", ("Rechnung " + name + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)))
                .with(csrf()).cookie(s.cookie())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.document.id"));
    }

    @Test
    @DisplayName("cases are listed per document; another tenant sees none")
    void casesOfOneDocument() throws Exception {
        Session owner = register();
        UUID doc = upload(owner, "a.txt");
        UUID other = upload(owner, "b.txt");
        UUID tenant = jdbc.queryForObject("select tenant_id from documents where id = ?", UUID.class, doc);
        UUID caseId = UUID.randomUUID();
        RequestContext.runAs(tenant, () -> {
            jdbc.update("insert into cases (id, tenant_id, reference, title, status, owner_user_id) values (?, ?, 'Z-1', 'Widerspruch', 'NEW', ?)",
                    caseId, tenant, owner.userId());
            jdbc.update("insert into case_documents (case_id, document_id, tenant_id, link_origin) values (?, ?, ?, 'MANUAL')",
                    caseId, doc, tenant);
        });

        String cases = mockMvc.perform(get("/api/v1/documents/" + doc + "/cases").cookie(owner.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(cases, "$")).hasSize(1);
        assertThat((String) JsonPath.read(cases, "$[0].title")).isEqualTo("Widerspruch");
        assertThat((List<?>) JsonPath.read(mockMvc.perform(get("/api/v1/documents/" + other + "/cases").cookie(owner.cookie()))
                .andReturn().getResponse().getContentAsString(), "$")).isEmpty();

        Session stranger = register();
        mockMvc.perform(get("/api/v1/documents/" + doc + "/cases").cookie(stranger.cookie())).andExpect(status().isNotFound());
    }
}
