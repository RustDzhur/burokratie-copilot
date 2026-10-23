package de.buerokratiecopilot.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The first-run wizard of the Kanzlei edition, §6.1 — three steps, five minutes:
 * <ol>
 *   <li>"Wer sind Sie?" — the name of the office, and nothing else;</li>
 *   <li>"Ihre Mandanten" — three clients by hand, or a CSV file;</li>
 *   <li>"Erste Dokumente" — the wizard is finished and the person lands in the Posteingang.</li>
 * </ol>
 *
 * <p>The test walks the steps in one flow because their order is the requirement: a cabinet whose name was
 * not saved is not set up, and a wizard that calls itself finished after step 1 would send a new office to an
 * empty Posteingang with no clients. What the wizard does in step 3 (upload, demo) is the ordinary document
 * API and is tested where that API lives; here only the transition is checked.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = "bc.test.context=account-setup")
class AccountSetupIT {

    private static final String PASSWORD = "EinSicheresPasswort-2026!";

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    /** A fresh signed-in person with their own cabinet, as registration leaves them. */
    private record Session(Cookie cookie, Cookie xsrf, UUID userId, UUID tenantId) {
    }

    private Session signIn() throws Exception {
        Cookie xsrf = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        String email = "setup-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de";

        MvcResult registration = mvc.perform(post("/api/v1/auth/register")
                        .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","displayName":"Erika Mustermann"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));

        // Registration sends a verification mail; the test activates the address as clicking the link would.
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });

        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        Cookie session = login.getResponse().getCookie("bc_session");
        assertThat(session).as("sign-in issues the session cookie every step below needs").isNotNull();
        return new Session(session, xsrf, userId, tenantId);
    }

    private MockHttpServletRequestBuilder authed(Session session, MockHttpServletRequestBuilder request) {
        return request.cookie(session.cookie(), session.xsrf())
                .header("X-XSRF-TOKEN", session.xsrf().getValue());
    }

    private static String contact(String name, String email, String city) {
        return """
                {"name":"%s","kind":"ORGANISATION","email":"%s","city":"%s"}
                """.formatted(name, email, city);
    }

    // ---------------------------------------------------------------- the three steps

    @Test
    @DisplayName("three steps: the name, the clients, and the cabinet is set up")
    void finishesInThreeSteps() throws Exception {
        Session session = signIn();

        // Step 1: the wizard opens with the name of the office and nothing finished.
        mvc.perform(authed(session, get("/api/v1/setup")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.cabinetName").isNotEmpty())
                .andExpect(jsonPath("$.mandantCount").value(0))
                .andExpect(jsonPath("$.canEditCabinet").value(true));

        mvc.perform(authed(session, put("/api/v1/setup/cabinet"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kanzlei Weber\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cabinetName").value("Kanzlei Weber"));

        // Step 2: three clients by hand - the suggestion of §6.1, not a condition.
        for (String[] client : new String[][] {
                {"Müller Bau GmbH", "info@mueller-bau.de", "Berlin"},
                {"Schmidt Physiotherapie", "physio@schmidt.de", "Hamburg"},
                {"Weber Café UG", "kontakt@weber-cafe.de", "München"}}) {
            mvc.perform(authed(session, post("/api/v1/contacts"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(contact(client[0], client[1], client[2])))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.display_name").value(client[0]))
                    .andExpect(jsonPath("$.city").value(client[2]));
        }
        mvc.perform(authed(session, get("/api/v1/setup")))
                .andExpect(jsonPath("$.mandantCount").value(3))
                .andExpect(jsonPath("$.completed").value(false));

        // Step 3: finished; the person lands in the Posteingang, which is a place the client can open.
        mvc.perform(authed(session, post("/api/v1/setup/complete"))).andExpect(status().isNoContent());

        mvc.perform(authed(session, get("/api/v1/setup")))
                .andExpect(jsonPath("$.completed").value(true));

        mvc.perform(authed(session, get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupCompleted").value(true))
                .andExpect(jsonPath("$.workspaceName").value("Kanzlei Weber"));

        // The name is the cabinet's, not only a field of the wizard: tenant and default workspace carry it.
        assertThat(jdbc.queryForObject("select name from tenants where id = ?", String.class,
                session.tenantId())).isEqualTo("Kanzlei Weber");
        assertThat(jdbc.queryForObject("""
                select name from workspaces where tenant_id = ? and is_default and deleted_at is null
                """, String.class, session.tenantId())).isEqualTo("Kanzlei Weber");
        assertThat(jdbc.queryForObject("select setup_completed_at from tenants where id = ?",
                java.sql.Timestamp.class, session.tenantId())).isNotNull();
    }

    @Test
    @DisplayName("step 1 is one mandatory text field: no name, no progress")
    void theCabinetNameIsTheOneMandatoryField() throws Exception {
        Session session = signIn();

        mvc.perform(authed(session, put("/api/v1/setup/cabinet"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(session, put("/api/v1/setup/cabinet"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(authed(session, put("/api/v1/setup/cabinet"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "x".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());

        // Nothing was written by the refusals: the cabinet is still the one registration created.
        mvc.perform(authed(session, get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.setupCompleted").value(false));
        assertThat(jdbc.queryForObject("select setup_completed_at from tenants where id = ?",
                java.sql.Timestamp.class, session.tenantId())).isNull();
    }

    // ---------------------------------------------------------------- the CSV import

    @Test
    @DisplayName("a CSV file creates clients and reports every line it did not create, with the reason")
    void csvImportCreatesClientsAndReportsSkippedLines() throws Exception {
        Session session = signIn();
        mvc.perform(authed(session, post("/api/v1/contacts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(contact("Müller Bau GmbH", "info@mueller-bau.de", "Berlin")))
                .andExpect(status().isCreated());

        String csv = """
                name;email;city
                Müller Bau GmbH;info@mueller-bau.de;Berlin
                Schmidt Physiotherapie;physio@schmidt.de;Hamburg
                ;leer@example.de;Köln
                Weber Café UG;kein-at-zeichen;München
                Weber Café UG;kontakt@weber-cafe.de;München
                """;
        mvc.perform(authed(session, post("/api/v1/contacts/import"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(java.util.Map.of("content", csv))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.skipped").value(3))
                // The existing client is not created twice; the two malformed lines say what is wrong.
                .andExpect(jsonPath("$.lines[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$.lines[0].reason").value("Der Mandant existiert bereits"))
                .andExpect(jsonPath("$.lines[1].status").value("CREATED"))
                .andExpect(jsonPath("$.lines[2].reason").value("Der Name fehlt"))
                .andExpect(jsonPath("$.lines[3].reason").isNotEmpty())
                .andExpect(jsonPath("$.lines[4].status").value("CREATED"));

        assertThat(jdbc.queryForObject("""
                select count(*) from contacts where tenant_id = ? and deleted_at is null
                """, Integer.class, session.tenantId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select a.city from contact_addresses a join contacts c on c.id = a.contact_id
                where c.tenant_id = ? and c.display_name = 'Schmidt Physiotherapie'
                """, String.class, session.tenantId())).isEqualTo("Hamburg");

        // The import is idempotent by name: a second run of the same file creates nothing.
        MvcResult second = mvc.perform(authed(session, post("/api/v1/contacts/import"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"Schmidt Physiotherapie;physio@schmidt.de;Hamburg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(1))
                .andReturn();
        assertThat(second.getResponse().getContentAsString()).contains("existiert bereits");
    }

    // ---------------------------------------------------------------- the old flow is gone

    @Test
    @DisplayName("the routes of the removed flow answer 404, not an empty success")
    void theOldRoutesAreGone() throws Exception {
        mvc.perform(get("/api/v1/public/onboarding/status")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/onboarding/by-slug/muster-gmbh")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/onboarding").contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"muster-gmbh\",\"accountName\":\"Muster GmbH\","
                        + "\"adminEmail\":\"a@example.de\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/onboarding/bootstrap").contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\":\"muster-gmbh\",\"accountName\":\"Muster GmbH\","
                        + "\"adminEmail\":\"a@example.de\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/public/onboarding/bootstrap/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"bc_1\",\"password\":\"EinSicheresPasswort-2026!\"}"))
                .andExpect(status().isNotFound());

        Session session = signIn();
        mvc.perform(authed(session, post("/api/v1/auth/onboarding"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceName\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
