package de.buerokratiecopilot.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import de.buerokratiecopilot.identity.onboarding.OnboardingService;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * The phone's calendar: a private address that serves iCalendar, with alarms and the explanation of each entry, that
 * can be revoked, and that does not publish the details of a confidential document.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalendarFeedIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OnboardingService onboarding;

    @Autowired
    private CalendarEventService calendar;

    @Autowired
    private CalendarFeedService feeds;

    @Autowired
    private JdbcTemplate jdbc;

    private record Account(UUID tenantId, UUID userId) {
    }

    private Account account() {
        var created = onboarding.onboard(new OnboardingService.Request(
                "cal-" + UUID.randomUUID().toString().substring(0, 12), "Kalender", "PRIVATE_PERSON",
                "cal-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de", "Erika Mustermann",
                "de", "Europe/Berlin", "DE", "Kalender"));
        return new Account(created.tenantId(), created.adminUserId());
    }

    private String fetch(String token) throws Exception {
        return mvc.perform(get("/api/v1/calendar/feed/" + token + ".ics")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the address serves iCalendar without a login, with the entry, its explanation and alarms")
    void servesTheCalendar() throws Exception {
        Account account = account();
        LocalDate date = LocalDate.now().plusDays(10);
        RequestContext.runAs(account.tenantId(), () -> calendar.create(account.tenantId(),
                new CalendarEventService.Draft("Vertragsende: Internet, DSL", date, null,
                        CalendarEventService.Kind.REMINDER, null),
                account.userId(), "Automatisch aus dem Dokument gelesen; Kündigungsfrist 3 Monate.", null));
        var created = RequestContext.runAs(account.tenantId(),
                () -> feeds.create(account.tenantId(), account.userId(), "iPhone", "de"));

        String ics = fetch(created.token());

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n").endsWith("END:VCALENDAR\r\n");
        assertThat(ics).contains("VERSION:2.0", "REFRESH-INTERVAL;VALUE=DURATION:PT1H");
        assertThat(ics).contains("DTSTART;VALUE=DATE:" + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        assertThat(ics).contains("SUMMARY:Vertragsende: Internet\\, DSL"); // the comma is escaped
        assertThat(ics.replace("\r\n ", "")).contains("Kündigungsfrist 3 Monate");
        assertThat(ics).as("the phone rings the day before and on the day")
                .contains("BEGIN:VALARM", "TRIGGER:-PT15H", "TRIGGER:PT8H");
        // RFC 5545: no line longer than 75 octets
        for (String line : ics.split("\r\n")) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        }
    }

    @Test
    @DisplayName("a wrong or revoked secret is a plain 404, and the secret itself is never stored")
    void wrongAndRevoked() throws Exception {
        Account account = account();
        var created = RequestContext.runAs(account.tenantId(),
                () -> feeds.create(account.tenantId(), account.userId(), "Android", "ru"));
        fetch(created.token());

        mvc.perform(get("/api/v1/calendar/feed/" + "x".repeat(43) + ".ics")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/calendar/feed/kurz.ics")).andExpect(status().isNotFound());

        String stored = jdbc.queryForObject("select token_hash from calendar_feed_tokens where id = ?", String.class,
                created.id());
        assertThat(stored).isNotEqualTo(created.token()).hasSize(64);

        RequestContext.runAs(account.tenantId(),
                () -> feeds.revoke(account.tenantId(), account.userId(), created.id()));
        mvc.perform(get("/api/v1/calendar/feed/" + created.token() + ".ics")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an entry of a confidential document is published without its details")
    void confidentialEntriesAreMasked() throws Exception {
        Account account = account();
        LocalDate date = LocalDate.now().plusDays(5);
        RequestContext.runAs(account.tenantId(), () -> {
            UUID id = jdbc.queryForObject("""
                    insert into tasks (id, tenant_id, title, status, priority, priority_source, source, tags,
                                       created_by_user_id, assignment_pending)
                    values (gen_random_uuid(), ?, 'Befund Dr. Meyer prüfen', 'NEW', 'NORMAL', 'MANUAL', 'MANUAL',
                            '["medizinisches_dokument"]'::jsonb, ?, false) returning id
                    """, UUID.class, account.tenantId(), account.userId());
            jdbc.update("""
                    insert into task_deadlines (id, tenant_id, task_id, deadline_type, due_date, source, is_active)
                    values (gen_random_uuid(), ?, ?, 'DOCUMENT_DATE', ?, 'DOCUMENT', true)
                    """, account.tenantId(), id, date);
            return id;
        });
        var created = RequestContext.runAs(account.tenantId(),
                () -> feeds.create(account.tenantId(), account.userId(), "iPhone", "de"));

        String ics = fetch(created.token());

        assertThat(ics).contains("Vertraulicher Eintrag").doesNotContain("Meyer").doesNotContain("Befund");
    }
}
