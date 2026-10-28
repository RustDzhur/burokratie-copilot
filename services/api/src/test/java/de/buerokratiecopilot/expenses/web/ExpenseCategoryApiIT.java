package de.buerokratiecopilot.expenses.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The two endpoints that make the catalogue usable: the tree a screen renders and the explicit
 * categorisation of a document.
 *
 * <p>The test registers a real account and logs in, because the permissions are part of the contract: an
 * owner may administer the catalogue, and a document may only be categorised by a role that may review
 * documents. A service call would prove neither.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpenseCategoryApiIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId) {
    }

    private Session session() throws Exception {
        String email = "expense-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!","displayName":"M",
                                 "accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(registered.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(registered.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("bc_session");
        return new Session(cookie, tenantId);
    }

    private UUID document(Session session) {
        UUID id = UUID.randomUUID();
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status, extracted_text,
                                       sender, document_date)
                values (?, ?, 'obi.pdf', 'obi.pdf', 'application/pdf', 100, ?, 'UPLOAD', 'READY',
                        'NOT_REQUIRED', 'OBI Baumarkt Zement 39,90 €', 'OBI', ?)
                """, id, session.tenantId(), "%064x".formatted(ThreadLocalRandom.current().nextLong()),
                LocalDate.of(2026, 3, 15)));
        return id;
    }

    @Test
    @DisplayName("the catalogue tree is readable and carries names, DATEV accounts and folders")
    void treeIsReadable() throws Exception {
        Session session = session();

        MvcResult result = mockMvc.perform(get("/api/v1/expense-categories").cookie(session.cookie()))
                .andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> tree = JsonPath.read(result.getResponse().getContentAsString(), "$");

        Map<String, Object> material = tree.stream().filter(node -> "material".equals(node.get("code")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) material.get("children");
        Map<String, Object> goods = children.stream().filter(node -> "material.waren".equals(node.get("code")))
                .findFirst().orElseThrow();

        assertThat(goods.get("folderPath")).isEqualTo("/Belege/{year}/Material/Waren");
        assertThat(goods.get("name")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKeys("de", "en", "ru");
        assertThat(goods.get("datev")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("skr03", "3400").containsEntry("skr04", "5400");
    }

    @Test
    @DisplayName("a document is categorised through the API and the DTO names the category and the folder")
    void documentIsCategorisedThroughTheApi() throws Exception {
        Session session = session();
        UUID documentId = document(session);

        mockMvc.perform(post("/api/v1/documents/" + documentId + "/categorize").cookie(session.cookie())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryCode\":\"material.waren\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.document.folderPath").value("/Belege/2026/Material/Waren"))
                .andExpect(jsonPath("$.document.expenseCategory.code").value("material.waren"))
                .andExpect(jsonPath("$.document.expenseCategory.businessExpense").value(true));

        mockMvc.perform(get("/api/v1/documents/" + documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expenseCategory.code").value("material.waren"))
                .andExpect(jsonPath("$.folderPath").value("/Belege/2026/Material/Waren"));
    }

    @Test
    @DisplayName("an owner may create a category and add a rule to it")
    void ownerExtendsTheCatalogue() throws Exception {
        Session session = session();
        String code = "material.test_" + UUID.randomUUID().toString().substring(0, 8);
        String body = """
                {"code":"%s","parentCode":"material","nameDe":"Testmaterial","nameEn":"Test material",
                 "nameRu":"Тестовый материал","descriptionDe":"Nur für den Test","datevSkr03":"4980",
                 "datevSkr04":"6850","folderPath":"/Belege/{year}/Material/Test","sortOrder":999}
                """.formatted(code);

        mockMvc.perform(post("/api/v1/expense-categories").cookie(session.cookie()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.isBusinessExpense").value(true));

        mockMvc.perform(post("/api/v1/expense-categories/" + code + "/rules").cookie(session.cookie())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"SENDER\",\"pattern\":\"testlieferant\",\"weight\":0.9}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ruleType").value("SENDER"));

        mockMvc.perform(get("/api/v1/expense-categories/" + code + "/rules").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pattern").value("testlieferant"));
    }
}
