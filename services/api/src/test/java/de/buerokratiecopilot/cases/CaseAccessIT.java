package de.buerokratiecopilot.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies that case access is limited to the people who take part in it (spec §11.1, §12 acceptance).
 *
 * <p>This is the acceptance criterion with real consequences: a case holds a tax matter or a
 * termination, and "everybody with the same job title can read it" is how a platform loses a
 * customer's trust. The test therefore drives a colleague through the whole ladder — no access,
 * read-only, write, revoked — and checks each step through the API.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseAccessIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private CaseTestSupport support;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register(String prefix) throws Exception {
        String email = prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Zugriff Test","accountType":"COMPANY"}
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
        return new Session(login(email), tenantId, userId);
    }

    private Cookie login(String email) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return login.getResponse().getCookie("bc_session");
    }

    /** A colleague in the same tenant, with the same roles, who logs in for real. */
    private Session colleague(Session owner) throws Exception {
        String email = "kollege-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        UUID userId = support.colleagueIn(owner.tenantId(), owner.userId(), email);
        return new Session(login(email), owner.tenantId(), userId);
    }

    private String openCase(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cases").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Steuerunterlagen 2025\",\"caseTypeCode\":\"steuerunterlagen\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    private String addParticipant(Session actor, String caseId, Session target, String level)
            throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/cases/{id}/participants", caseId).with(csrf())
                        .cookie(actor.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"PARTICIPANT","accessLevel":"%s"}
                                """.formatted(target.userId(), level)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    @DisplayName("a colleague who does not take part in a case cannot see it at all")
    void colleagueWithoutAccessSeesNothing() throws Exception {
        Session owner = register("owner");
        Session other = colleague(owner);
        String caseId = openCase(owner);

        // The same answer as for a case that does not exist: the API must not confirm that a matter
        // the user may not see exists at all.
        mockMvc.perform(get("/api/v1/cases/{id}", caseId).cookie(other.cookie()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/cases").cookie(other.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + caseId + "')]").isEmpty());
    }

    @Test
    @DisplayName("the owner always sees the case, and it appears in their list")
    void ownerSeesOwnCase() throws Exception {
        Session owner = register("owner");
        String caseId = openCase(owner);

        mockMvc.perform(get("/api/v1/cases").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + caseId + "')]").isNotEmpty());
    }

    @Test
    @DisplayName("read access lets a colleague see the case but not change who else may see it")
    void readAccessCannotGrantAccess() throws Exception {
        Session owner = register("owner");
        Session other = colleague(owner);
        Session third = colleague(owner);
        String caseId = openCase(owner);
        addParticipant(owner, caseId, other, "READ");

        mockMvc.perform(get("/api/v1/cases/{id}", caseId).cookie(other.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseRecord.id").value(caseId));

        mockMvc.perform(post("/api/v1/cases/{id}/participants", caseId).with(csrf())
                        .cookie(other.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"PARTICIPANT","accessLevel":"READ"}
                                """.formatted(third.userId())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("write access lets a colleague grant access in turn")
    void writeAccessCanGrantAccess() throws Exception {
        Session owner = register("owner");
        Session other = colleague(owner);
        Session third = colleague(owner);
        String caseId = openCase(owner);
        String participantId = addParticipant(owner, caseId, other, "READ");

        mockMvc.perform(patch("/api/v1/cases/{id}/participants/{pid}", caseId, participantId).with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessLevel\":\"WRITE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessLevel").value("WRITE"));

        addParticipant(other, caseId, third, "READ");
        mockMvc.perform(get("/api/v1/cases/{id}", caseId).cookie(third.cookie()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a revoked grant really removes access, and the grant itself stays on record")
    void revokedGrantRemovesAccess() throws Exception {
        Session owner = register("owner");
        Session other = colleague(owner);
        String caseId = openCase(owner);
        String participantId = addParticipant(owner, caseId, other, "WRITE");

        mockMvc.perform(delete("/api/v1/cases/{id}/participants/{pid}", caseId, participantId).with(csrf())
                        .cookie(owner.cookie()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/cases/{id}", caseId).cookie(other.cookie()))
                .andExpect(status().isNotFound());

        // The record survives: "who could see this in March" must stay answerable.
        mockMvc.perform(get("/api/v1/cases/{id}", caseId).cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants[?(@.id=='" + participantId + "')].active")
                        .value(org.hamcrest.Matchers.contains(false)));
    }

    @Test
    @DisplayName("a case of another tenant is invisible, however the caller asks for it")
    void otherTenantCannotSeeTheCase() throws Exception {
        Session owner = register("owner");
        Session stranger = register("stranger");
        String caseId = openCase(owner);

        mockMvc.perform(get("/api/v1/cases/{id}", caseId).cookie(stranger.cookie()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/cases").cookie(stranger.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + caseId + "')]").isEmpty());
    }

    @Test
    @DisplayName("the owner cannot be removed from their own case")
    void ownerCannotBeRemoved() throws Exception {
        Session owner = register("owner");
        String caseId = openCase(owner);

        String ownerParticipantId = mockMvc.perform(get("/api/v1/cases/{id}", caseId)
                        .cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String participantId = JsonPath.read(ownerParticipantId, "$.participants[0].id");

        mockMvc.perform(delete("/api/v1/cases/{id}/participants/{pid}", caseId, participantId).with(csrf())
                        .cookie(owner.cookie()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the same colleague cannot be added to the same case twice")
    void duplicateParticipantIsRefused() throws Exception {
        Session owner = register("owner");
        Session other = colleague(owner);
        String caseId = openCase(owner);
        addParticipant(owner, caseId, other, "READ");

        mockMvc.perform(post("/api/v1/cases/{id}/participants", caseId).with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"%s","role":"PARTICIPANT","accessLevel":"WRITE"}
                                """.formatted(other.userId())))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a participant is either a user or an external contact, never both and never neither")
    void participantIsEitherUserOrContact() throws Exception {
        Session owner = register("owner");
        String caseId = openCase(owner);

        mockMvc.perform(post("/api/v1/cases/{id}/participants", caseId).with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"PARTICIPANT\",\"accessLevel\":\"READ\"}"))
                .andExpect(status().is4xxClientError());

        // An external advisor is recorded without inventing a user account for them.
        UUID advisor = support.contactIn(owner.tenantId(), "Rechtsanwalt Muster");
        mockMvc.perform(post("/api/v1/cases/{id}/participants", caseId).with(csrf())
                        .cookie(owner.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactId":"%s","role":"LEGAL_ADVISOR","accessLevel":"READ"}
                                """.formatted(advisor)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("LEGAL_ADVISOR"))
                .andExpect(jsonPath("$.userId").doesNotExist());
    }
}
