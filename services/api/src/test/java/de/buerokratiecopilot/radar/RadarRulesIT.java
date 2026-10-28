package de.buerokratiecopilot.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDocuments;

/**
 * RD-03 and RD-04: each of the first six rules, with a scenario where it fires and one where it must stay silent, and the
 * engine around them (severity from the rule, announcement once, automatic resolution of a situation that is gone).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RadarRulesIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RadarEngine engine;

    private record Account(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "rules-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Rules Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(registration.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email)))
                .andExpect(status().isOk()).andReturn();
        return new Account(tenantId, userId, login.getResponse().getCookie("bc_session"));
    }

    private UUID upload(Account a, String text) throws Exception {
        MvcResult r = mockMvc.perform(multipart("/api/v1/documents")
                        .file(new MockMultipartFile("file", "Dokument.pdf", "application/pdf", TestDocuments.pdf(text)))
                        .with(csrf()).cookie(a.cookie()))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.document.id"));
    }

    private UUID contact(Account a, String name) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into contacts (id, tenant_id, contact_type, display_name, organization_name, is_customer, is_supplier)
                values (gen_random_uuid(), ?, 'ORGANIZATION', ?, ?, false, true) returning id
                """, UUID.class, a.tenantId(), name, name));
    }

    private UUID invoice(Account a, UUID contactId, UUID documentId, String number, String gross, String currency, String issued,
                         boolean bankChanged, String iban) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into invoices (id, tenant_id, direction, contact_id, document_id, invoice_number, gross_amount, currency,
                                      issue_date, bank_details_changed, iban)
                values (gen_random_uuid(), ?, 'INCOMING', ?, ?, ?, ?::numeric, ?, ?::date, ?, ?) returning id
                """, UUID.class, a.tenantId(), contactId, documentId, number, gross, currency, issued, bankChanged, iban));
    }

    private List<Map<String, Object>> findings(Account a, String ruleId) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForList(
                "select * from radar_findings where tenant_id = ? and rule_id = ? order by created_at", a.tenantId(), ruleId));
    }

    private RadarEngine.Report onDocument(Account a, UUID documentId) {
        return RequestContext.runAs(a.tenantId(), () -> engine.evaluateDocument(a.tenantId(), documentId));
    }

    private RadarEngine.Report sweep(Account a) {
        return RequestContext.runAs(a.tenantId(), () -> engine.sweep(a.tenantId()));
    }

    // ------------------------------------------------------------------ the engine

    @Test
    @DisplayName("every rule describes itself completely, and the ids are unique")
    void rulesAreDescribed() {
        List<RadarRule.Description> all = engine.descriptions();
        assertThat(all).extracting(RadarRule.Description::id).containsExactlyInAnyOrder("A1", "B1", "B2", "B3", "C1", "D1", "E1");
        assertThat(all).allSatisfy(description -> assertThat(description.isComplete()).as(description.id()).isTrue());
    }

    // ------------------------------------------------------------------ B1

    @Test
    @DisplayName("B1: a higher amount than the previous invoice of the sender raises a finding with both invoices and the contract price")
    void amountChanged() throws Exception {
        Account a = newTenant();
        UUID sender = contact(a, "Stadtwerke");
        UUID d1 = upload(a, "Rechnung eins");
        UUID d2 = upload(a, "Rechnung zwei");
        invoice(a, sender, d1, "R-1", "100.00", "EUR", "2026-01-10", false, null);
        UUID second = invoice(a, sender, d2, "R-2", "140.00", "EUR", "2026-02-10", false, null);
        UUID contract = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into contracts (id, tenant_id, title, contract_type, status, cost_amount, cost_interval)
                values (gen_random_uuid(), ?, 'Strom', 'ENERGY', 'ACTIVE', 100.00, 'MONTHLY') returning id
                """, UUID.class, a.tenantId()));
        RequestContext.runAs(a.tenantId(), () -> jdbc.update("update invoices set contract_id = ? where id = ?", contract, second));

        assertThat(onDocument(a, d2).created()).isEqualTo(1);

        Map<String, Object> finding = findings(a, "B1").get(0);
        assertThat(finding.get("severity")).isEqualTo("MEDIUM");
        assertThat(finding.get("finding_type")).isEqualTo("INVOICE_ANOMALY");
        assertThat((String) finding.get("description")).contains("140.00").contains("100.00").contains("40.0 %")
                .as("a difference is not called an error").doesNotContain("fehlerhaft").doesNotContain("falsch");
        assertThat(finding.get("reason_codes").toString()).contains("AMOUNT_DIFFERS_FROM_PREVIOUS_INVOICE")
                .contains("AMOUNT_DIFFERS_FROM_CONTRACT_PRICE");
        List<Map<String, Object>> evidence = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForList(
                "select role, document_id from radar_finding_evidence where finding_id = ?", finding.get("id")));
        assertThat(evidence).extracting(row -> row.get("document_id")).contains(d1, d2);
        assertThat(evidence).extracting(row -> row.get("role")).contains("PRIMARY", "COMPARISON");

        // The same document evaluated again is the same finding.
        assertThat(onDocument(a, d2).created()).isZero();
        assertThat(findings(a, "B1")).hasSize(1);
    }

    @Test
    @DisplayName("B1: a small change, another currency, another sender, the first invoice and a very large change")
    void amountChangedStaysSilentOrEscalates() throws Exception {
        Account a = newTenant();
        UUID sender = contact(a, "Stadtwerke");
        UUID other = contact(a, "Andere");
        UUID d1 = upload(a, "A eins");
        UUID d2 = upload(a, "A zwei");
        UUID d3 = upload(a, "A drei");
        UUID d4 = upload(a, "A vier");
        UUID d5 = upload(a, "A fuenf");
        invoice(a, sender, d1, "R-1", "100.00", "EUR", "2026-01-10", false, null);

        assertThat(onDocument(a, d1).created()).as("the first invoice has nothing to be compared with").isZero();

        invoice(a, sender, d2, "R-2", "105.00", "EUR", "2026-02-10", false, null);
        assertThat(onDocument(a, d2).created()).as("5 % is below the starting threshold").isZero();

        invoice(a, sender, d3, "R-3", "400.00", "USD", "2026-03-10", false, null);
        assertThat(onDocument(a, d3).created()).as("another currency is not comparable").isZero();

        invoice(a, other, d4, "R-4", "900.00", "EUR", "2026-03-11", false, null);
        assertThat(onDocument(a, d4).created()).as("another sender").isZero();

        invoice(a, sender, d5, "R-5", "400.00", "EUR", "2026-04-10", false, null);
        assertThat(onDocument(a, d5).created()).isEqualTo(1);
        assertThat(findings(a, "B1").get(0).get("severity")).as("compared with 105: +281 %").isEqualTo("HIGH");
    }

    // ------------------------------------------------------------------ B2

    @Test
    @DisplayName("B2: the same invoice number of the same sender in two documents; equal amount is rated higher; another sender is silent")
    void duplicates() throws Exception {
        Account a = newTenant();
        UUID sender = contact(a, "Lieferant");
        UUID other = contact(a, "Anderer");
        UUID d1 = upload(a, "Dup eins");
        UUID d2 = upload(a, "Dup zwei");
        UUID d3 = upload(a, "Dup drei");
        UUID d4 = upload(a, "Dup vier");
        invoice(a, sender, d1, "2026-77", "50.00", "EUR", "2026-01-10", false, null);
        invoice(a, sender, d2, "2026-77", "50.00", "EUR", "2026-01-12", false, null);
        invoice(a, sender, d3, "2026-78", "10.00", "EUR", "2026-01-13", false, null);
        invoice(a, sender, d4, "2026-78", "10.50", "EUR", "2026-01-14", false, null);
        UUID d5 = upload(a, "Dup fuenf");
        invoice(a, other, d5, "2026-77", "50.00", "EUR", "2026-01-15", false, null);

        assertThat(onDocument(a, d2).created()).isEqualTo(1);
        assertThat(onDocument(a, d4).created()).isEqualTo(1);
        assertThat(onDocument(a, d5).created()).as("the same number at another sender is no duplicate").isZero();

        List<Map<String, Object>> found = findings(a, "B2");
        assertThat(found).hasSize(2);
        assertThat(found).extracting(row -> row.get("severity")).containsExactlyInAnyOrder("HIGH", "MEDIUM");

        // Evaluating the other document of the pair finds the same finding, not a second one.
        assertThat(onDocument(a, d1).created()).isZero();
        assertThat(findings(a, "B2")).hasSize(2);
    }

    // ------------------------------------------------------------------ B3

    @Test
    @DisplayName("B3: a changed account is a high finding that does not accuse; an unchanged one is silent")
    void bankDetails() throws Exception {
        Account a = newTenant();
        UUID sender = contact(a, "Lieferant");
        UUID d1 = upload(a, "Bank eins");
        UUID d2 = upload(a, "Bank zwei");
        invoice(a, sender, d1, "B-1", "20.00", "EUR", "2026-01-10", false, "DE89370400440532013000");
        invoice(a, sender, d2, "B-2", "20.00", "EUR", "2026-02-10", true, "DE02120300000000202051");

        assertThat(onDocument(a, d1).created()).isZero();
        assertThat(onDocument(a, d2).created()).isEqualTo(1);

        Map<String, Object> finding = findings(a, "B3").get(0);
        assertThat(finding.get("severity")).isEqualTo("HIGH");
        assertThat((String) finding.get("description")).contains("DE02120300000000202051").doesNotContain("Betrug");
    }

    // ------------------------------------------------------------------ D1

    @Test
    @DisplayName("D1: an open overdue task raises a finding; the platform resolves it when the task is done; due and done tasks are silent")
    void overdueTasks() throws Exception {
        Account a = newTenant();
        UUID overdue = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into tasks (id, tenant_id, title, due_at) values (gen_random_uuid(), ?, 'Miete zahlen', now() - interval '10 days')
                returning id
                """, UUID.class, a.tenantId()));
        RequestContext.runAs(a.tenantId(), () -> {
            jdbc.update("insert into tasks (id, tenant_id, title, due_at) values (gen_random_uuid(), ?, 'Später', now() + interval '1 day')",
                    a.tenantId());
            jdbc.update("insert into tasks (id, tenant_id, title, due_at, status, completed_at) values (gen_random_uuid(), ?, 'Erledigt', now() - interval '3 days', 'DONE', now())",
                    a.tenantId());
            jdbc.update("insert into tasks (id, tenant_id, title) values (gen_random_uuid(), ?, 'Ohne Datum')", a.tenantId());
            return null;
        });

        assertThat(sweep(a).created()).isEqualTo(1);
        Map<String, Object> finding = findings(a, "D1").get(0);
        assertThat(finding.get("finding_key")).isEqualTo("D1:" + overdue);
        assertThat(finding.get("severity")).as("10 days overdue").isEqualTo("HIGH");

        RequestContext.runAs(a.tenantId(), () -> jdbc.update("update tasks set status = 'DONE', completed_at = now() where id = ?", overdue));
        RadarEngine.Report report = sweep(a);
        assertThat(report.resolved()).isEqualTo(1);
        assertThat(findings(a, "D1").get(0).get("status")).isEqualTo("RESOLVED");
    }

    // ------------------------------------------------------------------ A1

    @Test
    @DisplayName("A1: a confirmed notice deadline warns, close ones are rated higher, an unconfirmed one is only information, far and past ones are silent")
    void noticeDeadline() throws Exception {
        Account a = newTenant();
        RequestContext.runAs(a.tenantId(), () -> {
            insertContract(a, "Nah bestätigt", 10, true, "ACTIVE");
            insertContract(a, "Mittel bestätigt", 30, true, "ACTIVE");
            insertContract(a, "Nah unbestätigt", 10, false, "ACTIVE");
            insertContract(a, "Weit", 200, true, "ACTIVE");
            insertContract(a, "Vorbei", -1, true, "ACTIVE");
            insertContract(a, "Beendet", 10, true, "TERMINATED");
            return null;
        });

        assertThat(sweep(a).created()).isEqualTo(3);

        Map<String, String> bySeverity = new java.util.HashMap<>();
        for (Map<String, Object> row : findings(a, "A1")) {
            bySeverity.put((String) row.get("title"), (String) row.get("severity"));
        }
        assertThat(bySeverity).containsEntry("Kündigungsfrist nähert sich: Nah bestätigt", "HIGH")
                .containsEntry("Kündigungsfrist nähert sich: Mittel bestätigt", "MEDIUM")
                .containsEntry("Kündigungsfrist (unbestätigt): Nah unbestätigt", "INFO");
        assertThat(findings(a, "A1").stream().filter(row -> row.get("severity").equals("INFO")).findFirst().orElseThrow()
                .get("reason_codes").toString()).contains("DEADLINE_UNCONFIRMED");
    }

    private void insertContract(Account a, String title, int daysUntilDeadline, boolean confirmed, String status) {
        jdbc.update("""
                insert into contracts (id, tenant_id, title, contract_type, status, notice_deadline_date, data_verified_at)
                values (gen_random_uuid(), ?, ?, 'INTERNET', ?, current_date + ?::int, case when ? then now() end)
                """, a.tenantId(), title, status, daysUntilDeadline, confirmed);
    }

    // ------------------------------------------------------------------ C1

    @Test
    @DisplayName("C1: an open case lacks its mandatory documents until they are linked; a young case and a case without a type are silent")
    void missingDocuments() throws Exception {
        Account a = newTenant();
        UUID caseType = jdbc.queryForObject("select id from case_types where tenant_id is null and code = 'kuendigung'", UUID.class);
        UUID contractType = jdbc.queryForObject("select id from document_types where code = 'vertrag'", UUID.class);
        UUID confirmationType = jdbc.queryForObject(
                "select id from document_types where code = 'kuendigungsbestaetigung'", UUID.class);
        UUID old = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title, status, priority, opened_at, case_type_id)
                values (gen_random_uuid(), ?, 'K-1', 'Kündigung Internet', 'IN_PROGRESS', 'NORMAL', now() - interval '5 days', ?)
                returning id
                """, UUID.class, a.tenantId(), caseType));
        RequestContext.runAs(a.tenantId(), () -> {
            jdbc.update("""
                    insert into cases (id, tenant_id, reference, title, status, priority, opened_at, case_type_id)
                    values (gen_random_uuid(), ?, 'K-2', 'Neu', 'NEW', 'NORMAL', now(), ?)
                    """, a.tenantId(), caseType);
            jdbc.update("""
                    insert into cases (id, tenant_id, reference, title, status, priority, opened_at)
                    values (gen_random_uuid(), ?, 'K-3', 'Ohne Typ', 'NEW', 'NORMAL', now() - interval '9 days')
                    """, a.tenantId());
            return null;
        });

        assertThat(sweep(a).created()).isEqualTo(1);
        Map<String, Object> finding = findings(a, "C1").get(0);
        assertThat(finding.get("finding_key")).isEqualTo("C1:" + old);
        assertThat(finding.get("severity")).isEqualTo("LOW");
        assertThat((String) finding.get("description")).contains("Vertrag").contains("Kündigungsbestätigung");

        UUID d1 = upload(a, "Vertrag Internet");
        UUID d2 = upload(a, "Kuendigung bestaetigt");
        RequestContext.runAs(a.tenantId(), () -> {
            jdbc.update("update documents set document_type_id = ? where id = ?", contractType, d1);
            jdbc.update("update documents set document_type_id = ? where id = ?", confirmationType, d2);
            jdbc.update("insert into case_documents (case_id, document_id, tenant_id) values (?, ?, ?)", old, d1, a.tenantId());
            jdbc.update("insert into case_documents (case_id, document_id, tenant_id) values (?, ?, ?)", old, d2, a.tenantId());
            return null;
        });

        RadarEngine.Report report = sweep(a);
        assertThat(report.resolved()).as("the documents are there: the situation is gone").isEqualTo(1);
        assertThat(findings(a, "C1").get(0).get("status")).isEqualTo("RESOLVED");
    }

    // ------------------------------------------------------------------ the engine around the rules

    @Test
    @DisplayName("a finding is announced once to the owner, only from the configured severity")
    void announcedOnce() throws Exception {
        Account a = newTenant();
        UUID sender = contact(a, "Lieferant");
        UUID d1 = upload(a, "Ank eins");
        UUID d2 = upload(a, "Ank zwei");
        invoice(a, sender, d1, "N-1", "100.00", "EUR", "2026-01-10", false, null);
        invoice(a, sender, d2, "N-2", "300.00", "EUR", "2026-02-10", false, null);

        onDocument(a, d2);
        onDocument(a, d2);

        Integer notifications = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from notifications where tenant_id = ? and category = 'RADAR'", Integer.class, a.tenantId()));
        assertThat(notifications).isEqualTo(1);

        // INFO is below the starting level of MEDIUM: found, filed, not announced.
        RequestContext.runAs(a.tenantId(), () -> insertContract(a, "Unbestätigt", 5, false, "ACTIVE"));
        sweep(a);
        Integer after = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(
                "select count(*) from notifications where tenant_id = ? and category = 'RADAR'", Integer.class, a.tenantId()));
        assertThat(after).isEqualTo(1);
        assertThat(findings(a, "A1")).hasSize(1);
    }
}
