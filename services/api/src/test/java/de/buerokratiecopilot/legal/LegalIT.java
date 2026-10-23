package de.buerokratiecopilot.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.List;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/** The document page shows the risks that cite the document and the cases it belongs to (S02-14). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegalIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BurokratieProperties properties;

    private record Session(Cookie cookie, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "ins-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        String body = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"P\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        UUID userId = UUID.fromString(JsonPath.read(body, "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andReturn().getResponse().getCookie("bc_session");
        return new Session(cookie, userId);
    }

    private static final String REGISTER = "{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"P\"%s}";

    @Test
    @DisplayName("the public legal data shows only what the operator entered, and names what is still missing")
    void imprintIsTheOperatorsOwn() throws Exception {
        jdbc.update("update platform_settings set setting_value = '{}'::jsonb where setting_key = 'legal.operator'");
        mockMvc.perform(get("/api/v1/public/legal")).andExpect(status().isOk())
                .andExpect(jsonPath("$.operator.name").doesNotExist())
                .andExpect(jsonPath("$.missing[0]").value("name"))
                .andExpect(jsonPath("$.version").value(LegalService.TEXT_VERSION));

        jdbc.update("""
                insert into platform_settings (setting_key, setting_value) values ('legal.operator',
                    '{"name":"Muster GmbH","address":"Musterstr. 1, 10115 Berlin","email":"info@example.de","phone":"+49 30 1234","disputeResolution":"NOT_WILLING"}'::jsonb)
                on conflict (setting_key) do update set setting_value = excluded.setting_value
                """);
        try {
            mockMvc.perform(get("/api/v1/public/legal")).andExpect(jsonPath("$.operator.name").value("Muster GmbH"))
                    .andExpect(jsonPath("$.missing.length()").value(0));
        }
        finally {
            jdbc.update("update platform_settings set setting_value = '{}'::jsonb where setting_key = 'legal.operator'");
        }
    }

    private UUID register(String extra) throws Exception {
        String email = "legal-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        String body = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(REGISTER.formatted(email, extra))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.userId"));
    }

    @Test
    @DisplayName("registration records which version of the terms, the privacy notice and the processing agreement was accepted")
    void registrationRecordsTheAcceptance() throws Exception {
        // Every cabinet is a Kanzlei, a business, so the processing agreement is part of every registration.
        UUID registered = register(",\"acceptedTerms\":true");
        assertThat(jdbc.queryForList("select document from legal_consents where user_id = ? and version = ? order by document",
                String.class, registered, LegalService.TEXT_VERSION)).containsExactly("AGB", "AVV", "DATENSCHUTZ");
    }

    @Test
    @DisplayName("where acceptance is required, registration without it is refused and creates nothing")
    void acceptanceIsRequiredInProduction() throws Exception {
        properties.getSecurity().setRequireTermsAcceptance(true);
        try {
            String email = "legal-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
            mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(REGISTER.formatted(email, ""))).andExpect(status().isUnprocessableEntity());
            assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
        }
        finally {
            properties.getSecurity().setRequireTermsAcceptance(false);
        }
    }
}
