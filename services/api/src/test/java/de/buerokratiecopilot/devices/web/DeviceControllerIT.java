package de.buerokratiecopilot.devices.web;

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
 * Devices over HTTP (spec §8, §20).
 *
 * <p>What this checks beyond the routes: the device key is <strong>shown once and stored only as its
 * hash</strong>, a device that has been taken out of service <strong>stops being able to receive work</strong>,
 * and a device that has never been seen is not reported as one seen long ago.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeviceControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "dev-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Geräte Test","accountType":"COMPANY"}
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

    /** A registered device and its identifier, over HTTP. */
    private UUID registerDevice(Session session, String identifier) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/devices").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"%s","name":"Scanner","deviceType":"SCANNER",
                                 "connectionType":"PULL"}
                                """.formatted(identifier)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(), "$.deviceId"));
    }

    // ---------------------------------------------------------------- the key

    @Test
    @DisplayName("a device is registered and its key is shown once")
    void aDeviceIsRegistered() throws Exception {
        Session session = register();

        MvcResult created = mockMvc.perform(post("/api/v1/devices").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"identifier":"scanner-%s","name":"Scanner","deviceType":"SCANNER",
                                 "connectionType":"PULL"}
                                """.formatted(UUID.randomUUID().toString().substring(0, 6))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").isNotEmpty())
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty())
                .andReturn();

        String apiKey = JsonPath.read(created.getResponse().getContentAsString(), "$.apiKey");
        String stored = jdbc.queryForObject("""
                select api_key_hash from devices where tenant_id = ?
                """, String.class, session.tenantId());
        // The key authenticates the device on every later call; a platform that could show it again would
        // be one that keeps it in a readable form.
        org.assertj.core.api.Assertions.assertThat(stored).doesNotContain(apiKey);
    }

    @Test
    @DisplayName("a device without an identifier is refused, because it could not be found again")
    void aDeviceWithoutAnIdentifierIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/devices").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ohne Kennung\",\"deviceType\":\"SCANNER\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- the list and one device

    @Test
    @DisplayName("the devices are listed, and one of them is served by itself")
    void theDevicesAreListed() throws Exception {
        Session session = register();
        UUID deviceId = registerDevice(session, "scanner-" + UUID.randomUUID().toString().substring(0, 6));

        mockMvc.perform(get("/api/v1/devices").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].identifier").isNotEmpty());

        mockMvc.perform(get("/api/v1/devices/{id}", deviceId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifier").isNotEmpty());
    }

    // ---------------------------------------------------------------- out of service

    @Test
    @DisplayName("a device is confirmed, and then it may receive work")
    void aDeviceIsConfirmed() throws Exception {
        Session session = register();
        UUID deviceId = registerDevice(session, "scanner-" + UUID.randomUUID().toString().substring(0, 6));

        mockMvc.perform(post("/api/v1/devices/{id}/activate", deviceId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/devices/{id}/may-receive-work", deviceId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayReceiveWork").value(true));
    }

    @Test
    @DisplayName("a device that was taken out of service stops receiving work at once")
    void aRevokedDeviceReceivesNothing() throws Exception {
        Session session = register();
        UUID deviceId = registerDevice(session, "scanner-" + UUID.randomUUID().toString().substring(0, 6));
        mockMvc.perform(post("/api/v1/devices/{id}/activate", deviceId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/devices/{id}/revoke", deviceId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Ausgemustert\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/devices/{id}/may-receive-work", deviceId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayReceiveWork").value(false));
        // Nothing is deleted: a document that came through it is still a document.
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                select count(*) from devices where id = ?
                """, Integer.class, deviceId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- what is unknown

    @Test
    @DisplayName("a device that was never seen is not reported as one seen long ago")
    void aDeviceNeverSeenSaysSo() throws Exception {
        Session session = register();
        UUID deviceId = registerDevice(session, "scanner-" + UUID.randomUUID().toString().substring(0, 6));

        // A zero would read as 'seen at the start of time'; the answer says which of the two it is.
        mockMvc.perform(get("/api/v1/devices/{id}/last-seen", deviceId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seen").value(false))
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist());
    }

    @Test
    @DisplayName("a device of another account is not reachable")
    void anotherAccountsDeviceIsNotReachable() throws Exception {
        Session first = register();
        Session second = register();
        UUID deviceId = registerDevice(first, "scanner-" + UUID.randomUUID().toString().substring(0, 6));

        mockMvc.perform(get("/api/v1/devices/{id}", deviceId).cookie(second.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/devices").cookie(second.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or registered")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/devices")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/devices").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"x\",\"name\":\"x\",\"deviceType\":\"SCANNER\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/devices/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown device is reported rather than invented")
    void anUnknownDeviceIsReported() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/devices/{id}", UUID.randomUUID()).cookie(session.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/devices/{id}/activate", UUID.randomUUID()).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the device permissions exist and are held, which is what makes these reachable")
    void theDevicePermissionsAreHeld() {
        // The lesson from taxonomy.manage, checked rather than assumed.
        Integer held = jdbc.queryForObject("""
                select count(*) from role_permissions rp
                join roles r on r.id = rp.role_id
                join permissions p on p.id = rp.permission_id
                where p.code in ('devices.view','devices.register','devices.manage')
                  and r.tenant_id is null
                """, Integer.class);

        org.assertj.core.api.Assertions.assertThat(held)
                .as("the device permissions must be granted to a system role, or these endpoints are dead")
                .isGreaterThan(0);
    }
}
