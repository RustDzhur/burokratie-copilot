package de.buerokratiecopilot.advisor.web;

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
 * Advisor access over HTTP (spec §3.2; H-002).
 *
 * <p>What this checks beyond the routes: <strong>an invitation grants nothing until the client accepts
 * it</strong>, the accepting account comes from the session rather than the body, and ending an arrangement
 * <strong>stops access at once</strong> while keeping the record.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdvisorControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register(String kind) throws Exception {
        // Registration creates a Kanzlei account, whatever the body says: that is the one kind of cabinet the
        // product still offers. The advisor service, which predates that decision, still requires the
        // EXTERNAL_REPRESENTATIVE account type for the party that manages clients, so the fixture sets it
        // directly - the HTTP surface this test covers is the arrangement itself, not the account type.
        String email = "adv-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"%s"}
                                """.formatted(email, kind)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));
        if (kind.contains("Berater")) {
            jdbc.update("update tenants set account_type = 'EXTERNAL_REPRESENTATIVE', updated_at = now() where id = ?",
                    tenantId);
        }

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

    private UUID invite(Session advisor, Session client) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/advisor/arrangements").with(csrf())
                        .cookie(advisor.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientTenantId":"%s","accessLevel":"READ","scope":"DOCUMENTS",
                                 "label":"Steuerberatung"}
                                """.formatted(client.tenantId())))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "id"));
    }

    // ---------------------------------------------------------------- consent

    @Test
    @DisplayName("an invitation grants nothing until the client accepts it")
    void anInvitationGrantsNothingOnItsOwn() throws Exception {
        Session advisor = register("Berater");
        Session client = register("Mandant");

        MvcResult created = mockMvc.perform(post("/api/v1/advisor/arrangements").with(csrf())
                        .cookie(advisor.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientTenantId":"%s","accessLevel":"READ","scope":"DOCUMENTS",
                                 "label":"Steuerberatung"}
                                """.formatted(client.tenantId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.advisorTenantId").value(advisor.tenantId().toString()))
                .andExpect(jsonPath("$.clientTenantId").value(client.tenantId().toString()))
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();

        // The two-step shape is the point: a relationship in which one party gives another access to their
        // documents is not one that party can create alone.
        mockMvc.perform(get("/api/v1/advisor/access/{id}", client.tenantId()).cookie(advisor.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayAccess").value(false))
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("after the client accepts, the advisor may read")
    void afterAcceptanceTheAccessExists() throws Exception {
        Session advisor = register("Berater");
        Session client = register("Mandant");
        UUID arrangementId = invite(advisor, client);

        // The client accepts, from their own session.
        mockMvc.perform(post("/api/v1/advisor/arrangements/{id}/accept", arrangementId).with(csrf())
                        .cookie(client.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").isNotEmpty());

        mockMvc.perform(get("/api/v1/advisor/access/{id}", client.tenantId()).cookie(advisor.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayAccess").value(true));
    }

    @Test
    @DisplayName("the advisor's account comes from the session and cannot be named in the body")
    void theAdvisorComesFromTheSession() throws Exception {
        Session advisor = register("Berater");
        Session other = register("Anderer Berater");
        Session client = register("Mandant");

        // The invitation names the client, never the inviter: otherwise one advisor could invite clients on
        // behalf of another, and the client could not tell whose request they were accepting.
        MvcResult created = mockMvc.perform(post("/api/v1/advisor/arrangements").with(csrf())
                        .cookie(advisor.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientTenantId":"%s","advisorTenantId":"%s","accessLevel":"READ"}
                                """.formatted(client.tenantId(), other.tenantId())))
                .andExpect(status().isCreated())
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(JsonPath.read(
                        created.getResponse().getContentAsString(), "$.advisorTenantId").toString())
                .as("the arrangement records the session's account, not the identifier in the body")
                .isEqualTo(advisor.tenantId().toString());
    }

    @Test
    @DisplayName("a client cannot accept somebody else's invitation")
    void aClientCannotAcceptSomebodyElsesInvitation() throws Exception {
        Session advisor = register("Berater");
        Session client = register("Mandant");
        Session stranger = register("Fremder");
        UUID arrangementId = invite(advisor, client);

        // Accepting is the client's act; a third account accepting would be an arrangement nobody agreed to.
        mockMvc.perform(post("/api/v1/advisor/arrangements/{id}/accept", arrangementId).with(csrf())
                        .cookie(stranger.cookie()))
                .andExpect(status().is4xxClientError());

        mockMvc.perform(get("/api/v1/advisor/access/{id}", client.tenantId()).cookie(advisor.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayAccess").value(false));
    }

    // ---------------------------------------------------------------- ending it

    @Test
    @DisplayName("ending an arrangement stops access at once and keeps the record")
    void endingStopsAccessAtOnce() throws Exception {
        Session advisor = register("Berater");
        Session client = register("Mandant");
        UUID arrangementId = invite(advisor, client);
        mockMvc.perform(post("/api/v1/advisor/arrangements/{id}/accept", arrangementId).with(csrf())
                        .cookie(client.cookie()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/advisor/arrangements/{id}/end", arrangementId).with(csrf())
                        .cookie(advisor.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Mandat beendet\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/advisor/access/{id}", client.tenantId()).cookie(advisor.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayAccess").value(false));

        // A document that was seen is a document that was seen: the arrangement is not erased, it is marked
        // as ended. The read names the tenant, because advisor_clients is protected by a policy and a query
        // without a context sees nothing - which is how this assertion first failed.
        Integer kept = RequestContext.runAs(advisor.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from advisor_clients where id = ?
                """, Integer.class, arrangementId));
        org.assertj.core.api.Assertions.assertThat(kept)
                .as("ending an arrangement must not erase the record of it")
                .isEqualTo(1);
        String status = RequestContext.runAs(advisor.tenantId(), () -> jdbc.queryForObject(
                "select status from advisor_clients where id = ?", String.class, arrangementId));
        org.assertj.core.api.Assertions.assertThat(status)
                .as("the arrangement is marked ended rather than removed")
                .isEqualTo("ENDED");
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("an advisor cannot invite themselves")
    void anAdvisorCannotInviteThemselves() throws Exception {
        Session advisor = register("Berater");

        mockMvc.perform(post("/api/v1/advisor/arrangements").with(csrf())
                        .cookie(advisor.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientTenantId\":\"%s\",\"accessLevel\":\"READ\"}"
                                .formatted(advisor.tenantId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an invitation without a client is refused")
    void anInvitationWithoutAClientIsRefused() throws Exception {
        Session advisor = register("Berater");

        mockMvc.perform(post("/api/v1/advisor/arrangements").with(csrf())
                        .cookie(advisor.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessLevel\":\"READ\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("without a session nothing is read or arranged")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/advisor/access/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/advisor/arrangements").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientTenantId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/advisor/arrangements/{id}/accept", UUID.randomUUID()).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an account with no arrangement has no access")
    void noArrangementMeansNoAccess() throws Exception {
        Session advisor = register("Berater");
        Session client = register("Mandant");

        mockMvc.perform(get("/api/v1/advisor/access/{id}", client.tenantId()).cookie(advisor.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayAccess").value(false));
    }

    @Test
    @DisplayName("the staff permissions exist and are held, which is what makes these reachable")
    void theStaffPermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('staff.view','staff.invite','staff.remove')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the staff permissions must be granted to OWNER and ADMIN, or these are dead")
                .isGreaterThan(0);
    }
}
