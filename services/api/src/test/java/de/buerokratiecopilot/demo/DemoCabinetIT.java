package de.buerokratiecopilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.orchestration.CaseOrchestrator;
import de.buerokratiecopilot.shared.context.RequestContext;

/** A demo cabinet opens without a password, is filled, cannot reach the outside, and is gone without a trace when left. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoCabinetIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DemoService demo;
    @Autowired
    private DocumentProcessingPipeline pipeline;
    @Autowired
    private CaseOrchestrator orchestrator;

    private Cookie start(String persona, String ip) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/public/demo/start").with(csrf())
                        .with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content("{\"persona\":\"" + persona + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.next").value("/documents?tour=1"))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("bc_session");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private UUID tenantOf(Cookie cookie) throws Exception {
        String me = mockMvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(me, "$.tenantId"));
    }

    private int count(String sql, UUID tenant) {
        Integer value = RequestContext.runAs(tenant, () -> jdbc.queryForObject(sql, Integer.class, tenant));
        return value == null ? 0 : value;
    }

    @Test
    @DisplayName("a company demo opens without a password, with letters read and sorted, work to do, and a status that says it is a demo")
    void companyDemoIsFilled() throws Exception {
        Cookie cookie = start("company", "10.1.0.1");
        UUID tenant = tenantOf(cookie);

        assertThat(count("select count(*) from documents where tenant_id = ? and document_type_id is not null", tenant))
                .as("letters are sorted by the platform's own rules").isGreaterThanOrEqualTo(3);
        assertThat(count("select count(*) from documents where tenant_id = ? and processing_status = 'REVIEW_REQUIRED'", tenant))
                .as("and the unsure ones wait for a person, which the visitor can try").isGreaterThan(0);
        assertThat(count("select count(*) from tasks where tenant_id = ? and deleted_at is null", tenant))
                .as("a demo that shows an empty task queue is not a demo of this product").isGreaterThan(0);
        assertThat(count("select count(*) from documents where tenant_id = ? and sender is not null and sender_contact_id is not null", tenant))
                .as("the senders are read from the letters and kept as contacts").isGreaterThanOrEqualTo(3);

        mockMvc.perform(get("/api/v1/demo/status").cookie(cookie)).andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.persona").value("COMPANY")).andExpect(jsonPath("$.expiresAt").exists());
        // The account has no password: the only way in is the button.
        String email = jdbc.queryForObject("select u.email from users u join memberships m on m.user_id = u.id where m.tenant_id = ? and m.is_owner",
                String.class, tenant);
        assertThat(email).endsWith("@demo.invalid");
        assertThat(jdbc.queryForObject("select password_hash from users where email = ?", String.class, email)).isNull();
    }

    @Test
    @DisplayName("in a demo cabinet everything that reaches the outside or changes the way in is closed")
    void outsideIsClosed() throws Exception {
        Cookie cookie = start("freelancer", "10.1.0.2");
        for (String path : new String[] {"/api/v1/mail/messages", "/api/v1/integrations", "/api/v1/billing/subscription", "/api/v1/settings/security", "/api/v1/admin/overview"}) {
            mockMvc.perform(get(path).cookie(cookie)).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/v1/documents").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("leaving deletes the cabinet, its documents, the person and the session; an expired cabinet is deleted by the job")
    void leavingLeavesNothing() throws Exception {
        Cookie cookie = start("freelancer", "10.1.0.3");
        UUID tenant = tenantOf(cookie);
        UUID user = jdbc.queryForObject("select user_id from memberships where tenant_id = ? and is_owner", UUID.class, tenant);
        // The visitor changes things: a document is deleted, a task closed. None of it survives.
        mockMvc.perform(post("/api/v1/demo/exit").with(csrf()).cookie(cookie)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from tenants where id = ?", Integer.class, tenant)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from documents where tenant_id = ?", Integer.class, tenant)).isZero();
        mockMvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());

        Cookie second = start("freelancer", "10.1.0.4");
        UUID secondTenant = tenantOf(second);
        jdbc.update("update tenants set created_at = now() - interval '5 hours' where id = ?", secondTenant);
        assertThat(demo.purgeExpired()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenants where id = ?", Integer.class, secondTenant)).isZero();
    }

    @Test
    @DisplayName("the Kanzlei demo is one office with twenty vouchers, three clients, open work and dates - the meeting cabinet")
    void advisorDemoIsTheMeetingCabinet() throws Exception {
        Cookie cookie = start("advisor", "10.1.0.5");
        UUID tenant = tenantOf(cookie);

        // §10/Э0: 20 Belege in one Kanzlei, not twenty client workspaces.
        assertThat(count("select count(*) from documents where tenant_id = ? and deleted_at is null", tenant))
                .as("twenty vouchers").isGreaterThanOrEqualTo(20);
        assertThat(count("select count(*) from contacts where tenant_id = ? and deleted_at is null", tenant))
                .as("exactly the three clients of the demo Kanzlei").isEqualTo(3);
        assertThat(count("select count(distinct mandant_id) from documents where tenant_id = ? and mandant_id is not null", tenant))
                .as("the vouchers belong to at least three different clients").isGreaterThanOrEqualTo(3);
        assertThat(count("select count(*) from documents where tenant_id = ? and mandant_id is null", tenant))
                .as("some vouchers are honestly left 'Ohne Mandant' for the visitor to sort").isGreaterThanOrEqualTo(1);

        // Work, and the dates that make it a Kanzlei rather than an archive.
        assertThat(count("select count(*) from tasks where tenant_id = ? and deleted_at is null", tenant))
                .as("the queue is not empty").isGreaterThan(0);
        assertThat(count("""
                select count(*) from task_deadlines
                 where tenant_id = ? and is_active
                   and coalesce(due_date, (due_at at time zone 'Europe/Berlin')::date) > current_date
                """, tenant)).as("at least one Frist lies in the coming days").isGreaterThan(0);

        // The Beleg card is not empty: amount, date and supplier were read from the text.
        assertThat(count("""
                select count(*) from documents d
                 where d.tenant_id = ? and d.deleted_at is null
                   and exists (select 1 from document_extractions e where e.document_id = d.id and e.field_key = 'Brutto')
                   and exists (select 1 from document_extractions e where e.document_id = d.id and e.field_key = 'Rechnungsdatum')
                   and exists (select 1 from document_extractions e where e.document_id = d.id and e.field_key = 'Lieferant')
                """, tenant)).as("some vouchers carry amount, date and supplier").isGreaterThan(0);

        // The office queue has been worked in; not every voucher is a fresh arrival.
        assertThat(count("select count(distinct beleg_status) from documents where tenant_id = ?", tenant))
                .as("the Beleg statuses are distributed").isGreaterThanOrEqualTo(3);
        assertThat(count("select count(*) from documents where tenant_id = ? and beleg_status = 'NEU'", tenant))
                .as("not all NEU").isLessThan(count("select count(*) from documents where tenant_id = ?", tenant));

        // The buttons and screens the visitor opens read this cabinet, and the Posteingang is not empty.
        mockMvc.perform(get("/api/v1/workspaces").cookie(cookie))
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/v1/demo/status").cookie(cookie))
                .andExpect(jsonPath("$.demo").value(true)).andExpect(jsonPath("$.persona").value("ADVISOR"));
        mockMvc.perform(get("/api/v1/documents").cookie(cookie))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(20)))
                .andExpect(jsonPath("$.content[0].id").exists());
        mockMvc.perform(get("/api/v1/documents/beleg-counts").cookie(cookie))
                .andExpect(jsonPath("$.ohneMandant").value(greaterThan(0)))
                .andExpect(jsonPath("$.neu").value(greaterThan(0)));

        mockMvc.perform(post("/api/v1/demo/exit").with(csrf()).cookie(cookie)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from tenants where id = ?", Integer.class, tenant)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from documents where tenant_id = ?", Integer.class, tenant)).isZero();
    }

    @Test
    @DisplayName("running the demo letters through the pipeline again creates no duplicate documents, tasks or deadlines")
    void demoIsIdempotent() throws Exception {
        Cookie cookie = start("advisor", "10.1.0.6");
        UUID tenant = tenantOf(cookie);
        int documents = count("select count(*) from documents where tenant_id = ? and deleted_at is null", tenant);
        int tasks = count("select count(*) from tasks where tenant_id = ? and deleted_at is null", tenant);
        int deadlines = count("select count(*) from task_deadlines where tenant_id = ?", tenant);
        List<UUID> ids = RequestContext.runAs(tenant,
                () -> jdbc.queryForList("select id from documents where tenant_id = ? and deleted_at is null",
                        UUID.class, tenant));
        assertThat(ids).isNotEmpty();

        // The scheduler may deliver the same events again after a restart; the demo must look the same.
        RequestContext.runAs(tenant, () -> {
            for (UUID id : ids) {
                pipeline.advance(id);
                orchestrator.orchestrate(id);
            }
            return null;
        });

        assertThat(count("select count(*) from documents where tenant_id = ? and deleted_at is null", tenant))
                .isEqualTo(documents);
        assertThat(count("select count(*) from tasks where tenant_id = ? and deleted_at is null", tenant))
                .isEqualTo(tasks);
        assertThat(count("select count(*) from task_deadlines where tenant_id = ?", tenant))
                .isEqualTo(deadlines);
    }

    @Test
    @DisplayName("an unknown cabinet is refused, and one address cannot open a cabinet again and again")
    void startIsLimited() throws Exception {
        mockMvc.perform(post("/api/v1/public/demo/start").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"persona\":\"nonsense\"}")).andExpect(status().isUnprocessableEntity());
        for (int i = 0; i < DemoService.MAX_STARTS_PER_IP_PER_HOUR; i++) {
            Cookie cookie = start("freelancer", "10.1.9.9");
            mockMvc.perform(post("/api/v1/demo/exit").with(csrf()).cookie(cookie)).andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/public/demo/start").with(csrf()).with(request -> { request.setRemoteAddr("10.1.9.9"); return request; })
                .contentType(MediaType.APPLICATION_JSON).content("{\"persona\":\"freelancer\"}")).andExpect(status().isTooManyRequests());
    }
}
