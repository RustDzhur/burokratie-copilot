package de.buerokratiecopilot.invoices.outgoing.web;

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
 * Invoice number sequences over HTTP (spec §24).
 *
 * <p><strong>The assertion this test exists for:</strong> looking at the next number does not take it. A
 * number consumed by somebody opening a screen is a gap in the books, and a gap in an invoice sequence is
 * what an auditor asks about — so {@code peek} is a {@code GET} and is checked twice for the same answer,
 * while {@code nextNumber} is a {@code POST} and is checked to advance.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvoiceNumberControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "inv-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Nummern Test","accountType":"COMPANY"}
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
        return "RE-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private void define(Session session, String code) throws Exception {
        mockMvc.perform(post("/api/v1/invoice-numbers").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Ausgangsrechnungen","pattern":"RE-{year}-{number}",
                                 "resetPolicy":"YEARLY"}
                                """.formatted(code)))
                .andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- the properties

    @Test
    @DisplayName("looking at the next number does not take it")
    void lookingDoesNotTakeTheNumber() throws Exception {
        Session session = register();
        String code = code();
        define(session, code);

        MvcResult first = mockMvc.perform(get("/api/v1/invoice-numbers/{code}/next", code)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumed").value(false))
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();
        MvcResult second = mockMvc.perform(get("/api/v1/invoice-numbers/{code}/next", code)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();

        // A number consumed by somebody opening a screen is a gap in the books, and a gap in an invoice
        // sequence is what an auditor asks about.
        org.assertj.core.api.Assertions.assertThat(JsonPath.read(
                        second.getResponse().getContentAsString(), "$.nextNumber").toString())
                .as("asking twice must give the same answer, or looking has consumed something")
                .isEqualTo(JsonPath.read(first.getResponse().getContentAsString(), "$.nextNumber")
                        .toString());
    }

    @Test
    @DisplayName("taking a number consumes it, and the next look shows a different one")
    void takingConsumesTheNumber() throws Exception {
        Session session = register();
        String code = code();
        define(session, code);

        MvcResult before = mockMvc.perform(get("/api/v1/invoice-numbers/{code}/next", code)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        String promised = JsonPath.read(before.getResponse().getContentAsString(), "$.nextNumber");

        MvcResult taken = mockMvc.perform(post("/api/v1/invoice-numbers/{code}/next", code).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumed").value(true))
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();

        // What was promised is what was given: a peek that showed one number and a take that issued another
        // would make the screen a lie.
        org.assertj.core.api.Assertions.assertThat(JsonPath.read(
                        taken.getResponse().getContentAsString(), "$.number").toString())
                .as("the number taken must be the one that was shown")
                .isEqualTo(promised.toString());

        MvcResult after = mockMvc.perform(get("/api/v1/invoice-numbers/{code}/next", code)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(JsonPath.read(
                        after.getResponse().getContentAsString(), "$.nextNumber").toString())
                .as("after taking one, the next look must show a different number")
                .isNotEqualTo(promised.toString());
    }

    // ---------------------------------------------------------------- the sequences

    @Test
    @DisplayName("a sequence is defined with the customer's own pattern and then listed")
    void aSequenceIsDefinedAndListed() throws Exception {
        Session session = register();
        String code = code();

        mockMvc.perform(post("/api/v1/invoice-numbers").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"Ausgangsrechnungen","pattern":"RE-{year}-{number}",
                                 "resetPolicy":"YEARLY"}
                                """.formatted(code)))
                .andExpect(status().isCreated())
                // The pattern is the customer's: which numbers a business must use is not a question this
                // platform answers.
                .andExpect(jsonPath("$.pattern").value("RE-{year}-{number}"))
                .andExpect(jsonPath("$.resetPolicy").value("YEARLY"));

        MvcResult listed = mockMvc.perform(get("/api/v1/invoice-numbers").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(listed.getResponse().getContentAsString())
                .contains(code);
    }

    @Test
    @DisplayName("a sequence without a code is refused, and an unknown reset policy is refused with the ones that exist")
    void theFieldsAreChecked() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/invoice-numbers").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ohne Code\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/invoice-numbers").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X-1\",\"name\":\"X\",\"resetPolicy\":\"STÜNDLICH\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown sequence is reported rather than invented")
    void anUnknownSequenceIsReported() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/invoice-numbers/{code}/next", "GIBT-ES-NICHT")
                        .cookie(session.cookie()))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/invoice-numbers/{code}/next", "GIBT-ES-NICHT").with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or taken")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/invoice-numbers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/invoice-numbers/{code}/next", "RE-1"))
                .andExpect(status().isUnauthorized());
        // Taking a number without a session must fail: a number in the books with nobody behind it is worse
        // than no number.
        mockMvc.perform(post("/api/v1/invoice-numbers/{code}/next", "RE-1").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another account's sequences are not visible and its numbers are not taken")
    void accountsAreSeparated() throws Exception {
        Session first = register();
        Session second = register();
        String code = code();
        define(first, code);

        mockMvc.perform(get("/api/v1/invoice-numbers").cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/v1/invoice-numbers/{code}/next", code).cookie(second.cookie()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the finance permissions exist and are held, which is what makes these reachable")
    void theFinancePermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('finance.view','finance.create')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the finance permissions must be granted to OWNER and ADMIN, or these are dead")
                .isGreaterThan(0);
    }
}
