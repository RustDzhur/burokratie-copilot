package de.buerokratiecopilot.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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

import de.buerokratiecopilot.shared.context.RequestContext;

/** HD-GDPR-01: find, copy, correct and delete within limits - with a record of each request and an audit line. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DataSubjectRequestIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private record Account(UUID tenantId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "dsr-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"D\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie cookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andReturn().getResponse().getCookie("bc_session");
        return new Account(tenantId, cookie);
    }

    private UUID person(Account a) {
        return RequestContext.runAs(a.tenantId(), () -> {
            UUID id = jdbc.queryForObject("""
                    insert into contacts (id, tenant_id, contact_type, display_name, first_name, last_name, email, phone, notes)
                    values (gen_random_uuid(), ?, 'PERSON', 'Erika Mustermann', 'Erika', 'Mustermann', 'erika@example.de', '+49 30 1234', 'zahlt spät')
                    returning id
                    """, UUID.class, a.tenantId());
            jdbc.update("insert into contact_identifiers (tenant_id, contact_id, kind, value, normalized_value) values (?, ?, 'EMAIL', 'erika@example.de', 'erika@example.de')",
                    a.tenantId(), id);
            jdbc.update("""
                    insert into contracts (tenant_id, title, contract_type, status, counterparty_contact_id)
                    values (?, 'Mietvertrag', 'RENT', 'ACTIVE', ?)
                    """, a.tenantId(), id);
            jdbc.update("""
                    insert into invoices (tenant_id, direction, invoice_number, contact_id, gross_amount, currency)
                    values (?, 'INCOMING', 'R-1', ?, 100.00, 'EUR')
                    """, a.tenantId(), id);
            return id;
        });
    }

    private String body(Account a, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String content, int expected) throws Exception {
        MvcResult r = mockMvc.perform(b.with(csrf()).cookie(a.cookie()).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(expected);
        return r.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("find by name, e-mail or identifier; see what is held; a copy contains the records; every request is recorded and audited")
    void findAndExport() throws Exception {
        Account a = newTenant();
        UUID id = person(a);

        assertThat((List<?>) JsonPath.read(body(a, get("/api/v1/privacy/subjects/search?q=erika@example"), "", 200), "$")).hasSize(1);
        assertThat((List<?>) JsonPath.read(body(a, get("/api/v1/privacy/subjects/search?q=Mustermann"), "", 200), "$")).hasSize(1);
        body(a, get("/api/v1/privacy/subjects/search?q=x"), "", 400);

        String overview = body(a, get("/api/v1/privacy/subjects/" + id), "", 200);
        assertThat((Integer) JsonPath.read(overview, "$.contracts")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(overview, "$.invoices")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(overview, "$.identifiers")).isEqualTo(1);

        String copy = body(a, get("/api/v1/privacy/subjects/" + id + "/export"), "", 200);
        assertThat((String) JsonPath.read(copy, "$.contact.email")).isEqualTo("erika@example.de");
        assertThat((List<?>) JsonPath.read(copy, "$.contracts")).hasSize(1);
        assertThat((List<?>) JsonPath.read(copy, "$.invoices")).hasSize(1);

        List<String> kinds = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForList(
                "select kind from data_subject_requests where contact_id = ? order by created_at", String.class, id));
        assertThat(kinds).containsExactly("ACCESS", "EXPORT");
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from audit_events where tenant_id = ? and action = 'DATA_SUBJECT_EXPORT'", Integer.class, a.tenantId()))).isEqualTo(1);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select result::text from data_subject_requests where contact_id = ? and kind = 'EXPORT'", String.class, id)))
                .as("the record holds counts, not the person's data").doesNotContain("erika@example.de").doesNotContain("Mustermann");
    }

    @Test
    @DisplayName("a correction changes only the fields allowed, and says which fields it changed")
    void rectification() throws Exception {
        Account a = newTenant();
        UUID id = person(a);

        String result = body(a, put("/api/v1/privacy/subjects/" + id + "/rectification"),
                "{\"fields\":{\"phone\":\"+49 30 9999\",\"notes\":null},\"reason\":\"Auf Wunsch der Person\"}", 200);
        assertThat(JsonPath.<List<String>>read(result, "$.changedFields")).containsExactlyInAnyOrder("phone", "notes");
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForMap("select phone, notes from contacts where id = ?", id)))
                .containsEntry("phone", "+49 30 9999").containsEntry("notes", null);

        body(a, put("/api/v1/privacy/subjects/" + id + "/rectification"), "{\"fields\":{\"tenant_id\":\"" + UUID.randomUUID() + "\"}}", 400);
        body(a, put("/api/v1/privacy/subjects/" + id + "/rectification"), "{\"fields\":{\"display_name\":\" \"}}", 400);
        body(a, put("/api/v1/privacy/subjects/" + id + "/rectification"), "{\"fields\":{}}", 400);
    }

    @Test
    @DisplayName("deletion anonymises the person and the identifiers, keeps the business records and names them; it needs a reason")
    void limitedErasure() throws Exception {
        Account a = newTenant();
        UUID id = person(a);

        body(a, post("/api/v1/privacy/subjects/" + id + "/erasure"), "{}", 400);
        String result = body(a, post("/api/v1/privacy/subjects/" + id + "/erasure"), "{\"reason\":\"Löschverlangen der Person\"}", 200);

        assertThat((Integer) JsonPath.read(result, "$.retained.contracts")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(result, "$.retained.invoices")).isEqualTo(1);
        assertThat((String) JsonPath.read(result, "$.retainedReason")).contains("Verantwortliche");
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForMap(
                "select display_name, first_name, email::text as email, phone, deleted_at is not null as gone from contacts where id = ?", id)))
                .containsEntry("display_name", "Gelöschte Person").containsEntry("first_name", null).containsEntry("email", null)
                .containsEntry("phone", null).containsEntry("gone", true);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from contact_identifiers where contact_id = ?", Integer.class, id))).isZero();
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from contracts where counterparty_contact_id = ?", Integer.class, id))).as("business records are not deleted").isEqualTo(1);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from audit_events where tenant_id = ? and action = 'DATA_SUBJECT_ERASURE'", Integer.class, a.tenantId()))).isEqualTo(1);
        body(a, get("/api/v1/privacy/subjects/" + id), "", 404);
    }

    @Test
    @DisplayName("a document under legal hold stops the deletion; another workspace cannot reach the person")
    void legalHoldAndIsolation() throws Exception {
        Account a = newTenant();
        UUID id = person(a);
        RequestContext.runAs(a.tenantId(), () -> {
            UUID doc = jdbc.queryForObject("""
                    insert into documents (id, tenant_id, original_filename, internal_name, mime_type, size_bytes, content_hash, legal_hold)
                    values (gen_random_uuid(), ?, 'Akte.pdf', 'akte', 'application/pdf', 10, repeat('a', 64), true) returning id
                    """, UUID.class, a.tenantId());
            jdbc.update("""
                    insert into document_matches (tenant_id, document_id, match_type, target_id, score, status, decided_at)
                    values (?, ?, 'CONTACT', ?, 0.9, 'CONFIRMED', now())
                    """, a.tenantId(), doc, id);
            return null;
        });

        body(a, post("/api/v1/privacy/subjects/" + id + "/erasure"), "{\"reason\":\"Löschverlangen\"}", 409);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("select deleted_at is null from contacts where id = ?", Boolean.class, id))).isTrue();

        Account stranger = newTenant();
        body(stranger, get("/api/v1/privacy/subjects/" + id), "", 404);
        body(stranger, get("/api/v1/privacy/subjects/" + id + "/export"), "", 404);
        assertThat((List<?>) JsonPath.read(body(stranger, get("/api/v1/privacy/subjects/search?q=Mustermann"), "", 200), "$")).isEmpty();
    }
}
