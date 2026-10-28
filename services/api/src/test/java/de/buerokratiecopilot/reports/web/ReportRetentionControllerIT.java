package de.buerokratiecopilot.reports.web;

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
 * Reports and retention over HTTP (spec §22, §26; §20).
 *
 * <p>Two things are checked beyond the routes. A figure is served <strong>with its period, its unit and
 * whether it is exact</strong>, because a number without those is a number somebody will misread. And a
 * retention period is refused <strong>without a legal basis</strong>, because how long a document must be
 * kept is not this platform's answer.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportRetentionControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "rep-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Auswertung Test","accountType":"COMPANY"}
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

    private UUID documentFor(Session session) {
        return jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status)
                values (gen_random_uuid(), ?, 'akte.pdf', 'akte.pdf', 'application/pdf', 10, ?,
                        'UPLOAD', 'READY', 'NOT_REQUIRED')
                returning id
                """, UUID.class, session.tenantId(),
                "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong()));
    }

    // ---------------------------------------------------------------- reports

    @Test
    @DisplayName("a report is served with its period")
    void aReportIsServedWithItsPeriod() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/reports/company")
                        .param("from", "2026-01-01").param("to", "2026-03-31")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audience").value("COMPANY"))
                .andExpect(jsonPath("$.period.from").value("2026-01-01"))
                .andExpect(jsonPath("$.period.to").value("2026-03-31"))
                .andExpect(jsonPath("$.period.description").isNotEmpty())
                .andExpect(jsonPath("$.metrics").isArray());
    }

    @Test
    @DisplayName("every figure carries its unit and whether it is exact")
    void everyFigureCarriesItsUnit() throws Exception {
        Session session = register();

        MvcResult result = mockMvc.perform(get("/api/v1/reports/company")
                        .param("from", "2026-01-01").param("to", "2026-03-31")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andReturn();

        java.util.List<java.util.Map<String, Object>> metrics = JsonPath.read(
                result.getResponse().getContentAsString(), "$.metrics");
        org.assertj.core.api.Assertions.assertThat(metrics).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(metrics).allSatisfy(metric -> {
            // A number without its unit and without whether it is exact is a number somebody acts on.
            org.assertj.core.api.Assertions.assertThat(metric).containsKeys("code", "label", "value",
                    "unit", "exact");
        });
    }

    @Test
    @DisplayName("a report without a period is refused, because a figure without one cannot be read")
    void aReportWithoutAPeriodIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/reports/company").cookie(session.cookie()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("an unknown audience is refused with the ones that exist")
    void anUnknownAudienceIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/reports/galaxy")
                        .param("from", "2026-01-01").param("to", "2026-03-31")
                        .cookie(session.cookie()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the audiences and their metric codes are served")
    void theCatalogueIsServed() throws Exception {
        Session session = register();

        mockMvc.perform(get("/api/v1/reports/audiences").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").isNotEmpty());
        mockMvc.perform(get("/api/v1/reports/company/metrics").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricCodes").isArray());
    }

    // ---------------------------------------------------------------- retention

    @Test
    @DisplayName("a retention period without a legal basis is refused")
    void withoutALegalBasisNothingIsDefined() throws Exception {
        Session session = register();

        // How long a document must be kept is not this platform's answer; a policy without a basis is a
        // period somebody guessed.
        mockMvc.perform(post("/api/v1/retention/policies").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X-1\",\"nameGerman\":\"Zehn Jahre\","
                                + "\"retentionPeriodMonths\":120,\"retentionTrigger\":\"DOCUMENT_DATE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a retention period without a positive number of months is refused")
    void withoutAPositivePeriodNothingIsDefined() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/retention/policies").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X-2\",\"nameGerman\":\"Null\",\"retentionPeriodMonths\":0,"
                                + "\"retentionTrigger\":\"DOCUMENT_DATE\",\"legalBasis\":\"§ 147 AO\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a policy with a basis is defined and can be confirmed")
    void aPolicyIsDefinedAndConfirmed() throws Exception {
        Session session = register();
        String code = "SZ-" + UUID.randomUUID().toString().substring(0, 6);

        MvcResult created = mockMvc.perform(post("/api/v1/retention/policies").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","nameGerman":"Zehn Jahre","retentionPeriodMonths":120,
                                 "retentionTrigger":"DOCUMENT_DATE","legalBasis":"§ 147 AO",
                                 "validFrom":"2020-01-01"}
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID policyId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(),
                "$.policyId"));

        mockMvc.perform(post("/api/v1/retention/policies/{id}/verify", policyId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/retention/policies/{id}/may-delete", policyId)
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mayDelete").isBoolean());
    }

    @Test
    @DisplayName("a legal hold is placed, read back and lifted")
    void aLegalHoldIsPlacedAndLifted() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);

        mockMvc.perform(post("/api/v1/documents/{id}/legal-hold", documentId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Rechtsstreit\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/documents/{id}/legal-hold", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underLegalHold").value(true));

        mockMvc.perform(post("/api/v1/documents/{id}/legal-hold/lift", documentId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Beendet\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/documents/{id}/legal-hold", documentId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underLegalHold").value(false));
    }

    @Test
    @DisplayName("a retention action can be scheduled and run, and it reports what happened")
    void aRetentionJobRunsAndReports() throws Exception {
        Session session = register();
        UUID documentId = documentFor(session);
        String code = "SZ-" + UUID.randomUUID().toString().substring(0, 6);
        MvcResult created = mockMvc.perform(post("/api/v1/retention/policies").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","nameGerman":"Zehn Jahre","retentionPeriodMonths":120,
                                 "retentionTrigger":"DOCUMENT_DATE","legalBasis":"§ 147 AO"}
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID policyId = UUID.fromString(JsonPath.read(created.getResponse().getContentAsString(),
                "$.policyId"));

        MvcResult scheduled = mockMvc.perform(post("/api/v1/retention/jobs").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"%s\",\"policyId\":\"%s\"}"
                                .formatted(documentId, policyId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID jobId = UUID.fromString(JsonPath.read(scheduled.getResponse().getContentAsString(),
                "$.jobId"));

        // The outcome says what happened AND why, including when nothing happened.
        mockMvc.perform(post("/api/v1/retention/jobs/{id}/execute", jobId).with(csrf())
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").isNotEmpty());
    }

    @Test
    @DisplayName("an unknown action is refused")
    void anUnknownActionIsRefused() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/retention/jobs").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"%s\",\"policyId\":\"%s\",\"action\":\"VAPORISE\"}"
                                .formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------- what is refused

    @Test
    @DisplayName("without a session nothing is read or changed")
    void withoutASessionNothingHappens() throws Exception {
        mockMvc.perform(get("/api/v1/reports/company")
                        .param("from", "2026-01-01").param("to", "2026-03-31"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/retention/policies").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X\",\"legalBasis\":\"§ 147 AO\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/documents/{id}/legal-hold", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
