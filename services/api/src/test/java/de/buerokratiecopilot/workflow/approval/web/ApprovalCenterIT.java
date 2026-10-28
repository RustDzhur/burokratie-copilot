package de.buerokratiecopilot.workflow.approval.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.cases.CaseTestSupport;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * CA-07: the Approval Center gives what a decision needs - case, material, warnings, term - shows a private case's request only
 * to those who may open the case, and accepts a request for changes as a decision with a description.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApprovalCenterIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CaseTestSupport support;

    private record Account(UUID tenantId, UUID userId, String email, Cookie cookie) {
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("bc_session");
    }

    private Account newTenant() throws Exception {
        String email = "center-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"EinSicheresPasswort-2026!","displayName":"Center","accountType":"COMPANY"}
                        """.formatted(email))).andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        return new Account(tenantId, userId, email, login(email));
    }

    private UUID caseOf(Account a, String reference) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title, status, priority, opened_at, owner_user_id)
                values (gen_random_uuid(), ?, ?, 'Preiserhöhung', 'IN_PROGRESS', 'NORMAL', now(), ?) returning id
                """, UUID.class, a.tenantId(), reference, a.userId()));
    }

    private UUID letter(Account a, UUID caseId) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into outbound_messages (id, tenant_id, case_id, subject, body_text, to_addresses)
                values (gen_random_uuid(), ?, ?, 'Widerspruch', 'Hiermit widerspreche ich der Preiserhöhung.', '["service@anbieter.example"]'::jsonb)
                returning id
                """, UUID.class, a.tenantId(), caseId));
    }

    private UUID approvalFor(Account a, UUID messageId, UUID requester) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into approvals (id, tenant_id, subject_type, subject_id, approval_type, status, automation_level, title, justification, requested_by,
                                       expires_at)
                values (gen_random_uuid(), ?, 'OUTBOUND_MESSAGE', ?, 'OUTBOUND_MESSAGE', 'PENDING', 4, 'Widerspruch senden', 'Die Erhöhung ist nicht vereinbart', ?,
                        now() + interval '3 days') returning id
                """, UUID.class, a.tenantId(), messageId, requester));
    }

    @Test
    @DisplayName("the center shows the material and the case; a private case's request is hidden from a colleague who cannot open the case")
    void materialAndPrivacy() throws Exception {
        Account owner = newTenant();
        UUID caseId = caseOf(owner, "A-1");
        UUID message = letter(owner, caseId);
        UUID colleagueUser = support.colleagueIn(owner.tenantId(), owner.userId(), "kollege-" + UUID.randomUUID().toString().substring(0, 6) + "@example.de");
        UUID approval = approvalFor(owner, message, colleagueUser);
        // The colleague may see finance matters (an accountant), but is no participant of the private case.
        RequestContext.runAs(owner.tenantId(), () -> jdbc.update("""
                insert into membership_roles (membership_id, role_id, granted_at)
                select m.id, r.id, now() from memberships m, roles r
                where m.user_id = ? and m.tenant_id = ? and r.tenant_id is null and r.code = 'ACCOUNTANT'
                """, colleagueUser, owner.tenantId()));
        RequestContext.runAs(owner.tenantId(), () -> jdbc.update("""
                insert into radar_findings (tenant_id, finding_key, finding_type, rule_id, rule_version, title, severity, material_hash, related_case_id)
                values (?, 'B1:x', 'INVOICE_ANOMALY', 'B1', 1, 'Betrag weicht ab', 'HIGH', 'h', ?)
                """, owner.tenantId(), caseId));

        String center = mockMvc.perform(get("/api/v1/approvals/center").cookie(owner.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(center, "$[0].id")).isEqualTo(approval.toString());
        assertThat((Object) JsonPath.read(center, "$[0].case.reference")).isEqualTo("A-1");
        assertThat((Object) JsonPath.read(center, "$[0].material.subject")).isEqualTo("Widerspruch");
        assertThat((Object) JsonPath.read(center, "$[0].material.excerpt")).asString().contains("Preiserhöhung");
        assertThat((List<Object>) JsonPath.read(center, "$[0].warnings")).containsExactly("OPEN_RADAR_FINDINGS");
        assertThat((Object) JsonPath.read(center, "$[0].mayDecide")).isEqualTo(true);
        assertThat((Object) JsonPath.read(center, "$[0].requested_by_name")).isNotNull();

        // The colleague asked for it, holds no case right and cannot open the private case: the request is not shown to them.
        String hidden = mockMvc.perform(get("/api/v1/approvals/center").cookie(login(jdbc.queryForObject(
                "select email from users where id = ?", String.class, colleagueUser)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(hidden).isEqualTo("[]");
    }

    @Test
    @DisplayName("a request for changes is recorded as a rejection of this version with the wish; it needs a description")
    void changesRequested() throws Exception {
        Account owner = newTenant();
        UUID caseId = caseOf(owner, "A-2");
        UUID message = letter(owner, caseId);
        UUID colleagueUser = support.colleagueIn(owner.tenantId(), owner.userId(), "kollege-" + UUID.randomUUID().toString().substring(0, 6) + "@example.de");
        UUID approval = approvalFor(owner, message, colleagueUser);

        mockMvc.perform(post("/api/v1/approvals/" + approval + "/decision").with(csrf()).cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":false,\"changesRequested\":true}")).andExpect(status().is4xxClientError());
        mockMvc.perform(post("/api/v1/approvals/" + approval + "/decision").with(csrf()).cookie(owner.cookie())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":false,\"changesRequested\":true,\"note\":\"Bitte die Frist im Text nennen\"}"))
                .andExpect(status().isOk());

        assertThat(RequestContext.runAs(owner.tenantId(), () -> jdbc.queryForMap("select status, decision_note from approvals where id = ?", approval)))
                .containsEntry("status", "REJECTED").satisfies(row -> assertThat((String) row.get("decision_note")).startsWith("[Änderung erbeten]"));
    }
}
