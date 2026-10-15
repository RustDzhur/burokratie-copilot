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
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.shared.context.RequestContext;

/** The panel that tells a person what happened to a document must answer for a classified one (it answered 500 before). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentPanelIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DocumentProcessingPipeline pipeline;

    @Test
    @DisplayName("the processing panel of a classified document answers, with the words that made the rule fire")
    void panelOfAClassifiedDocument() throws Exception {
        String email = "panel-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"P\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andReturn();
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andReturn().getResponse().getCookie("bc_session");

        String text = "Rechnung Nr. 2026-0815\nRechnungsbetrag 119,00 EUR\nZahlbar bis 20.10.2026\n";
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "Rechnung.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8))).with(csrf()).cookie(cookie))
                .andExpect(status().isCreated()).andReturn();
        String id = JsonPath.read(upload.getResponse().getContentAsString(), "$.document.id");

        UUID tenant = jdbc.queryForObject("select tenant_id from documents where id = ?", UUID.class, UUID.fromString(id));
        RequestContext.runAs(tenant, () -> pipeline.advance(UUID.fromString(id)));

        MvcResult panel = mockMvc.perform(get("/api/v1/documents/" + id + "/pipeline").cookie(cookie)).andReturn();
        assertThat(panel.getResponse().getStatus()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(panel.getResponse().getContentAsString(), "$.matchedPassages")).isNotEmpty();
    }
}
