package de.buerokratiecopilot.memory;

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
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/** BM-04: one timeline over the event tables, paged, tenant-isolated and never wider than the rights of the reader. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TimelineIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
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
        String email = "tl-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Timeline Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.userId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        return new Account(tenantId, userId, email, login(email));
    }

    private List<String> sources(String json) {
        return JsonPath.read(json, "$[*].source");
    }

    @Test
    @DisplayName("the timeline of a contact merges the sources newest first, pages by time, hides private cases and other tenants")
    void timelineOfContact() throws Exception {
        Account owner = newTenant();
        Account stranger = newTenant();
        UUID contactId = support.contactIn(owner.tenantId(), "Stadtwerke Musterstadt");
        UUID caseId = RequestContext.runAs(owner.tenantId(), () -> {
            UUID id = jdbc.queryForObject("""
                    insert into cases (id, tenant_id, reference, title, status, priority, opened_at, owner_user_id,
                                       counterparty_contact_id)
                    values (gen_random_uuid(), ?, '2026-T1', 'Nebenkosten', 'NEW', 'NORMAL', now(), ?, ?) returning id
                    """, UUID.class, owner.tenantId(), owner.userId(), contactId);
            for (int i = 1; i <= 3; i++) {
                jdbc.update("""
                        insert into case_events (case_id, tenant_id, event_type, title, occurred_at)
                        values (?, ?, 'NOTE_ADDED', ?, now() - (? * interval '1 day'))
                        """, id, owner.tenantId(), "Notiz " + i, 10 - i);
            }
            jdbc.update("""
                    insert into audit_events (tenant_id, actor_user_id, category, action, entity_type, entity_id, entity_label)
                    values (?, ?, 'CASE', 'CONTACT_UPDATED', 'contacts', ?, 'Stadtwerke Musterstadt')
                    """, owner.tenantId(), owner.userId(), contactId);
            return id;
        });

        String all = mockMvc.perform(get("/api/v1/memory/entities/" + contactId + "/timeline").cookie(owner.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(sources(all)).containsOnly("CASE", "AUDIT").hasSize(4);
        assertThat((Object) JsonPath.read(all, "$[0].source")).as("the audit entry is the newest").isEqualTo("AUDIT");
        assertThat((Object) JsonPath.read(all, "$[0].recorded_at")).isNotNull();

        String first = mockMvc.perform(get("/api/v1/memory/entities/" + contactId + "/timeline?limit=2").cookie(owner.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(first, "$")).hasSize(2);
        String cursor = JsonPath.read(first, "$[1].event_time");
        String next = mockMvc.perform(get("/api/v1/memory/entities/" + contactId + "/timeline?limit=2&before=" + cursor)
                        .cookie(owner.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(next, "$")).hasSize(2);
        assertThat((Object) JsonPath.read(next, "$[0].title")).isEqualTo("Notiz 2");

        // A colleague who is no participant of the case, and holds no audit right, sees none of it.
        String colleagueEmail = "kollege-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        support.colleagueIn(owner.tenantId(), owner.userId(), colleagueEmail);
        String hidden = mockMvc.perform(get("/api/v1/memory/entities/" + contactId + "/timeline")
                        .cookie(login(colleagueEmail))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(hidden).as("a private case and the audit trail stay out").isEqualTo("[]");

        assertThat(mockMvc.perform(get("/api/v1/memory/entities/" + contactId + "/timeline").cookie(stranger.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
        assertThat(mockMvc.perform(get("/api/v1/memory/cases/" + caseId + "/timeline").cookie(stranger.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
    }
}
