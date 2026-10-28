package de.buerokratiecopilot.license;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Without a licence the agent surface is closed with 402, while the routes an operator needs to
 * diagnose and repair the installation stay open.
 */
@SpringBootTest(properties = {
        "burokratie.license.enforced=true",
        "burokratie.license.key=",
        "burokratie.license.secret=",
        "burokratie.license.instance-id-file="
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LicenseMissingIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("the agent catalogue answers 402 LICENSE_MISSING, not 401")
    void agentSurfaceIsPaymentRequired() throws Exception {
        mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("LICENSE_MISSING"));
    }

    @Test
    @DisplayName("a run is refused before credentials are checked, so the real problem is visible")
    void runIsPaymentRequiredEvenWithoutAToken() throws Exception {
        mockMvc.perform(post("/api/v1/agents/doc-detect/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trigger\":\"manual\",\"inputs\":{\"fileName\":\"rechnung.pdf\"}}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("LICENSE_MISSING"));
    }

    @Test
    @DisplayName("health, authentication and the licence status stay reachable")
    void diagnosticsStayOpen() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/license"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("LICENSE_MISSING"))
                .andExpect(jsonPath("$.enforced").value(true))
                .andExpect(jsonPath("$.instanceId").doesNotExist());
    }
}
