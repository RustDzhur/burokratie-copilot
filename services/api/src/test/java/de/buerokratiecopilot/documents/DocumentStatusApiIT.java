package de.buerokratiecopilot.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/** HD-TEST-03: the API refuses a status the pipeline does not allow, and leaves the document as it was. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DocumentStatusApiIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("an arbitrary status change through the API is refused with 409 and nothing changes")
    void invalidTransitionIsRefused() throws Exception {
        String email = "status-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"S\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("bc_session");
        MvcResult upload = mockMvc.perform(multipart("/api/v1/documents")
                .file(new MockMultipartFile("file", "Brief.pdf", "application/pdf", TestDocuments.pdf("Brief")))
                .with(csrf()).cookie(cookie)).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(JsonPath.read(upload.getResponse().getContentAsString(), "$.document.id"));

        // A document that is archived can only be put on legal hold; anything else is not a step of the pipeline.
        RequestContext.runAs(tenantId, () -> jdbc.update("update documents set processing_status = 'ARCHIVED' where id = ?", id));
        mockMvc.perform(post("/api/v1/documents/" + id + "/status").param("status", "RECEIVED").with(csrf()).cookie(cookie))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/documents/" + id + "/status").param("status", "OCR_PENDING").with(csrf()).cookie(cookie))
                .andExpect(status().isConflict());
        assertThat(RequestContext.runAs(tenantId, () -> jdbc.queryForObject("select processing_status from documents where id = ?", String.class, id)))
                .isEqualTo("ARCHIVED");

        mockMvc.perform(post("/api/v1/documents/" + id + "/status").param("status", "LEGAL_HOLD").with(csrf()).cookie(cookie))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/documents/" + id + "/status").param("status", "NO_SUCH_STATUS").with(csrf()).cookie(cookie))
                .andExpect(status().is4xxClientError());
    }
}
