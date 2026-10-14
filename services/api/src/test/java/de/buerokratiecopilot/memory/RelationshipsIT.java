package de.buerokratiecopilot.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/** BM-02: relationships carry a status and the evidence, a person decides, and a tenant sees only its own. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RelationshipsIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;

    private record Session(UUID tenantId, Cookie cookie) {
    }

    private Session newTenant() throws Exception {
        String email = "rel-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Rel Test","accountType":"COMPANY"}
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
        return new Session(tenantId, login.getResponse().getCookie("bc_session"));
    }

    private String upload(Session s, byte[] bytes) throws Exception {
        MvcResult r = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "Rechnung.pdf", "application/pdf", bytes))
                        .with(csrf()).cookie(s.cookie()))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.document.id");
    }

    @Test
    @DisplayName("a copy is related to its original with the reason; a person can reject it, and another tenant cannot see or decide it")
    void duplicateRelationIsListedDecidedAndIsolated() throws Exception {
        Session a = newTenant();
        Session b = newTenant();
        byte[] pdf = TestDocuments.pdf("Rechnung Nr. 4711\nBruttobetrag: 119,00 EUR\nZahlbar bis: 31.03.2026\n");
        String original = upload(a, pdf);
        String copy = upload(a, pdf);

        String list = mockMvc.perform(get("/api/v1/memory/documents/" + copy + "/relationships").cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(list, "$[0].type")).isEqualTo("DUPLICATE");
        assertThat((Object) JsonPath.read(list, "$[0].status")).isEqualTo("CONFIRMED");
        assertThat((Object) JsonPath.read(list, "$[0].other_document_id")).isEqualTo(original);
        assertThat(list).contains("identical content hash");
        String relationId = JsonPath.read(list, "$[0].id");

        // Another tenant sees nothing and cannot decide.
        String other = mockMvc.perform(get("/api/v1/memory/documents/" + copy + "/relationships").cookie(b.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(other).isEqualTo("[]");
        mockMvc.perform(post("/api/v1/memory/relationships/" + relationId + "/reject").with(csrf()).cookie(b.cookie()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/memory/relationships/" + relationId + "/reject").with(csrf()).cookie(a.cookie()))
                .andExpect(status().isOk());
        String rejected = mockMvc.perform(get("/api/v1/memory/documents/" + copy + "/relationships?status=REJECTED")
                        .cookie(a.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(rejected, "$[0].status")).isEqualTo("REJECTED");
        Integer audited = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from audit_events where tenant_id = ? and action = 'RELATIONSHIP_REJECTED'",
                Integer.class, a.tenantId()));
        assertThat(audited).isEqualTo(1);
    }
}
