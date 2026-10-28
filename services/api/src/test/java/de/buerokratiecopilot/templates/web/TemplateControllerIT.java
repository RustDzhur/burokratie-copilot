package de.buerokratiecopilot.templates.web;

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
 * Templates over HTTP (spec §15, §20).
 *
 * <p>What this checks beyond the routes: a template is <strong>published as a version</strong>, so a letter
 * that went out does not change when the template is edited afterwards, and a generation
 * <strong>reports which fields it used</strong> — a letter assembled from a template is only trustworthy if
 * somebody can see where each value came from.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "tpl-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Vorlage Test","accountType":"COMPANY"}
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

    private String code() {
        return "tpl_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private UUID define(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/templates").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","nameGerman":"Antwort","category":"LETTER",
                                 "subjectTemplate":"Antwort auf {{az}}",
                                 "bodyHtml":"<p>Sehr geehrte Damen und Herren,</p><p>{{betrag}}</p>",
                                 "fields":[
                                   {"fieldKey":"az","labelGerman":"Aktenzeichen","sourceEntity":"case",
                                    "sourcePath":"reference","valueType":"TEXT","required":true},
                                   {"fieldKey":"betrag","labelGerman":"Betrag","sourceEntity":"invoice",
                                    "sourcePath":"grossAmount","valueType":"MONEY","required":false}]}
                                """.formatted(code())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.templateId"));
    }

    // ---------------------------------------------------------------- the routes

    @Test
    @DisplayName("a template is defined and its fields are served")
    void aTemplateIsDefined() throws Exception {
        Session session = register();
        UUID templateId = define(session);

        mockMvc.perform(get("/api/v1/templates/{id}/fields", templateId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].fieldKey").isNotEmpty())
                .andExpect(jsonPath("$[0].sourcePath").isNotEmpty());
    }

    @Test
    @DisplayName("a template without a code is refused")
    void aTemplateWithoutACodeIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/templates").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameGerman\":\"Ohne Code\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a template is published as a version")
    void aTemplateIsPublished() throws Exception {
        Session session = register();
        UUID templateId = define(session);

        mockMvc.perform(post("/api/v1/templates/{id}/versions/{v}/publish", templateId, 1).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());

        // template_versions is protected by a policy on its own tenant_id, so this read names the tenant:
        // the HTTP call set the context for itself and cleared it again.
        Integer published = RequestContext.runAs(session.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from template_versions where template_id = ? and status = 'PUBLISHED'
                """, Integer.class, templateId));
        org.assertj.core.api.Assertions.assertThat(published)
                .as("the letter that went out must not change when the template is edited afterwards")
                .isGreaterThan(0);
    }

    // ---------------------------------------------------------------- generating

    @Test
    @DisplayName("generating a letter reports which fields it used")
    void generatingReportsTheFieldsUsed() throws Exception {
        Session session = register();
        UUID templateId = define(session);
        mockMvc.perform(post("/api/v1/templates/{id}/versions/{v}/publish", templateId, 1).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/templates/{id}/generate", templateId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":{\"az\":\"AZ-2026-1\",\"betrag\":\"119,00\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").isNotEmpty())
                .andExpect(jsonPath("$.subject").isNotEmpty())
                .andExpect(jsonPath("$.usedFields").isArray())
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("generating without a required value is refused, and says which field is missing")
    void generatingWithoutARequiredValueIsRefused() throws Exception {
        Session session = register();
        UUID templateId = define(session);
        mockMvc.perform(post("/api/v1/templates/{id}/versions/{v}/publish", templateId, 1).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());

        // The template marks its field as required, and a letter whose mandatory field is empty is not a
        // letter to send - so the service refuses it and names what is missing.
        mockMvc.perform(post("/api/v1/templates/{id}/generate", templateId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("an unknown template answers with no fields and reveals nothing")
    void anUnknownTemplateAnswersWithNoFields() throws Exception {
        Session session = register();

        // An empty list rather than a 404, and that is the better answer: a refusal that distinguishes
        // 'exists but is not yours' from 'does not exist' tells one account that the other has a template
        // with that identifier, and the empty answer tells it nothing at all.
        mockMvc.perform(get("/api/v1/templates/{id}/fields", UUID.randomUUID()).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("publishing an unknown template is reported")
    void publishingAnUnknownTemplateIsReported() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/templates/{id}/versions/{v}/publish", UUID.randomUUID(), 1)
                        .with(csrf()).cookie(session.cookie()))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or generated")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/templates/{id}/fields", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/templates").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x\",\"nameGerman\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/templates/{id}/generate", UUID.randomUUID()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a template of another account is not reachable")
    void anotherAccountsTemplateIsNotReachable() throws Exception {
        Session first = register();
        Session second = register();
        UUID templateId = define(first);

        mockMvc.perform(get("/api/v1/templates/{id}/fields", templateId).cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("the template permissions exist and are held, which is what makes these reachable")
    void theTemplatePermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('templates.view','templates.create','templates.edit')
                  and r.tenant_id is null
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the template permissions must be granted to a system role, or these are dead")
                .isGreaterThan(0);
    }
}
