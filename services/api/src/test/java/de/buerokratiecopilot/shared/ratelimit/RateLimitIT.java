package de.buerokratiecopilot.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.shared.context.RequestContext;

/** HD-RATE-01: more calls than the limit within a minute are answered with 429, once in the audit trail, and others are not affected. */
@SpringBootTest(properties = { "burokratie.security.rate-limit.enabled=true", "burokratie.security.rate-limit.requests-per-minute=12" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private record Account(UUID tenantId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "rate-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"R\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andReturn()
                .getResponse().getCookie("bc_session");
        return new Account(tenantId, cookie);
    }

    private int status(Account a) throws Exception {
        return mockMvc.perform(get("/api/v1/documents").cookie(a.cookie())).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("over the limit: 429 with Retry-After in the error envelope, one audit line for the whole flood; another person is not affected")
    void overTheLimit() throws Exception {
        Account busy = newTenant();
        Account calm = newTenant();

        for (int i = 0; i < 12; i++) {
            assertThat(status(busy)).as("call %d is within the limit", i + 1).isEqualTo(200);
        }
        MvcResult refused = mockMvc.perform(get("/api/v1/documents").cookie(busy.cookie())).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(429);
        assertThat(refused.getResponse().getHeader("Retry-After")).isNotNull();
        assertThat(refused.getResponse().getHeader("X-RateLimit-Limit")).isEqualTo("12");
        assertThat((String) JsonPath.read(refused.getResponse().getContentAsString(), "$.code")).isEqualTo("RATE_LIMITED");
        for (int i = 0; i < 5; i++) {
            assertThat(status(busy)).isEqualTo(429);
        }

        assertThat(RequestContext.runAs(busy.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from audit_events where tenant_id = ? and action = 'API_RATE_LIMITED'", Integer.class, busy.tenantId())))
                .as("a flood leaves one line").isEqualTo(1);
        assertThat(status(calm)).isEqualTo(200);
    }

    @Test
    @DisplayName("health checks are not counted")
    void healthIsNotCounted() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isNotEqualTo(429);
        }
    }
}
