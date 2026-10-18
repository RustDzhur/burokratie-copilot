package de.buerokratiecopilot.identity.navigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.servlet.http.Cookie;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.ResultActions;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The personal sidebar settings over HTTP (spec §23).
 *
 * <p>The contract is small and the interesting part is not the saving but the boundaries: two people in
 * the same account must not overwrite each other, another account must not see the row even through raw
 * SQL, and an entry the backend does not know must still be stored. Those are the cases checked here;
 * {@code TenantIsolationSuiteIT} covers the other services of the platform, not this one.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NavigationPreferencesIT {

    private static final String ENDPOINT = "/api/v1/me/navigation";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private record Session(Cookie cookie, UUID tenantId, UUID userId, String email) {
    }

    /** Registers a company account, marks the address as confirmed and signs in. */
    private Session register() throws Exception {
        String email = "nav-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        String body = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Menü Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(body, "$.userId"));
        UUID tenantId = UUID.fromString(JsonPath.read(body, "$.tenantId"));
        // Registration leaves the account pending; the sign-in path refuses that by design.
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        return new Session(signIn(email), tenantId, userId, email);
    }

    private Cookie signIn(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("bc_session");
    }

    /**
     * Makes the second person a member of the first person's account.
     *
     * <p>A membership can only be created through an invitation, which needs a mail round trip the test
     * profile does not provide; moving the freshly registered membership to the other account produces
     * the same state and keeps the sign-in path real (a session is issued for the moved membership).</p>
     */
    private Session joinTenant(Session member, UUID tenantId) throws Exception {
        int moved = jdbc.update("""
                update memberships set tenant_id = ? where user_id = ? and status = 'ACTIVE'
                """, tenantId, member.userId());
        assertThat(moved).as("the freshly registered membership must be movable").isEqualTo(1);
        return new Session(signIn(member.email()), tenantId, member.userId(), member.email());
    }

    private ResultActions putSettings(Session session, List<String> order, List<String> hidden) throws Exception {
        return mockMvc.perform(put(ENDPOINT).with(csrf()).cookie(session.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("order", order, "hidden", hidden))));
    }

    private String getSettings(Session session) throws Exception {
        return mockMvc.perform(get(ENDPOINT).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------ the own settings

    @Test
    @DisplayName("the own settings are saved, read back, survive an unknown entry and are reset; the audit trail names both actions")
    void ownSettingsRoundTrip() throws Exception {
        Session me = register();

        // Never saved: empty lists and an explicit null, not an error and not a missing field.
        String empty = getSettings(me);
        assertThat((List<?>) JsonPath.read(empty, "$.order")).isEmpty();
        assertThat((List<?>) JsonPath.read(empty, "$.hidden")).isEmpty();
        assertThat(empty).as("the client distinguishes 'never saved' from a missing field")
                .contains("\"updatedAt\":null");

        String saved = putSettings(me, List.of("dashboard", "documents", "tasks"),
                List.of("integrations", "accessibilitySettings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.length()").value(3))
                .andExpect(jsonPath("$.order[0]").value("dashboard"))
                .andExpect(jsonPath("$.order[2]").value("tasks"))
                .andExpect(jsonPath("$.hidden[0]").value("integrations"))
                .andReturn().getResponse().getContentAsString();
        String savedAt = JsonPath.read(saved, "$.updatedAt");
        assertThat(savedAt).isNotBlank();

        String reread = getSettings(me);
        assertThat((List<String>) JsonPath.read(reread, "$.order"))
                .containsExactly("dashboard", "documents", "tasks");
        assertThat((List<String>) JsonPath.read(reread, "$.hidden"))
                .containsExactly("integrations", "accessibilitySettings");
        assertThat((String) JsonPath.read(reread, "$.updatedAt")).isEqualTo(savedAt);

        // The catalogue of screens lives in the frontend: an entry from a newer release is stored, not refused.
        putSettings(me, List.of("dashboard", "brand-new-screen-from-2027"), List.of("legacy-screen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order[1]").value("brand-new-screen-from-2027"))
                .andExpect(jsonPath("$.hidden[0]").value("legacy-screen"));
        assertThat((List<String>) JsonPath.read(getSettings(me), "$.order")).contains("brand-new-screen-from-2027");

        Integer saves = RequestContext.runAs(me.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from audit_events
                 where actor_user_id = ? and action = 'USER_NAVIGATION_SAVED'
                """, Integer.class, me.userId()));
        assertThat(saves).as("every save is in the audit trail").isEqualTo(2);

        mockMvc.perform(delete(ENDPOINT).with(csrf()).cookie(me.cookie())).andExpect(status().isNoContent());

        String reset = getSettings(me);
        assertThat((List<?>) JsonPath.read(reset, "$.order")).isEmpty();
        assertThat((List<?>) JsonPath.read(reset, "$.hidden")).isEmpty();
        assertThat(reset).contains("\"updatedAt\":null");
        Integer resets = RequestContext.runAs(me.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from audit_events
                 where actor_user_id = ? and action = 'USER_NAVIGATION_RESET'
                """, Integer.class, me.userId()));
        assertThat(resets).as("the reset is in the audit trail").isEqualTo(1);

        // A second delete is not an error: the interface asked for the default and the default applies.
        mockMvc.perform(delete(ENDPOINT).with(csrf()).cookie(me.cookie())).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ people of the same account

    @Test
    @DisplayName("a second person of the same account neither sees nor overwrites the first person's settings")
    void secondPersonOfTheSameAccountIsSomeoneElse() throws Exception {
        Session first = register();
        putSettings(first, List.of("dashboard", "finance"), List.of("integrations")).andExpect(status().isOk());

        Session second = joinTenant(register(), first.tenantId());
        String secondEmpty = getSettings(second);
        assertThat((List<?>) JsonPath.read(secondEmpty, "$.order")).isEmpty();
        assertThat((List<?>) JsonPath.read(secondEmpty, "$.hidden")).isEmpty();
        assertThat(secondEmpty).contains("\"updatedAt\":null");

        putSettings(second, List.of("tasks"), List.of("finance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order[0]").value("tasks"));

        // The first person's settings are untouched.
        String firstSettings = getSettings(first);
        assertThat((List<String>) JsonPath.read(firstSettings, "$.order")).containsExactly("dashboard", "finance");
        assertThat((List<String>) JsonPath.read(firstSettings, "$.hidden")).containsExactly("integrations");

        Integer rows = RequestContext.runAs(first.tenantId(),
                () -> jdbc.queryForObject("select count(*) from user_navigation_preferences", Integer.class));
        assertThat(rows).as("each person owns one row").isEqualTo(2);
    }

    @Test
    @DisplayName("resetting one person's settings leaves the other person's settings in the same account alone")
    void resetTouchesOnlyTheOwnRow() throws Exception {
        Session first = register();
        putSettings(first, List.of("dashboard"), List.of()).andExpect(status().isOk());
        Session second = joinTenant(register(), first.tenantId());
        putSettings(second, List.of("tasks", "documents"), List.of()).andExpect(status().isOk());

        mockMvc.perform(delete(ENDPOINT).with(csrf()).cookie(first.cookie())).andExpect(status().isNoContent());

        assertThat((List<?>) JsonPath.read(getSettings(first), "$.order")).isEmpty();
        assertThat((List<String>) JsonPath.read(getSettings(second), "$.order"))
                .containsExactly("tasks", "documents");
    }

    // ------------------------------------------------------------------ another account

    @Test
    @DisplayName("a person of another account sees nothing, and raw SQL cannot read or change the row through the policy")
    void anotherAccountIsOutOfReach() throws Exception {
        Session owner = register();
        putSettings(owner, List.of("dashboard", "documents"), List.of("integrations")).andExpect(status().isOk());

        Session stranger = register();
        String strangerView = getSettings(stranger);
        assertThat((List<?>) JsonPath.read(strangerView, "$.order")).isEmpty();
        assertThat(strangerView).contains("\"updatedAt\":null");

        // Defence in depth: even a query the service would never write must not reach the row.
        List<UUID> visibleToStranger = RequestContext.runAs(stranger.tenantId(), () ->
                jdbc.queryForList("select user_id from user_navigation_preferences where user_id = ?",
                        UUID.class, owner.userId()));
        assertThat(visibleToStranger).isEmpty();

        int changedByStranger = RequestContext.runAs(stranger.tenantId(), () ->
                jdbc.update("update user_navigation_preferences set preference = '{}'::jsonb where user_id = ?",
                        owner.userId()));
        assertThat(changedByStranger).as("row level security refuses the update").isZero();

        List<UUID> visibleToOwner = RequestContext.runAs(owner.tenantId(), () ->
                jdbc.queryForList("select user_id from user_navigation_preferences where user_id = ?",
                        UUID.class, owner.userId()));
        assertThat(visibleToOwner).containsExactly(owner.userId());
    }

    // ------------------------------------------------------------------ validation

    @Test
    @DisplayName("a duplicate, an entry both visible and hidden, more than 200 entries, a too long entry and a non-array are refused with 400")
    void invalidShapesAreRefused() throws Exception {
        Session me = register();

        putSettings(me, List.of("dashboard", "dashboard"), List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        putSettings(me, List.of("dashboard"), List.of("tasks", "tasks"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        putSettings(me, List.of("dashboard", "finance"), List.of("finance"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        List<String> moreThanAllowed = IntStream.range(0, 201).mapToObj(index -> "screen-" + index).toList();
        putSettings(me, moreThanAllowed, List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        putSettings(me, List.of("x".repeat(65)), List.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Not an array at all: the JSON cannot be bound to the request body.
        mockMvc.perform(put(ENDPOINT).with(csrf()).cookie(me.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order\":\"dashboard\",\"hidden\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_BODY"));

        // Nothing was stored while the requests were refused.
        assertThat((List<?>) JsonPath.read(getSettings(me), "$.order")).isEmpty();

        // The boundary itself is allowed: 200 entries, 64 characters, an unknown identifier.
        List<String> exactlyAllowed = IntStream.range(0, 200).mapToObj(index -> "screen-" + index).toList();
        putSettings(me, exactlyAllowed, List.of("y".repeat(64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.length()").value(200));
    }

    @Test
    @DisplayName("an empty body is refused instead of silently hiding every entry")
    void missingListsAreRefused() throws Exception {
        Session me = register();
        mockMvc.perform(put(ENDPOINT).with(csrf()).cookie(me.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Both lists empty is a real choice, not a missing value.
        putSettings(me, List.of(), List.of()).andExpect(status().isOk());
    }
}
