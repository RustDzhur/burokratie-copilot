package de.buerokratiecopilot.identity.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** A public installation can close registration for everybody (BC_REGISTRATION_OPEN=false). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "burokratie.security.registration-open=false")
class RegistrationClosedIT {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("with registration closed the endpoint refuses and creates nothing")
    void registrationIsRefused() throws Exception {
        mvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"closed@example.de\",\"password\":\"EinSicheresPasswort-2026!\"}"))
                .andExpect(status().isForbidden());
    }
}
