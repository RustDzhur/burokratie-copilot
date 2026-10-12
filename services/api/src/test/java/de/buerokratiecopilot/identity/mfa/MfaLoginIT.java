package de.buerokratiecopilot.identity.mfa;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Signing in with a second factor: the password alone must not open the application.
 *
 * <p>Enrolling a factor used to change nothing about signing in - the session was issued the same way either
 * way - so the setting protected nobody. This walks the whole path: enrol, confirm, sign out, sign in again,
 * find the session unusable, prove the code, find it usable.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaLoginIT {

    private static final String PASSWORD = "EinSicheresPasswort-2026!";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    private Cookie login(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("bc_session");
    }

    @Test
    @DisplayName("with a confirmed factor the session is unusable until the code is proven")
    void secondFactorGatesTheSession() throws Exception {
        String email = "mfa-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        var registration = mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Mfa Test\",\"accountType\":\"COMPANY\"}"
                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(registration, "$.userId"));
        UUID tenantId = UUID.fromString(JsonPath.read(registration, "$.tenantId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });

        Cookie first = login(email);
        // No factor yet: the login says nothing is required and the session works at once.
        mvc.perform(get("/api/v1/auth/me").cookie(first)).andExpect(status().isOk());

        String enrol = mvc.perform(post("/api/v1/mfa/enrol").with(csrf()).cookie(first)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String methodId = JsonPath.read(enrol, "$.methodId");
        byte[] secret = base32(JsonPath.read(enrol, "$.secret"));
        String code = Totp.code(secret, Totp.stepAt(Instant.now().getEpochSecond()));
        mvc.perform(post("/api/v1/mfa/methods/" + methodId + "/confirm").with(csrf()).cookie(first)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());

        // A new sign-in now has to prove the code.
        Cookie second = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mfaRequired").value(true))
                .andReturn().getResponse().getCookie("bc_session");
        mvc.perform(get("/api/v1/auth/me").cookie(second)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/dashboard/summary").cookie(second)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/mfa/verify").with(csrf()).cookie(second).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(second)).andExpect(status().isUnauthorized());

        String good = Totp.code(secret, Totp.stepAt(Instant.now().getEpochSecond()));
        mvc.perform(post("/api/v1/auth/mfa/verify").with(csrf()).cookie(second).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + good + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").cookie(second)).andExpect(status().isOk());
    }

    private static byte[] base32(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.replace("=", "").toUpperCase().toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
