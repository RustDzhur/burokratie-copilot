package de.buerokratiecopilot.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** BM-UI-01: the profile of a contact gathers what the memory holds about it, and hides what the reader may not see. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContactProfileIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CaseTestSupport support;

    private record Account(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "prof-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"P\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie c = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("bc_session");
        return new Account(tenantId, userId, c);
    }

    @Test
    @DisplayName("the profile lists contracts, invoices and visible cases of the contact; a private case stays hidden from a colleague; another tenant finds nothing")
    void profile() throws Exception {
        Account a = newTenant();
        Account b = newTenant();
        UUID contact = support.contactIn(a.tenantId(), "Stadtwerke Musterstadt");
        RequestContext.runAs(a.tenantId(), () -> {
            jdbc.update("insert into contracts (id, tenant_id, title, contract_type, status, counterparty_contact_id) values (gen_random_uuid(), ?, 'Strom', 'ENERGY', 'ACTIVE', ?)",
                    a.tenantId(), contact);
            jdbc.update("insert into invoices (id, tenant_id, direction, contact_id, invoice_number, gross_amount) values (gen_random_uuid(), ?, 'INCOMING', ?, 'R-1', 96.00)",
                    a.tenantId(), contact);
            jdbc.update("insert into cases (id, tenant_id, reference, title, status, priority, opened_at, owner_user_id, counterparty_contact_id) values (gen_random_uuid(), ?, 'K-1', 'Privat', 'NEW', 'NORMAL', now(), ?, ?)",
                    a.tenantId(), a.userId(), contact);
            return null;
        });
        String body = mockMvc.perform(get("/api/v1/memory/entities/" + contact).cookie(a.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(body, "$.contact.display_name")).isEqualTo("Stadtwerke Musterstadt");
        assertThat((Object) JsonPath.read(body, "$.contracts[0].title")).isEqualTo("Strom");
        assertThat((Object) JsonPath.read(body, "$.invoices[0].invoice_number")).isEqualTo("R-1");
        assertThat((Object) JsonPath.read(body, "$.cases[0].reference")).isEqualTo("K-1");

        String email = "kollege-" + UUID.randomUUID().toString().substring(0, 6) + "@example.de";
        support.colleagueIn(a.tenantId(), a.userId(), email);
        Cookie colleague = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andReturn().getResponse().getCookie("bc_session");
        String limited = mockMvc.perform(get("/api/v1/memory/entities/" + contact).cookie(colleague)).andReturn().getResponse().getContentAsString();
        assertThat(limited).doesNotContain("K-1");

        mockMvc.perform(get("/api/v1/memory/entities/" + contact).cookie(b.cookie())).andExpect(status().isNotFound());
    }
}
