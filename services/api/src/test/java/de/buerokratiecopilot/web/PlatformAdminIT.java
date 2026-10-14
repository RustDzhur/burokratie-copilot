package de.buerokratiecopilot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The platform administrator's API: who gets it, and what it shows and changes across customers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = "bc.test.context=platform-admin")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformAdminIT {

    private static final String PASSWORD = "EinSicheresPasswort-2026!";
    /** A new address every run: the test database keeps its users between runs. */
    private static final String BOSS = "boss-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";

    @org.springframework.test.context.DynamicPropertySource
    static void platformAdmin(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("burokratie.platform-admins", () -> BOSS);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    private static Cookie session;
    private static Cookie xsrf;
    private static UUID customerTenant;

    private UUID register(String email, boolean verify) throws Exception {
        MvcResult registration = mvc.perform(post("/api/v1/auth/register").cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","displayName":"Test","accountType":"COMPANY"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
        UUID userId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.userId"));
        UUID tenantId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
        if (verify) {
            RequestContext.runAs(tenantId, () -> {
                User user = users.findById(userId).orElseThrow();
                user.markEmailVerified(java.time.Instant.now());
                user.setStatus(User.Status.ACTIVE);
                return users.save(user);
            });
        }
        return tenantId;
    }

    @Test
    @Order(1)
    @DisplayName("the listed, verified address is a platform administrator and sees every customer")
    void adminSeesEverything() throws Exception {
        xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        register(BOSS, true);
        customerTenant = register("customer-it-" + UUID.randomUUID().toString().substring(0, 6) + "@example.de", true);
        session = mvc.perform(post("/api/v1/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(BOSS, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("bc_session");

        mvc.perform(authed(get("/api/v1/auth/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions[?(@ == 'platform.admin')]").isNotEmpty());
        mvc.perform(authed(get("/api/v1/admin/overview"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        MvcResult tenants = mvc.perform(authed(get("/api/v1/admin/tenants"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].owner_email").isString()).andReturn();
        // The customer's workspace is in the list although the administrator belongs to a different one.
        assertThat(tenants.getResponse().getContentAsString()).contains(customerTenant.toString());
    }

    @Test
    @Order(2)
    @DisplayName("the administrator extends, suspends and unsuspends a customer, and the gate follows at once")
    void adminManagesACustomer() throws Exception {
        mvc.perform(authed(post("/api/v1/admin/tenants/" + customerTenant + "/extend")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"months\":2}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(authed(post("/api/v1/admin/tenants/" + customerTenant + "/suspend")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.blocked").value(true));
        mvc.perform(authed(post("/api/v1/admin/tenants/" + customerTenant + "/unsuspend")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.blocked").value(false));
    }

    @Test
    @Order(3)
    @DisplayName("prices and payment details are edited in the admin area, and the public price list follows")
    void pricesAndSettings() throws Exception {
        mvc.perform(authed(put("/api/v1/admin/plans/PERSONAL").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priceCents\":800}"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/plans")).andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[?(@.code == 'PERSONAL')].price_cents").value(800))
                .andExpect(jsonPath("$.trialDays").isNumber());
        mvc.perform(authed(put("/api/v1/admin/plans/PERSONAL").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priceCents\":790}"))).andExpect(status().isOk());

        mvc.perform(authed(put("/api/v1/admin/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"billing.crypto\",\"value\":{\"asset\":\"USDT\",\"network\":\"TRON\","
                                + "\"address\":\"T-TEST\",\"eurPerUnit\":\"0.92\"}}")))
                .andExpect(status().isOk());
        // Only the platform's own keys can be set through this door.
        mvc.perform(authed(put("/api/v1/admin/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"anything.else\",\"value\":1}"))).andExpect(status().isBadRequest());
        mvc.perform(authed(get("/api/v1/admin/integrations"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isString());
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
        return request.cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
    }
}
