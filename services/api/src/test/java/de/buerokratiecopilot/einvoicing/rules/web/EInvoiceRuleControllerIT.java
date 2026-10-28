package de.buerokratiecopilot.einvoicing.rules.web;

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
 * E-invoicing rulesets over HTTP (spec §23).
 *
 * <p><strong>The assertion this test exists for:</strong> the platform ships no rules, and until a customer
 * publishes one, every validation runs against nothing. The answer has to say that in words, because a
 * validation against no ruleset is not a statement about conformity — and that is exactly the gap the
 * electronic invoice records have carried since their table was created.</p>
 *
 * <p>The rules used here are the test's own and are named as such. They are not a claim about any real
 * specification: writing a real rule would be inventing a legal requirement, which this work does not do.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EInvoiceRuleControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "rul-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Regelwerk Test","accountType":"COMPANY"}
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

    /** A specification name invented for the test, so that no real one is claimed or overwritten. */
    private String specification() {
        return "TESTSPEC-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private String body(String specification) {
        // The rules are the test's own and are not a claim about any real specification.
        return """
                {"specification":"%s","description":"Regeln des Kunden","source":"Kundenangabe",
                 "rules":[{"code":"T-1","severity":"ERROR","description":"Testregel eins",
                           "reference":"Kundenangabe","sortOrder":1},
                          {"code":"T-2","severity":"WARNING","description":"Testregel zwei",
                           "sortOrder":2}]}
                """.formatted(specification);
    }

    // ---------------------------------------------------------------- the honest flag

    @Test
    @DisplayName("the severities a rule may carry are served")
    void theSeveritiesAreServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/e-invoicing/rulesets/severities").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").isNotEmpty());
    }

    @Test
    @DisplayName("a specification nobody has published for is validated against nothing, and says so")
    void anUnpublishedSpecificationSaysItIsValidatedAgainstNothing() throws Exception {
        Session session = register();
        String specification = specification();

        // A validation against no ruleset is not a statement about conformity; the answer has to say that
        // rather than leaving it to be inferred from empty fields.
        MvcResult result = mockMvc.perform(get("/api/v1/e-invoicing/rulesets/in-force/{spec}", specification)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false))
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(
                        JsonPath.read(result.getResponse().getContentAsString(), "$.note").toString())
                .as("the consequence has to be said, not inferred from a boolean")
                .contains("keine Fassung");
    }

    // ---------------------------------------------------------------- publishing

    @Test
    @DisplayName("a ruleset is published with the customer's rules and is then in force")
    void aRulesetIsPublishedAndInForce() throws Exception {
        Session session = register();
        String specification = specification();

        mockMvc.perform(post("/api/v1/e-invoicing/rulesets").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(specification)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rulesetId").isNotEmpty())
                // Normalised to upper case by the register: 'xrechnung' and 'XRECHNUNG' have to be the
                // same specification, or a customer would publish twice and validate against one of them.
                .andExpect(jsonPath("$.specification").value(specification.toUpperCase(
                        java.util.Locale.ROOT)))
                .andExpect(jsonPath("$.ruleCount").value(2))
                // The rules come from the customer; the platform applies them without adopting them.
                .andExpect(jsonPath("$.note").isNotEmpty());

        mockMvc.perform(get("/api/v1/e-invoicing/rulesets/in-force/{spec}", specification)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true))
                .andExpect(jsonPath("$.ruleCount").value(2));

        mockMvc.perform(get("/api/v1/e-invoicing/rulesets/published").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anyPublished").value(true));
    }

    @Test
    @DisplayName("a ruleset without rules is refused, because one says nothing")
    void aRulesetWithoutRulesIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/e-invoicing/rulesets").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"specification\":\"X\",\"rules\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a ruleset without a specification is refused")
    void aRulesetWithoutASpecificationIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/e-invoicing/rulesets").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Ohne Spezifikation\",\"rules\":[{\"code\":\"T-1\","
                                + "\"severity\":\"ERROR\",\"description\":\"x\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown severity is refused by the register rather than stored")
    void anUnknownSeverityIsRefused() throws Exception {
        Session session = register();

        // The severities the register accepts are its own rule; refusing here would be a second copy of it.
        mockMvc.perform(post("/api/v1/e-invoicing/rulesets").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"specification":"X","rules":[{"code":"T-1","severity":"KATASTROPHAL",
                                 "description":"x"}]}
                                """))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------- retiring

    @Test
    @DisplayName("a retired ruleset is withdrawn and what was validated against it stays explainable")
    void retiringWithdrawsAndKeepsTheRecord() throws Exception {
        Session session = register();
        String specification = specification();

        MvcResult created = mockMvc.perform(post("/api/v1/e-invoicing/rulesets").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(specification)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID rulesetId = UUID.fromString(JsonPath.read(
                created.getResponse().getContentAsString(), "$.rulesetId"));

        mockMvc.perform(post("/api/v1/e-invoicing/rulesets/{id}/retire", rulesetId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/e-invoicing/rulesets/in-force/{spec}", specification)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false));

        // An invoice validated against it last year was validated against it: withdrawing the ruleset does
        // not change what was true then.
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select count(*) from e_invoice_rulesets where id = ?
                """, Integer.class, rulesetId))
                .as("retiring a ruleset must not erase it")
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or published")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/e-invoicing/rulesets/published"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/e-invoicing/rulesets/in-force/{spec}", "X"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/e-invoicing/rulesets").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("X")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the settings permissions exist and are held, which is what makes these reachable")
    void theSettingsPermissionsAreHeld() {
        // There is no e_invoice permission; publishing what the platform validates against is an
        // administrative decision, so these routes use settings.* - checked rather than assumed.
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('settings.view','settings.edit')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("settings.view and settings.edit must be granted to OWNER and ADMIN, or these are dead")
                .isEqualTo(4);
    }
}
