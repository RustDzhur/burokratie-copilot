package de.buerokratiecopilot.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * SM-01 and SM-03: the future shows only what is recorded, each event with its source and what is not known; a scenario
 * computes from recorded values and the person's stated assumption and changes nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FutureEventsIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private record Account(UUID tenantId, UUID userId, Cookie cookie) {
    }

    private Account newTenant() throws Exception {
        String email = "future-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult r = mockMvc.perform(post("/api/v1/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\",\"displayName\":\"F\",\"accountType\":\"COMPANY\"}".formatted(email)))
                .andExpect(status().isCreated()).andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(r.getResponse().getContentAsString(), "$.userId"));
        jdbc.update("update users set status = 'ACTIVE', email_verified_at = now() where id = ?", userId);
        Cookie c = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"EinSicheresPasswort-2026!\"}".formatted(email))).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("bc_session");
        return new Account(tenantId, userId, c);
    }

    private UUID task(Account a, String title, String status) {
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into tasks (id, tenant_id, title, status, completed_at)
                values (gen_random_uuid(), ?, ?, ?, case when ? = 'DONE' then now() else null end) returning id
                """, UUID.class, a.tenantId(), title, status, status));
    }

    private void deadline(Account a, UUID task, int inDays, boolean confirmed) {
        RequestContext.runAs(a.tenantId(), () -> jdbc.update("""
                insert into task_deadlines (task_id, tenant_id, deadline_type, due_date, source, confirmed_at, is_active)
                values (?, ?, 'ASSUMED_LEGAL', current_date + ?, 'DOCUMENT', case when ? then now() else null end, true)
                """, task, a.tenantId(), inDays, confirmed));
    }

    /** A contract; {@code extra} is "column|value" for one more column. */
    private UUID contract(Account a, String title, String extra) {
        String column = extra.isEmpty() ? "" : ", " + extra.split("\\|")[0];
        String value = extra.isEmpty() ? "" : ", " + extra.split("\\|")[1];
        return RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject(("""
                insert into contracts (id, tenant_id, title, contract_type, status, cost_amount, cost_currency, cost_interval%s)
                values (gen_random_uuid(), ?, ?, 'INTERNET', 'ACTIVE', 30.00, 'EUR', 'MONTHLY'%s) returning id
                """).formatted(column, value), UUID.class, a.tenantId(), title));
    }

    private Map<String, Object> read(Account a, String url) throws Exception {
        return JsonPath.read(mockMvc.perform(get(url).cookie(a.cookie())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
    }

    @Test
    @DisplayName("deadlines, obligations, notice and end of a contract, unpaid invoices, tasks: each with its source; what is done or beyond the horizon is absent; a waiting reply has no date")
    void eventsWithSources() throws Exception {
        Account a = newTenant();
        UUID open = task(a, "Widerspruch prüfen", "NEW");
        deadline(a, open, 10, false);
        UUID done = task(a, "Erledigt", "DONE");
        deadline(a, done, 12, true);
        UUID far = task(a, "Weit weg", "NEW");
        deadline(a, far, 100, true);
        UUID plain = task(a, "Ohne Frist", "NEW");
        RequestContext.runAs(a.tenantId(), () -> jdbc.update("update tasks set due_at = now() + interval '8 days' where id = ?", plain));

        UUID renewing = contract(a, "Internet", "end_date|current_date + 45");
        RequestContext.runAs(a.tenantId(), () -> jdbc.update("update contracts set auto_renewal = true where id = ?", renewing));
        UUID noticed = contract(a, "Strom", "notice_deadline_date|current_date + 25");
        RequestContext.runAs(a.tenantId(), () -> {
            jdbc.update("""
                    insert into contract_obligations (contract_id, tenant_id, obligation_type, title, due_date, amount, status)
                    values (?, ?, 'PAYMENT', 'Jahresbeitrag', current_date + 20, 120.00, 'OPEN')
                    """, noticed, a.tenantId());
            jdbc.update("""
                    insert into invoices (tenant_id, direction, invoice_number, due_date, gross_amount, paid_amount, payment_status, currency)
                    values (?, 'INCOMING', 'R-77', current_date + 5, 280.00, 80.00, 'PARTIALLY_PAID', 'EUR')
                    """, a.tenantId());
        });
        UUID caseId = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into cases (id, tenant_id, reference, title, status, priority, opened_at, owner_user_id, responsible_user_id)
                values (gen_random_uuid(), ?, 'F-1', 'Widerspruch', 'WAITING_FOR_EXTERNAL_PARTY', 'NORMAL', now(), ?, ?) returning id
                """, UUID.class, a.tenantId(), a.userId(), a.userId()));
        RequestContext.runAs(a.tenantId(), () -> jdbc.update("""
                insert into case_completion_criteria (tenant_id, case_id, kind, description) values (?, ?, 'REPLY_RECEIVED', 'Antwort des Anbieters')
                """, a.tenantId(), caseId));

        Map<String, Object> ninety = read(a, "/api/v1/memory/future?horizon=90");
        List<Map<String, Object>> events = list(ninety, "events");
        assertThat(events).extracting(e -> (String) e.get("kind")).containsExactlyInAnyOrder("TASK", "INVOICE_DUE", "DEADLINE", "OBLIGATION",
                "CONTRACT_NOTICE", "CONTRACT_END");
        assertThat(events).allSatisfy(e -> {
            assertThat(e.get("date")).isNotNull();
            assertThat(((Map<?, ?>) e.get("source")).get("id")).as("every event names its source").isNotNull();
        });
        assertThat(events).extracting(e -> (String) e.get("date")).isSorted();

        Map<String, Object> deadline = events.stream().filter(e -> e.get("kind").equals("DEADLINE")).findFirst().orElseThrow();
        assertThat(deadline.get("certainty")).isEqualTo("UNCONFIRMED");
        assertThat((List<?>) deadline.get("unknown")).isNotEmpty();
        Map<String, Object> end = events.stream().filter(e -> e.get("kind").equals("CONTRACT_END")).findFirst().orElseThrow();
        assertThat(String.valueOf(((List<?>) end.get("unknown")).getFirst())).contains("Kündigungsfrist ist nicht erfasst");
        Map<String, Object> invoice = events.stream().filter(e -> e.get("kind").equals("INVOICE_DUE")).findFirst().orElseThrow();
        assertThat(new java.math.BigDecimal(String.valueOf(invoice.get("amount")))).isEqualByComparingTo("200");

        assertThat(list(ninety, "undated")).singleElement().satisfies(e -> {
            assertThat(e.get("kind")).isEqualTo("EXPECTED_REPLY");
            assertThat(e.get("date")).as("a reply without a date is not given one").isNull();
            assertThat(String.valueOf(e.get("unknown"))).contains("nicht bekannt");
        });

        assertThat(list(read(a, "/api/v1/memory/future?horizon=30"), "events")).extracting(e -> (String) e.get("kind"))
                .as("the contract end in 45 days is beyond 30 days").doesNotContain("CONTRACT_END");

        mockMvc.perform(get("/api/v1/memory/future?horizon=7").cookie(a.cookie())).andExpect(status().isBadRequest());
        Account stranger = newTenant();
        assertThat(list(read(stranger, "/api/v1/memory/future?horizon=90"), "events")).isEmpty();
        assertThat(list(read(stranger, "/api/v1/memory/future?horizon=90"), "undated")).isEmpty();
    }

    private Map<String, Object> scenario(Account a, String body) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/v1/memory/future/scenario").with(csrf()).cookie(a.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
    }

    private static java.math.BigDecimal number(Object value) {
        return new java.math.BigDecimal(String.valueOf(value));
    }

    @Test
    @DisplayName("a scenario computes from recorded values and the stated assumption, and writes nothing")
    void scenarioComputes() throws Exception {
        Account a = newTenant();
        UUID c = contract(a, "Internet", "end_date|date '2026-12-31'");
        RequestContext.runAs(a.tenantId(), () -> jdbc.update(
                "update contracts set auto_renewal = true, renewal_period_months = 12, notice_deadline_date = date '2026-10-31' where id = ?", c));
        long before = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("select count(*) from audit_events where tenant_id = ?", Long.class, a.tenantId()));
        String updated = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("select updated_at::text from contracts where id = ?", String.class, c));

        Map<String, Object> missed = scenario(a, "{\"type\":\"NOTICE_MISSED\",\"contractId\":\"%s\"}".formatted(c));
        assertThat(missed.get("status")).isEqualTo("COMPUTED");
        assertThat(missed.get("readOnly")).isEqualTo(true);
        assertThat(String.valueOf(((Map<?, ?>) missed.get("baseline")).get("endDate"))).isEqualTo("2026-12-31");
        assertThat(String.valueOf(((Map<?, ?>) missed.get("alternative")).get("endDate"))).isEqualTo("2027-12-31");
        assertThat(number(((Map<?, ?>) missed.get("alternative")).get("extraCost"))).isEqualByComparingTo("360");

        Map<String, Object> price = scenario(a, "{\"type\":\"PRICE_CHANGE\",\"contractId\":\"%s\",\"percent\":10}".formatted(c));
        assertThat(price.get("status")).isEqualTo("COMPUTED");
        assertThat(number(((Map<?, ?>) price.get("baseline")).get("yearlyCost"))).isEqualByComparingTo("360");
        assertThat(number(price.get("difference"))).isEqualByComparingTo("36");
        assertThat((List<?>) price.get("assumptions")).isNotEmpty();

        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("select count(*) from audit_events where tenant_id = ?", Long.class, a.tenantId())))
                .isEqualTo(before);
        assertThat(RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("select updated_at::text from contracts where id = ?", String.class, c)))
                .as("nothing was changed").isEqualTo(updated);
    }

    @Test
    @DisplayName("a scenario never computes over a missing condition: it says what is missing; a late payment gets no invented fee")
    void scenarioWithoutData() throws Exception {
        Account a = newTenant();
        UUID c = contract(a, "Internet", "");
        Map<String, Object> missed = scenario(a, "{\"type\":\"NOTICE_MISSED\",\"contractId\":\"%s\"}".formatted(c));
        assertThat(missed.get("status")).isEqualTo("INSUFFICIENT_DATA");
        assertThat(missed).doesNotContainKeys("alternative", "baseline");
        assertThat(String.valueOf(missed.get("missing"))).contains("Kündigungsfrist").contains("Vertragsende");

        Map<String, Object> noPercent = scenario(a, "{\"type\":\"PRICE_CHANGE\",\"contractId\":\"%s\"}".formatted(c));
        assertThat(noPercent.get("status")).isEqualTo("INSUFFICIENT_DATA");
        assertThat(String.valueOf(noPercent.get("missing"))).contains("Prozentsatz");

        UUID invoice = RequestContext.runAs(a.tenantId(), () -> jdbc.queryForObject("""
                insert into invoices (tenant_id, direction, invoice_number, due_date, gross_amount, payment_status, currency)
                values (?, 'INCOMING', 'R-1', date '2026-11-01', 100.00, 'UNPAID', 'EUR') returning id
                """, UUID.class, a.tenantId()));
        Map<String, Object> late = scenario(a, "{\"type\":\"PAYMENT_LATE\",\"invoiceId\":\"%s\",\"days\":14}".formatted(invoice));
        assertThat(late.get("status")).isEqualTo("COMPUTED");
        assertThat(String.valueOf(((Map<?, ?>) late.get("alternative")).get("paidOn"))).isEqualTo("2026-11-15");
        assertThat(String.valueOf(late.get("unknown"))).contains("nicht berechnet");

        mockMvc.perform(post("/api/v1/memory/future/scenario").with(csrf()).cookie(a.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"DELETE_ALL\"}")).andExpect(status().isBadRequest());
        Account stranger = newTenant();
        mockMvc.perform(post("/api/v1/memory/future/scenario").with(csrf()).cookie(stranger.cookie()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"PRICE_CHANGE\",\"contractId\":\"%s\",\"percent\":5}".formatted(c))).andExpect(status().isNotFound());
    }
}
