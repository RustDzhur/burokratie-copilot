package de.buerokratiecopilot.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * A calendar entry is not for everybody in the account (spec §5, §12).
 *
 * <p><strong>What was wrong.</strong> {@code CalendarEventService.events} read {@code calendar_events} by
 * {@code tenant_id} alone, with no filter by person, case or confidentiality. Every personal appointment and
 * every entry derived from a document was shown to every user of the account and went into their calendar
 * feed. The task deadlines and the case due dates beside it already had a visibility rule; the calendar
 * entries were the exception.</p>
 *
 * <p>The test gives the same account two people: one owns an entry, the other must not see it — unless they
 * hold the right to see everything, in which case they do. An entry with neither an owner nor a case stays
 * visible, because it predates the rule and hiding it would lose a date silently.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CalendarVisibilityIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CalendarEventService calendar;

    private record Person(UUID tenantId, UUID userId) {
    }

    /** A person with a confirmed account; the address is unique per call. */
    private Person register() throws Exception {
        String email = "cal-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
        MvcResult registration = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!",
                                 "displayName":"Kalender Test","accountType":"COMPANY"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID tenantId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.tenantId"));
        UUID userId = UUID.fromString(JsonPath.read(
                registration.getResponse().getContentAsString(), "$.userId"));
        RequestContext.runAs(tenantId, () -> {
            User user = users.findById(userId).orElseThrow();
            user.markEmailVerified(java.time.Instant.now());
            user.setStatus(User.Status.ACTIVE);
            return users.save(user);
        });
        return new Person(tenantId, userId);
    }

    /** An entry on the given day, owned by {@code createdBy}. */
    private UUID entry(Person tenantOf, UUID createdBy, String title, LocalDate day, UUID caseId) {
        return RequestContext.runAs(tenantOf.tenantId(), () -> jdbc.queryForObject("""
                insert into calendar_events (id, tenant_id, title, event_type, starts_on, all_day,
                                             case_id, created_by)
                values (gen_random_uuid(), ?, ?, 'APPOINTMENT', ?, true, ?, ?)
                returning id
                """, UUID.class, tenantOf.tenantId(), title, day, caseId, createdBy));
    }

    private List<Map<String, Object>> seenBy(Person person, boolean seeAll) {
        return RequestContext.runAs(person.tenantId(),
                () -> calendar.events(person.tenantId(), person.userId(), seeAll,
                        LocalDate.now().minusDays(1), LocalDate.now().plusDays(30)));
    }

    @Test
    @DisplayName("a personal entry is not shown to a colleague in the same account")
    void aPersonalEntryIsNotShownToAColleague() throws Exception {
        Person owner = register();
        Person colleague = register();
        // The colleague's account is different, but the point is the person: the entry is owned by somebody
        // else and must not appear in the second person's answer.
        entry(owner, owner.userId(), "Zahnarzt", LocalDate.now().plusDays(3), null);

        List<Map<String, Object>> seen = seenBy(owner, false);
        assertThat(seen).extracting(event -> event.get("title")).contains("Zahnarzt");

        // The same account, a different person: the entry is not theirs.
        List<Map<String, Object>> other = RequestContext.runAs(owner.tenantId(),
                () -> calendar.events(owner.tenantId(), colleague.userId(), false,
                        LocalDate.now().minusDays(1), LocalDate.now().plusDays(30)));
        assertThat(other).extracting(event -> event.get("title"))
                .as("a colleague must not see somebody else's appointment")
                .doesNotContain("Zahnarzt");
    }

    @Test
    @DisplayName("whoever may see everything sees it too")
    void aCallerWithTheRightSeesEverything() throws Exception {
        Person owner = register();
        Person colleague = register();
        entry(owner, owner.userId(), "Zahnarzt", LocalDate.now().plusDays(3), null);

        List<Map<String, Object>> all = RequestContext.runAs(owner.tenantId(),
                () -> calendar.events(owner.tenantId(), colleague.userId(), true,
                        LocalDate.now().minusDays(1), LocalDate.now().plusDays(30)));
        assertThat(all).extracting(event -> event.get("title"))
                .as("the assignment right means seeing the whole account as well")
                .contains("Zahnarzt");
    }

    @Test
    @DisplayName("an entry without an owner and without a case stays visible")
    void anUnownedEntryStaysVisible() throws Exception {
        Person person = register();
        // Such rows predate the rule; hiding them would silently lose a date.
        entry(person, null, "Alter Eintrag", LocalDate.now().plusDays(5), null);

        assertThat(seenBy(person, false)).extracting(event -> event.get("title"))
                .contains("Alter Eintrag");
    }
}
