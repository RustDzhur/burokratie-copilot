package de.buerokratiecopilot.documents.taxonomy.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The taxonomy endpoints, over HTTP (spec §6, §20; B-008b).
 *
 * <p>The service layer was verified with twenty tests; what this adds is the part those cannot reach — that
 * the routes exist where they are claimed to be, that the request and response shapes are what an interface
 * would send and receive, and that an unauthenticated caller is refused before anything is read.</p>
 *
 * <p>A service test can pass while the endpoint is on the wrong path, or named differently, or not
 * registered at all. That is the gap this closes.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaxonomyAdminControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "tax-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Taxonomie Test","accountType":"COMPANY"}
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

    private String uniqueCode() {
        return "typ_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A type created over HTTP, and its identifier as the response gave it. */
    private UUID createType(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/document-types").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","category":"FINANCE","nameDe":"Testtyp","sortOrder":10}
                                """.formatted(uniqueCode())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));
    }

    // ---------------------------------------------------------------- the routes exist

    @Test
    @DisplayName("the catalogue is served where an interface expects it")
    void theCatalogueIsServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/document-types/catalogue").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.ruleTypes").isArray());
    }

    @Test
    @DisplayName("the types are listed")
    void theTypesAreListed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/document-types").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("a type is created and comes back with its identifier")
    void aTypeIsCreated() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/document-types").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","category":"GOVERNMENT","nameDe":"Bescheid"}
                                """.formatted(uniqueCode())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.category").value("GOVERNMENT"))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    @DisplayName("a type is switched off over HTTP")
    void aTypeIsSwitchedOff() throws Exception {
        Session session = register();
        UUID typeId = createType(session);

        mockMvc.perform(patch("/api/v1/document-types/{id}", typeId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    // ---------------------------------------------------------------- the rule that waits

    @Test
    @DisplayName("a keyword rule takes effect at once over HTTP")
    void aKeywordRuleIsActiveOverHttp() throws Exception {
        Session session = register();
        UUID typeId = createType(session);

        mockMvc.perform(post("/api/v1/document-types/{id}/rules", typeId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"KEYWORD\",\"pattern\":\"Rechnung\",\"weight\":0.8}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.verified").value(true));
    }

    @Test
    @DisplayName("a pattern rule is created inactive and says why")
    void aPatternRuleWaitsOverHttp() throws Exception {
        Session session = register();
        UUID typeId = createType(session);

        // The rule that carries the acceptance criterion, seen from where an interface sits.
        mockMvc.perform(post("/api/v1/document-types/{id}/rules", typeId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"REGEX\",\"pattern\":\"RE-\\\\d+\",\"weight\":0.9}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("the rules waiting for a second person are served")
    void awaitingVerificationIsServed() throws Exception {
        Session session = register();
        UUID typeId = createType(session);
        mockMvc.perform(post("/api/v1/document-types/{id}/rules", typeId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"REGEX\",\"pattern\":\"RE-\\\\d+\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/document-types/awaiting-verification").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].rule_type").value("REGEX"));
    }

    @Test
    @DisplayName("a rule is removed and the endpoint says so with no content")
    void aRuleIsRemoved() throws Exception {
        Session session = register();
        UUID typeId = createType(session);
        MvcResult created = mockMvc.perform(post("/api/v1/document-types/{id}/rules", typeId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"KEYWORD\",\"pattern\":\"Mahnung\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        UUID ruleId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.id"));

        mockMvc.perform(delete("/api/v1/document-types/{id}/rules/{ruleId}", typeId, ruleId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the changes are served, which is how a mis-filed document is traced back")
    void theHistoryIsServed() throws Exception {
        Session session = register();
        createType(session);

        mockMvc.perform(get("/api/v1/document-types/history").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("DOCUMENT_TYPE_CREATED"));
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("an unauthenticated caller is refused before anything is read")
    void withoutASessionNothingIsRead() throws Exception {
        mockMvc.perform(get("/api/v1/document-types"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/document-types/catalogue"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/document-types").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x1\",\"category\":\"OTHER\",\"nameDe\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown category is refused with a sentence, not a database error")
    void anUnknownCategoryIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/document-types").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"category\":\"SPACE\",\"nameDe\":\"x\"}"
                                .formatted(uniqueCode())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a code that already exists is refused over HTTP as well")
    void aDuplicateCodeIsRefused() throws Exception {
        Session session = register();
        String code = uniqueCode();
        mockMvc.perform(post("/api/v1/document-types").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"category\":\"OTHER\",\"nameDe\":\"x\"}"
                                .formatted(code)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/document-types").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"category\":\"OTHER\",\"nameDe\":\"y\"}"
                                .formatted(code)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a broken pattern is refused at the endpoint, before it is stored")
    void aBrokenPatternIsRefused() throws Exception {
        Session session = register();
        UUID typeId = createType(session);

        mockMvc.perform(post("/api/v1/document-types/{id}/rules", typeId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ruleType\":\"REGEX\",\"pattern\":\"RE-(\\\\d+\"}"))
                .andExpect(status().isBadRequest());
    }
}
