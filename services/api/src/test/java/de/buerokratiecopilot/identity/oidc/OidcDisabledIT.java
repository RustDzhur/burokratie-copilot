package de.buerokratiecopilot.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** HD-OIDC: without an issuer and a client the platform offers no external sign-in and answers its routes with 404. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OidcDisabledIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("not configured: the login page is told so, and start and callback do nothing")
    void disabledByDefault() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/auth/oidc/available")).andReturn().getResponse().getContentAsString()).contains("\"enabled\":false");
        assertThat(mockMvc.perform(get("/api/v1/auth/oidc/start")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(get("/api/v1/auth/oidc/callback").param("code", "x").param("state", "y")).andReturn().getResponse().getStatus())
                .as("a refusal that sends the browser back to the login page, never a session").isEqualTo(302);
        assertThat(mockMvc.perform(get("/api/v1/auth/oidc/callback").param("code", "x").param("state", "y")).andReturn().getResponse().getCookie("bc_session")).isNull();
    }
}
