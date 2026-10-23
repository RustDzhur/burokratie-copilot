package de.buerokratiecopilot.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.settings.SettingsService;

/** UI-SET-01: a tenant tunes the thresholds within their range; each change is a new version with a reason, history and audit. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SettingsRulesIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SettingsService settings;

    private record Account(UUID tenantId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "rules-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"R\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andReturn().getResponse().getCookie("bc_session");
        return new Account(tenantId, cookie);
    }

    private MvcResult call(Account a, MockHttpServletRequestBuilder b, String content) throws Exception {
        return mockMvc.perform(b.with(csrf()).cookie(a.cookie()).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn();
    }

    private int change(Account a, String key, String value, String reason) throws Exception {
        return call(a, put("/api/v1/settings/rules/" + key), "{\"value\":%s,\"reason\":%s}".formatted(value, reason)).getResponse().getStatus();
    }

    @Test
    @DisplayName("a change is a new version with the previous value and the reason; the Radar reads the new value; the default is untouched")
    void versionedChange() throws Exception {
        Account a = newTenant();

        assertThat(change(a, "radar.invoice.amount_change_percent", "25", "\"Zu viele Hinweise bei Kleinbeträgen\"")).isEqualTo(200);
        assertThat(change(a, "radar.invoice.amount_change_percent", "30", "\"Noch ruhiger\"")).isEqualTo(200);

        String history = call(a, get("/api/v1/settings/rules/radar.invoice.amount_change_percent/history"), "").getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(history, "$[*].version")).containsExactly(2, 1);
        assertThat((String) JsonPath.read(history, "$[1].previous_value")).isEqualTo("10");
        assertThat((String) JsonPath.read(history, "$[1].new_value")).isEqualTo("25");
        assertThat((String) JsonPath.read(history, "$[0].change_reason")).isEqualTo("Noch ruhiger");

        assertThat(RequestContext.runAs(a.tenantId(), () -> settings.getDouble("radar.invoice.amount_change_percent", 10))).isEqualTo(30.0);
        assertThat(jdbc.queryForObject("select value::text from system_settings where tenant_id is null and setting_key = 'radar.invoice.amount_change_percent'",
                String.class)).as("the platform default is untouched").isEqualTo("10");
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from audit_events where tenant_id = ? and action = 'RULE_SETTING_CHANGED'", Integer.class, a.tenantId()))).isEqualTo(2);

        // The radar module was deleted (§4.3), so its switches are no longer offered. They stay settable through
        // the API for a legacy workspace, but a list that shows them would present controls that change nothing.
        String list = call(a, get("/api/v1/settings/rules"), "").getResponse().getContentAsString();
        assertThat(JsonPath.<List<Map<String, Object>>>read(list, "$[?(@.key=='radar.invoice.amount_change_percent')]"))
                .as("the radar is gone, and so are its switches")
                .isEmpty();
        assertThat(JsonPath.<List<Map<String, Object>>>read(list, "$[?(@.key=='classification.review_threshold')]"))
                .as("a setting that still exists is still listed")
                .hasSize(1);
    }

    @Test
    @DisplayName("a value outside its range, of the wrong type, or against its partner threshold is refused; so is a change without a reason")
    void validation() throws Exception {
        Account a = newTenant();
        assertThat(change(a, "radar.invoice.amount_change_percent", "-5", "\"x\"")).isEqualTo(400);
        assertThat(change(a, "radar.invoice.amount_change_percent", "\"viel\"", "\"x\"")).isEqualTo(400);
        assertThat(change(a, "radar.enabled", "1", "\"x\"")).isEqualTo(400);
        assertThat(change(a, "radar.notify.min_severity", "\"SEHR_HOCH\"", "\"x\"")).isEqualTo(400);
        assertThat(change(a, "radar.invoice.amount_change_percent", "60", "\"x\"")).as("above the 'high' threshold of 50").isEqualTo(400);
        assertThat(change(a, "classification.review_threshold", "0.9", "\"x\"")).as("above the automatic acceptance of 0.85").isEqualTo(400);
        assertThat(change(a, "radar.enabled", "false", "null")).isEqualTo(400);
        assertThat(change(a, "radar.enabled", "false", "\" \"")).isEqualTo(400);
        assertThat(change(a, "ai.enabled", "true", "\"x\"")).as("not a key of this screen").isEqualTo(400);
        assertThat(change(a, "radar.notify.min_severity", "\"HIGH\"", "\"x\"")).isEqualTo(200);
        assertThat(change(a, "radar.enabled", "false", "\"Radar pausiert\"")).isEqualTo(200);
        assertThat(RequestContext.runAs(a.tenantId(), () -> settings.getBoolean("radar.enabled", true))).isFalse();
    }

    @Test
    @DisplayName("back to the default is a new version; settings of one workspace are invisible to another")
    void resetAndIsolation() throws Exception {
        Account a = newTenant();
        Account other = newTenant();
        change(a, "radar.task.overdue_high_days", "30", "\"länger warten\"");

        assertThat(call(a, post("/api/v1/settings/rules/radar.task.overdue_high_days/reset"), "{\"reason\":\"Zurück zum Standard\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(RequestContext.runAs(a.tenantId(), () -> settings.getInt("radar.task.overdue_high_days", 0))).isEqualTo(7);
        String history = call(a, get("/api/v1/settings/rules/radar.task.overdue_high_days/history"), "").getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(history, "$[*].version")).containsExactly(2, 1);

        assertThat(RequestContext.runAs(other.tenantId(), () -> settings.getInt("radar.task.overdue_high_days", 0))).isEqualTo(7);
        String otherHistory = call(other, get("/api/v1/settings/rules/radar.task.overdue_high_days/history"), "").getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(otherHistory, "$")).isEmpty();
    }
}
