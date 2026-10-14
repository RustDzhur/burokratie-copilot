package de.buerokratiecopilot.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.cases.CaseTestSupport;
import de.buerokratiecopilot.radar.RadarFindingService.Draft;
import de.buerokratiecopilot.radar.RadarFindingService.Evidence;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/** RD-06: the Radar API checks tenant and rights on every call, repeats are harmless, and every decision is recorded. */
@SuppressWarnings("unchecked")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RadarControllerIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RadarFindingService findings;
    @Autowired
    private CaseTestSupport support;

    private record Account(UUID tenantId, UUID userId, String email, Cookie cookie) {
    }

    private Cookie login(String email) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn();
        return login.getResponse().getCookie("bc_session");
    }

    private Account newTenant() throws Exception {
        String email = "rctl-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Radar API","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        return new Account(tenantId, userId, email, login(email));
    }

    private UUID upload(Account a, String text) throws Exception {
        MvcResult r = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "Beleg.pdf", "application/pdf", TestDocuments.pdf(text)))
                        .with(csrf()).cookie(a.cookie()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.document.id"));
    }

    private UUID finding(Account a, String key, UUID documentId, UUID caseId) {
        return RequestContext.runAs(a.tenantId(), () -> findings.raise(a.tenantId(), new Draft(key, "INVOICE_ANOMALY", "B1", 1,
                "Betrag weicht ab", "Der Betrag ist höher als zuletzt", RadarSeverity.HIGH, 1.0, caseId, null, null,
                List.of("AMOUNT_DIFFERS_FROM_PREVIOUS_INVOICE"), List.of(),
                documentId == null ? List.of() : List.of(new Evidence("PRIMARY", documentId, 1, "Betrag 140,00", null, null)),
                "h")).id());
    }

    private String json(Cookie cookie, String url) throws Exception {
        return mockMvc.perform(get(url).cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("a finding is listed, opened, decided on with every move recorded; a repeated decision changes nothing")
    void decisions() throws Exception {
        Account a = newTenant();
        UUID document = upload(a, "Rechnung zum Befund");
        UUID id = finding(a, "B1:ctl-1", document, null);

        String list = json(a.cookie(), "/api/v1/radar/findings?status=NEW&severity=HIGH");
        assertThat((Integer) JsonPath.read(list, "$.total")).isEqualTo(1);
        assertThat((List<Object>) JsonPath.read(list, "$.items[0].allowedStatuses")).contains("CONFIRMED", "DISMISSED", "SNOOZED");

        String detail = json(a.cookie(), "/api/v1/radar/findings/" + id);
        assertThat((Object) JsonPath.read(detail, "$.evidence[0].document_name")).isEqualTo("Beleg.pdf");
        assertThat((List<?>) JsonPath.read(detail, "$.history")).hasSize(1);

        mockMvc.perform(post("/api/v1/radar/findings/" + id + "/confirm").with(csrf()).cookie(a.cookie())).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/radar/findings/" + id + "/confirm").with(csrf()).cookie(a.cookie())).andExpect(status().isOk());
        assertThat(json(a.cookie(), "/api/v1/radar/findings/" + id + "/history")).as("the repeat is not recorded twice")
                .contains("CONFIRMED");
        assertThat((List<?>) JsonPath.read(json(a.cookie(), "/api/v1/radar/findings/" + id), "$.history")).hasSize(2);

        mockMvc.perform(post("/api/v1/radar/findings/" + id + "/dismiss").with(csrf()).cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/radar/findings/" + id + "/snooze").with(csrf()).cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"until\":\"%s\"}".formatted(Instant.now().plus(3, ChronoUnit.DAYS))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/radar/findings/" + id + "/dismiss").with(csrf()).cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Preiserhöhung war vereinbart\"}"))
                .andExpect(status().isOk());
        String dismissed = json(a.cookie(), "/api/v1/radar/findings/" + id);
        assertThat((Object) JsonPath.read(dismissed, "$.status")).isEqualTo("DISMISSED");
        assertThat((Object) JsonPath.read(dismissed, "$.resolution_reason")).isEqualTo("Preiserhöhung war vereinbart");
        assertThat((List<Object>) JsonPath.read(dismissed, "$.allowedStatuses")).containsExactly("REOPENED");

        Integer audited = jdbc.queryForObject("select count(*) from audit_events where tenant_id = ? and action like 'RADAR_FINDING_%'",
                Integer.class, a.tenantId());
        assertThat(audited).as("confirm, snooze and dismiss").isEqualTo(3);

        String summary = json(a.cookie(), "/api/v1/radar/summary");
        assertThat((Object) JsonPath.read(summary, "$.byStatus.DISMISSED")).isEqualTo(1);
        assertThat((List<?>) JsonPath.read(summary, "$.recentlyResolved")).hasSize(1);
    }

    @Test
    @DisplayName("a case can be opened from a finding once, with the evidence documents filed in it")
    void caseFromFinding() throws Exception {
        Account a = newTenant();
        UUID document = upload(a, "Beleg für den Vorgang");
        UUID id = finding(a, "B1:ctl-2", document, null);

        String first = mockMvc.perform(post("/api/v1/radar/findings/" + id + "/create-case").with(csrf()).cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String caseId = JsonPath.read(first, "$.related_case_id");
        String second = mockMvc.perform(post("/api/v1/radar/findings/" + id + "/create-case").with(csrf()).cookie(a.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(second, "$.related_case_id")).isEqualTo(caseId);

        Integer cases = jdbc.queryForObject("select count(*) from cases where tenant_id = ? and title = 'Betrag weicht ab'",
                Integer.class, a.tenantId());
        Integer linked = jdbc.queryForObject("select count(*) from case_documents where case_id = ?::uuid and document_id = ?",
                Integer.class, caseId, document);
        assertThat(cases).isEqualTo(1);
        assertThat(linked).isEqualTo(1);
    }

    @Test
    @DisplayName("a colleague without the triage right sees findings but cannot decide; a private case's finding is hidden from them")
    void rights() throws Exception {
        Account owner = newTenant();
        UUID privateCase = RequestContext.runAs(owner.tenantId(), () -> jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title, status, priority, opened_at, owner_user_id)
                values (gen_random_uuid(), ?, 'R-1', 'Privat', 'NEW', 'NORMAL', now(), ?) returning id
                """, UUID.class, owner.tenantId(), owner.userId()));
        UUID open = finding(owner, "B1:ctl-3", null, null);
        UUID hidden = finding(owner, "B1:ctl-4", null, privateCase);

        String email = "kollege-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        support.colleagueIn(owner.tenantId(), owner.userId(), email);
        Cookie colleague = login(email);

        String list = json(colleague, "/api/v1/radar/findings");
        assertThat((Integer) JsonPath.read(list, "$.total")).isEqualTo(1);
        assertThat((Object) JsonPath.read(list, "$.items[0].id")).isEqualTo(open.toString());
        mockMvc.perform(get("/api/v1/radar/findings/" + hidden).cookie(colleague)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/radar/findings/" + open + "/confirm").with(csrf()).cookie(colleague))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("another tenant finds nothing and cannot decide; the rules can be read with their descriptions")
    void isolationAndRules() throws Exception {
        Account a = newTenant();
        Account b = newTenant();
        UUID id = finding(a, "B1:ctl-5", null, null);

        assertThat((Integer) JsonPath.read(json(b.cookie(), "/api/v1/radar/findings"), "$.total")).isZero();
        mockMvc.perform(get("/api/v1/radar/findings/" + id).cookie(b.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/radar/findings/" + id + "/confirm").with(csrf()).cookie(b.cookie()))
                .andExpect(status().isNotFound());

        String rules = json(a.cookie(), "/api/v1/radar/rules");
        assertThat((List<?>) JsonPath.read(rules, "$")).hasSize(6);
        assertThat((Object) JsonPath.read(rules, "$[?(@.id == 'B1')].closedWhen[0]")).isNotNull();
    }

    @Test
    @DisplayName("assigning needs a member of the tenant")
    void assigning() throws Exception {
        Account a = newTenant();
        Account b = newTenant();
        UUID id = finding(a, "B1:ctl-6", null, null);

        mockMvc.perform(post("/api/v1/radar/findings/" + id + "/assign").with(csrf()).cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"%s\"}".formatted(b.userId())))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/radar/findings/" + id + "/assign").with(csrf()).cookie(a.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"%s\"}".formatted(a.userId())))
                .andExpect(status().isOk());
        assertThat((Object) JsonPath.read(json(a.cookie(), "/api/v1/radar/findings/" + id), "$.assigned_to"))
                .isEqualTo(a.userId().toString());
    }
}
