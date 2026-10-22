package de.buerokratiecopilot.mandantrequests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.orchestration.OrchestrationWatchdog;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TenantContextExtension;

/**
 * The client request from the voucher card (Kanzlei edition, §5.7).
 *
 * <p>What is pinned here is what a screen cannot prove by itself: the German letter really names the
 * client, the voucher date, number and gross amount; a deployment without mail answers 409 and leaves the
 * draft exactly where it was; the seven-day reminder moves the request to OVERDUE and tells the person who
 * wrote it exactly once; and a second account can neither see nor send somebody else's request.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(TenantContextExtension.class)
class MandantRequestIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrchestrationWatchdog watchdog;

    private record Account(Cookie cookie, UUID tenantId, UUID userId) {
    }

    // ------------------------------------------------------------------ setup

    private Account register() throws Exception {
        String email = "anfrage-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Kanzlei Anfrage","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(body(registration), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(body(registration), "$.userId"));
        RequestContext.runAs(tenantId, () -> jdbc.update("""
                update users set status = 'ACTIVE', email_verified_at = now() where id = ?
                """, userId));
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Account(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    private static String body(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private UUID mandant(Account account, String name, String email) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (tenant_id, contact_type, display_name, email)
                values (?, 'ORGANIZATION', ?, ?)
                returning id
                """, UUID.class, account.tenantId(), name, email));
    }

    private UUID document(Account account, UUID mandantId, LocalDate date, String number) {
        return RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                insert into documents (tenant_id, original_filename, internal_name, mime_type, size_bytes,
                                       content_hash, source, processing_status, review_status, beleg_status,
                                       mandant_id, document_date, document_number)
                values (?, 'rechnung.pdf', 'rechnung.pdf', 'application/pdf', 10,
                        md5(random()::text) || md5(random()::text), 'UPLOAD', 'READY', 'NOT_REQUIRED',
                        'OFFEN', ?, ?, ?)
                returning id
                """, UUID.class, account.tenantId(), mandantId, date, number));
    }

    private void grossAmount(Account account, UUID documentId, String value) {
        RequestContext.runAs(account.tenantId(), () -> jdbc.update("""
                insert into document_extractions (document_id, field_key, field_value, normalized_value,
                                                  value_type, confidence, source_kind, source_snippet,
                                                  extractor)
                values (?, 'Brutto', ?, ?, 'AMOUNT', 0.99, 'TEXT', 'Bruttobetrag 1.190,00 EUR', 'RULE')
                """, documentId, value + " €", value));
    }

    private MvcResult createRequest(Account account, UUID mandantId, UUID documentId, List<String> missing)
            throws Exception {
        String payload = """
                {"mandantId":"%s","documentId":%s,"missing":%s}
                """.formatted(mandantId, documentId == null ? "null" : "\"" + documentId + "\"",
                missing == null ? "[]" : missing.stream().map(item -> "\"" + item + "\"")
                        .collect(java.util.stream.Collectors.joining(",", "[", "]")));
        return mockMvc.perform(post("/api/v1/mandant-requests").with(csrf()).cookie(account.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private UUID createDraft(Account account, UUID mandantId, UUID documentId) throws Exception {
        MvcResult created = createRequest(account, mandantId, documentId, List.of("AMOUNT", "DATE"));
        return UUID.fromString(JsonPath.read(body(created), "$.id"));
    }

    private Map<String, Object> row(Account account, UUID id) {
        return RequestContext.runAs(account.tenantId(),
                () -> jdbc.queryForMap("select * from mandant_requests where id = ?", id));
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("the German letter names the client, the voucher date, number and gross amount")
    void createsALetterFromTheClientAndTheVoucher() throws Exception {
        Account account = register();
        UUID mandantId = mandant(account, "Kartenhaus GmbH", "buchhaltung@kartenhaus.example");
        UUID documentId = document(account, mandantId, LocalDate.of(2026, 2, 3), "RE-2026-001");
        grossAmount(account, documentId, "1190.00");

        MvcResult created = createRequest(account, mandantId, documentId, List.of("AMOUNT", "DATE"));
        UUID id = UUID.fromString(JsonPath.read(body(created), "$.id"));

        String subject = JsonPath.read(body(created), "$.subject");
        String text = JsonPath.read(body(created), "$.body");
        assertThat(JsonPath.<String>read(body(created), "$.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<String>read(body(created), "$.mandantName")).isEqualTo("Kartenhaus GmbH");
        assertThat(JsonPath.<String>read(body(created), "$.mandantEmail"))
                .isEqualTo("buchhaltung@kartenhaus.example");

        assertThat(subject).contains("Kartenhaus GmbH").contains("03.02.2026");
        assertThat(text).contains("Sehr geehrte Damen und Herren")
                .contains("für die Buchung der Rechnung vom 03.02.2026")
                .contains("Rechnungsnummer: RE-2026-001")
                .contains("Betrag: 1.190,00 €")
                .contains("der Rechnungsbetrag")
                .contains("das Rechnungsdatum")
                .contains("Kanzlei Anfrage");

        Integer audited = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from audit_events
                 where entity_id = ? and action = 'MANDANT_REQUEST_CREATED'
                """, Integer.class, id));
        assertThat(audited).isEqualTo(1);
    }

    @Test
    @DisplayName("without a mail provider sending answers 409 and leaves the draft untouched")
    void sendingWithoutProviderIsAConflictAndKeepsTheDraft() throws Exception {
        Account account = register();
        UUID mandantId = mandant(account, "Kartenhaus GmbH", "buchhaltung@kartenhaus.example");
        UUID id = createDraft(account, mandantId, null);

        mockMvc.perform(post("/api/v1/mandant-requests/{id}/send", id).with(csrf())
                        .cookie(account.cookie()).header("Accept-Language", "de"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAIL_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("E-Mail-Versand")));

        Map<String, Object> after = row(account, id);
        assertThat(after.get("status")).isEqualTo("DRAFT");
        assertThat(after.get("sent_at")).isNull();
        Integer sentAudit = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from audit_events where entity_id = ? and action = 'MANDANT_REQUEST_SENT'
                """, Integer.class, id));
        assertThat(sentAudit).as("a failed send is not recorded as a success").isZero();
    }

    @Test
    @DisplayName("a draft can be edited, a sent request may not be rewritten")
    void editsOnlyDrafts() throws Exception {
        Account account = register();
        UUID mandantId = mandant(account, "Kartenhaus GmbH", null);
        UUID id = createDraft(account, mandantId, null);

        mockMvc.perform(patch("/api/v1/mandant-requests/{id}", id).with(csrf()).cookie(account.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Sehr geehrte Damen und Herren,\\n\\nbitte senden Sie uns den Beleg.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("bitte senden Sie uns")));

        RequestContext.runAs(account.tenantId(), () -> jdbc.update("""
                update mandant_requests set status = 'SENT', sent_at = now() where id = ?
                """, id));
        mockMvc.perform(patch("/api/v1/mandant-requests/{id}", id).with(csrf()).cookie(account.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"anders\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("mark-answered closes the request and records when the answer came")
    void marksAnswered() throws Exception {
        Account account = register();
        UUID mandantId = mandant(account, "Kartenhaus GmbH", "buchhaltung@kartenhaus.example");
        UUID id = createDraft(account, mandantId, null);
        RequestContext.runAs(account.tenantId(), () -> jdbc.update("""
                update mandant_requests set status = 'SENT', sent_at = now() where id = ?
                """, id));

        mockMvc.perform(post("/api/v1/mandant-requests/{id}/mark-answered", id).with(csrf())
                        .cookie(account.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"))
                .andExpect(jsonPath("$.answeredAt").isNotEmpty())
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        Integer audited = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from audit_events where entity_id = ? and action = 'MANDANT_REQUEST_ANSWERED'
                """, Integer.class, id));
        assertThat(audited).isEqualTo(1);
    }

    @Test
    @DisplayName("after seven days without an answer the request is OVERDUE and the author is told once")
    void remindsOnceAfterSevenDays() throws Exception {
        Account account = register();
        UUID mandantId = mandant(account, "Kartenhaus GmbH", "buchhaltung@kartenhaus.example");
        UUID id = createDraft(account, mandantId, null);
        RequestContext.runAs(account.tenantId(), () -> jdbc.update("""
                update mandant_requests set status = 'SENT', sent_at = now() - interval '8 days'
                 where id = ?
                """, id));

        int first = watchdog.remindMandantRequests(account.tenantId());
        assertThat(first).as("one request was old enough").isEqualTo(1);

        Map<String, Object> after = row(account, id);
        assertThat(after.get("status")).isEqualTo("OVERDUE");
        assertThat(((Number) after.get("reminder_count")).intValue()).isEqualTo(1);
        assertThat(after.get("last_reminder_at")).isNotNull();

        String dedupKey = "mandant-request-reminder-" + id;
        Integer notifications = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from notifications
                 where dedup_key = ? and user_id = ? and notification_type = 'mandant.request_overdue'
                """, Integer.class, dedupKey, account.userId()));
        assertThat(notifications).as("the author was told").isEqualTo(1);

        // The watchdog runs every minute. A second notification for the same request is how a reminder
        // becomes noise people stop reading.
        int second = watchdog.remindMandantRequests(account.tenantId());
        assertThat(second).isZero();
        Integer stillOne = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from notifications where dedup_key = ?
                """, Integer.class, dedupKey));
        assertThat(stillOne).isEqualTo(1);
        assertThat(((Number) row(account, id).get("reminder_count")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("a request of one account is invisible and unusable to another")
    void isolatesAccounts() throws Exception {
        Account owner = register();
        UUID mandantId = mandant(owner, "Kartenhaus GmbH", "buchhaltung@kartenhaus.example");
        UUID id = createDraft(owner, mandantId, null);

        Account stranger = register();

        mockMvc.perform(get("/api/v1/mandant-requests/{id}", id).cookie(stranger.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/mandant-requests/{id}/send", id).with(csrf())
                        .cookie(stranger.cookie()))
                .andExpect(status().isNotFound());

        String listed = mockMvc.perform(get("/api/v1/mandant-requests").cookie(stranger.cookie()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(listed).doesNotContain(id.toString());

        String own = mockMvc.perform(get("/api/v1/mandant-requests").cookie(owner.cookie()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(own).contains(id.toString());
    }

    @Test
    @DisplayName("the preview fills the dialog without storing a request")
    void previewsWithoutStoring() throws Exception {
        Account account = register();
        UUID mandantId = mandant(account, "Kartenhaus GmbH", "buchhaltung@kartenhaus.example");
        UUID documentId = document(account, mandantId, LocalDate.of(2026, 2, 3), "RE-2026-001");

        String payload = """
                {"mandantId":"%s","documentId":"%s","missing":["NUMBER","ATTACHMENT"]}
                """.formatted(mandantId, documentId);
        mockMvc.perform(post("/api/v1/mandant-requests/preview").with(csrf()).cookie(account.cookie())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(org.hamcrest.Matchers.containsString("03.02.2026")))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("die Rechnungsnummer")))
                .andExpect(jsonPath("$.body").value(org.hamcrest.Matchers.containsString("der Beleg als Anlage")));

        Integer count = RequestContext.runAs(account.tenantId(), () -> jdbc.queryForObject("""
                select count(*) from mandant_requests where mandant_id = ?
                """, Integer.class, mandantId));
        assertThat(count).isZero();
    }
}
