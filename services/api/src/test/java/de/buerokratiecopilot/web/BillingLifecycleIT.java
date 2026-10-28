package de.buerokratiecopilot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

import de.buerokratiecopilot.billing.BillingService;
import de.buerokratiecopilot.billing.SubscriptionLifecycleJob;
import de.buerokratiecopilot.billing.SubscriptionService;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The life of a subscription from the first day to the deletion of the data: free trial, access that ends, the
 * gate that closes the API, an invoice with its PDF, the payment that opens it again, and the deletion by the
 * owner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(properties = "bc.test.context=billing-lifecycle")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingLifecycleIT {

    private static final String PASSWORD = "EinSicheresPasswort-2026!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SubscriptionService subscriptions;
    @Autowired
    private BillingService billing;
    @Autowired
    private SubscriptionLifecycleJob lifecycle;

    private static Cookie session;
    private static Cookie xsrf;
    private static UUID tenantId;
    private static UUID userId;
    private static String invoiceId;

    @Test
    @Order(1)
    @DisplayName("a new workspace starts with the free trial of its plan and can use the API")
    void trialStartsAtRegistration() throws Exception {
        String email = "bill-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        xsrf = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult registration = mvc.perform(post("/api/v1/auth/register").cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","displayName":"Erika Mustermann","accountType":"FAMILY_HOUSEHOLD"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
        userId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.userId"));
        tenantId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        session = mvc.perform(post("/api/v1/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("bc_session");

        mvc.perform(authed(get("/api/v1/auth/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.plan_code").value("PERSONAL"))
                .andExpect(jsonPath("$.subscription.status").value("TRIAL"))
                .andExpect(jsonPath("$.subscription.blocked").value(false))
                .andExpect(jsonPath("$.subscription.days_left").value(org.hamcrest.Matchers.greaterThan(55)))
                // Nobody becomes a platform administrator by registering.
                .andExpect(jsonPath("$.permissions[?(@ == 'platform.admin')]").isEmpty());
        mvc.perform(authed(get("/api/v1/documents"))).andExpect(status().isOk());
        mvc.perform(authed(get("/api/v1/admin/overview"))).andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    @DisplayName("an invoice needs the payee data; with it the owner gets an invoice and a PDF")
    void invoice() throws Exception {
        String request = """
                {"method":"INVOICE","months":1,"customerName":"Erika Mustermann","customerAddress":"Musterstr. 1\\n10115 Berlin",
                 "customerCountry":"de"}
                """;
        // The settings are global and survive between runs, so the starting point is set here, not assumed.
        jdbc.update("""
                update platform_settings set setting_value =
                    '{"name":"","address":"","iban":"","bic":"","taxId":"","vatId":"","note":""}'::jsonb
                where setting_key = 'billing.payee'
                """);
        // Nowhere to pay yet: the platform refuses to issue an invoice that says nothing about where to pay.
        mvc.perform(authed(post("/api/v1/billing/invoices").contentType(MediaType.APPLICATION_JSON).content(request)))
                .andExpect(status().isConflict());

        jdbc.update("""
                update platform_settings set setting_value =
                    '{"name":"Test GmbH","address":"Teststr. 2\\n10115 Berlin","iban":"DE02120300000000202051","bic":"BYLADEM1001","taxId":"12/345/67890","vatId":"","note":"Kein Ausweis von Umsatzsteuer"}'::jsonb
                where setting_key = 'billing.payee'
                """);
        MvcResult created = mvc.perform(authed(post("/api/v1/billing/invoices").contentType(MediaType.APPLICATION_JSON)
                        .content(request))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount_cents").value(790))
                .andExpect(jsonPath("$.status").value("ISSUED")).andReturn();
        invoiceId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        // Pressing the button again returns the same open invoice instead of a second one.
        mvc.perform(authed(post("/api/v1/billing/invoices").contentType(MediaType.APPLICATION_JSON).content(request)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(invoiceId));

        byte[] pdf = mvc.perform(authed(get("/api/v1/billing/invoices/" + invoiceId + "/pdf")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    @Order(3)
    @DisplayName("when the access ends the API answers 402, except the billing area and /auth/me")
    void gateClosesWhenAccessEnds() throws Exception {
        jdbc.update("update subscriptions set access_until = now() - interval '1 day' where tenant_id = ?", tenantId);
        subscriptions.forget(tenantId);

        mvc.perform(authed(get("/api/v1/documents"))).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_REQUIRED"));
        mvc.perform(authed(get("/api/v1/auth/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.blocked").value(true));
        mvc.perform(authed(get("/api/v1/billing/subscription"))).andExpect(status().isOk());
        mvc.perform(authed(get("/api/v1/billing/invoices"))).andExpect(status().isOk());
    }

    @Test
    @Order(4)
    @DisplayName("the lifecycle job marks the subscription expired and starts the retention period")
    void lifecycleExpires() {
        lifecycle.runOnce();
        var row = jdbc.queryForMap("select status, retention_until > now() as ahead from subscriptions where tenant_id = ?",
                tenantId);
        assertThat(row.get("status")).isEqualTo("EXPIRED");
        assertThat(row.get("ahead")).isEqualTo(true);
    }

    @Test
    @Order(5)
    @DisplayName("confirming the payment opens the access again; confirming twice is refused")
    void paymentOpensAccess() throws Exception {
        RequestContext.runAs((UUID) null, () -> {
            billing.markPaid(UUID.fromString(invoiceId), userId);
            return null;
        });
        subscriptions.forget(tenantId);
        mvc.perform(authed(get("/api/v1/documents"))).andExpect(status().isOk());
        mvc.perform(authed(get("/api/v1/auth/me"))).andExpect(jsonPath("$.subscription.status").value("ACTIVE"));
        org.junit.jupiter.api.Assertions.assertThrows(de.buerokratiecopilot.shared.error.ApiException.class,
                () -> RequestContext.runAs((UUID) null, () -> {
                    billing.markPaid(UUID.fromString(invoiceId), userId);
                    return null;
                }));
    }

    @Test
    @Order(6)
    @DisplayName("the owner can download everything as one ZIP")
    void exportsAllData() throws Exception {
        byte[] zip = mvc.perform(authed(get("/api/v1/billing/export")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        java.util.Set<String> names = new java.util.HashSet<>();
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                names.add(entry.getName());
            }
        }
        assertThat(names).contains("LIESMICH.txt", "dokumente.csv", "vorgaenge.csv", "aufgaben.csv", "kontakte.csv");
    }

    @Test
    @Order(7)
    @DisplayName("deleting the data needs the word DELETE and removes the workspace, its people and its audit trail")
    void ownerDeletesEverything() throws Exception {
        mvc.perform(authed(post("/api/v1/billing/data-choice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"DELETE\"}"))).andExpect(status().isBadRequest());
        mvc.perform(authed(post("/api/v1/billing/data-choice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"choice\":\"DELETE\",\"confirm\":\"DELETE\"}")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"deleted\":true")));

        assertThat(jdbc.queryForObject("select count(*) from tenants where id = ?", Integer.class, tenantId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_events where tenant_id = ?", Integer.class, tenantId))
                .isZero();
        // The invoice stays: it is an accounting record with its own retention.
        assertThat(jdbc.queryForObject("select count(*) from billing_invoices where tenant_id = ?", Integer.class,
                tenantId)).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
        return request.cookie(session, xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
    }
}
