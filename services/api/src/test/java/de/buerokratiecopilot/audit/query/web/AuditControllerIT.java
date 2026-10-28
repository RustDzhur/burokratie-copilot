package de.buerokratiecopilot.audit.query.web;

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
 * The audit log over HTTP (spec §30).
 *
 * <p><strong>The assertion this test exists for:</strong> the log can be read at all. Until the query service
 * existed the platform wrote audit events from authentication, the document pipeline, retention and taxonomy
 * administration, and nothing could read them — an audit log that cannot be read answers none of the
 * questions it exists for.</p>
 *
 * <p>And the second: <strong>an unknown filter is refused rather than ignored</strong>. A category that does
 * not exist, silently dropped, produces a result that looks like "nothing happened there" — a filter that
 * lies is worse than no filter.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "aud-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Audit Test","accountType":"COMPANY"}
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

    /** One entry, written the way the platform writes them. */
    private void event(Session session, String category, String action, String result, String description) {
        RequestContext.runAs(session.tenantId(), () -> jdbc.update("""
                insert into audit_events (id, tenant_id, actor_user_id, actor_type, actor_label, category,
                                          action, entity_type, description, changes, result, severity)
                values (gen_random_uuid(), ?, ?, 'USER', 'Testperson', ?, ?, 'DOCUMENT', ?, '{}'::jsonb,
                        ?, 'INFO')
                """, session.tenantId(), session.userId(), category, action, description, result));
    }

    // ---------------------------------------------------------------- reading it at all

    @Test
    @DisplayName("a new account already has a log, because registering is itself an event")
    void aNewAccountAlreadyHasALog() throws Exception {
        Session session = register();

        // Not empty, and that is the point: the platform records authentication, and until the query service
        // existed it recorded it into a table nothing could read. A test that expected zero here would have
        // been asserting that nothing was written.
        mockMvc.perform(get("/api/v1/audit/events").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.count").isNumber())
                // The filters that were applied are repeated, so a person can see what was actually asked.
                .andExpect(jsonPath("$.filters.category").value("alle"))
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    @DisplayName("a recorded entry can be read back")
    void aRecordedEntryCanBeReadBack() throws Exception {
        Session session = register();
        event(session, "DOCUMENT", "document.viewed", "SUCCESS", "Ein Dokument wurde geöffnet");

        // Selected by the entry itself rather than by position: the account's own registration and login are
        // in the log too, and they belong there.
        MvcResult result = mockMvc.perform(get("/api/v1/audit/events").param("entityType", "DOCUMENT")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[?(@.action == 'document.viewed')].category")
                        .value("DOCUMENT"))
                .andExpect(jsonPath("$.events[?(@.action == 'document.viewed')].result")
                        .value("SUCCESS"))
                .andReturn();

        // The entry says who acted, and the label is the one recorded with it rather than the person's name
        // today: a log that showed current names would rewrite its own history.
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("Testperson")
                .contains("Ein Dokument wurde geöffnet");
    }

    // ---------------------------------------------------------------- filters that do not lie

    @Test
    @DisplayName("an unknown category is refused rather than silently dropped")
    void anUnknownCategoryIsRefused() throws Exception {
        Session session = register();
        event(session, "DOCUMENT", "document.viewed", "SUCCESS", "geöffnet");

        // Silently dropped, it would produce a result that looks like 'nothing happened in that category',
        // and the person would believe they had narrowed the question.
        mockMvc.perform(get("/api/v1/audit/events").param("category", "GIBT_ES_NICHT")
                        .cookie(session.cookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown result is refused, and a known one filters")
    void anUnknownResultIsRefusedAndAKnownOneFilters() throws Exception {
        Session session = register();
        event(session, "DOCUMENT", "document.viewed", "SUCCESS", "geöffnet");
        event(session, "AUTHORIZATION", "document.denied", "DENIED", "abgelehnt");

        mockMvc.perform(get("/api/v1/audit/events").param("result", "VIELLEICHT")
                        .cookie(session.cookie()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/audit/events").param("result", "denied").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[*].result")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("DENIED"))))
                .andExpect(jsonPath("$.events[?(@.action == 'document.denied')]").isNotEmpty())
                .andExpect(jsonPath("$.filters.result").value("denied"));
    }

    @Test
    @DisplayName("a category filter keeps only that category")
    void aCategoryFilterKeepsOnlyThatCategory() throws Exception {
        Session session = register();
        event(session, "DOCUMENT", "document.viewed", "SUCCESS", "geöffnet");
        event(session, "AUTHENTICATION", "auth.login", "SUCCESS", "angemeldet");

        // Every entry returned carries the category that was asked for - a stronger statement than a count,
        // because it holds however many the account happens to have.
        mockMvc.perform(get("/api/v1/audit/events").param("category", "authentication")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[*].category")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("AUTHENTICATION"))))
                .andExpect(jsonPath("$.events[?(@.action == 'auth.login')]").isNotEmpty());
    }

    // ---------------------------------------------------------------- what is served and refused

    @Test
    @DisplayName("the categories and results an entry can have are served")
    void theCataloguesAreServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/audit/categories").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").isNotEmpty());
        mockMvc.perform(get("/api/v1/audit/results").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").isNotEmpty());
    }

    @Test
    @DisplayName("another account's entries are not visible")
    void anotherAccountsEntriesAreNotVisible() throws Exception {
        Session first = register();
        Session second = register();
        event(first, "DOCUMENT", "document.viewed", "SUCCESS", "geheim");

        // The second account has its own entries - its registration and login - so the assertion is that the
        // first account's entry is not among them, not that the list is empty.
        MvcResult foreign = mockMvc.perform(get("/api/v1/audit/events").cookie(second.cookie()))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(foreign.getResponse().getContentAsString())
                .as("nothing of the first account's log may appear in the second's answer")
                .doesNotContain("geheim");
    }

    @Test
    @DisplayName("without a session nothing is read")
    void withoutASessionNothingIsRead() throws Exception {
        // Being able to read who opened which document is itself sensitive: the log names people.
        mockMvc.perform(get("/api/v1/audit/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/audit/categories")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the audit permissions exist and are held, which is what makes these reachable")
    void theAuditPermissionsAreHeld() {
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('audit.view','audit.export')
                  and r.tenant_id is null and r.code in ('OWNER','ADMIN')
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the audit permissions must be granted to OWNER and ADMIN, or these are dead")
                .isGreaterThan(0);
    }
}
