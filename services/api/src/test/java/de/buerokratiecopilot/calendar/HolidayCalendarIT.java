package de.buerokratiecopilot.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Verifies the holiday calendar (spec §13.2).
 *
 * <p>The acceptance idea here is the same as for deadlines: the platform must not assert which days are
 * public holidays. The test therefore checks that a new calendar is empty, that it stays empty until
 * somebody imports days, that the count changes when they do, and that the only dates the platform
 * computes itself are offered as candidates and say so.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HolidayCalendarIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository users;

    private record Session(Cookie cookie, UUID tenantId, UUID userId) {
    }

    private Session register() throws Exception {
        String email = "calendar-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de";
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

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"EinSicheresPasswort-2026!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(login.getResponse().getCookie("bc_session"), tenantId, userId);
    }

    private String createCalendar(Session session, boolean asDefault) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/calendars").with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"de-by","name":"Bayern","countryCode":"DE","region":"BY",
                                 "defaultCalendar":%s}
                                """.formatted(asDefault)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.id");
    }

    @Test
    @DisplayName("a new calendar is empty: the platform has no holiday data of its own")
    void calendarStartsEmpty() throws Exception {
        Session session = register();
        String calendarId = createCalendar(session, true);

        mockMvc.perform(get("/api/v1/calendars/{id}", calendarId).cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holidays").isEmpty())
                .andExpect(jsonPath("$.calendar.holidayCount").value(0));
    }

    @Test
    @DisplayName("days imported by the operator are stored as given")
    void importsOperatorSuppliedDays() throws Exception {
        Session session = register();
        String calendarId = createCalendar(session, true);

        mockMvc.perform(post("/api/v1/calendars/{id}/holidays", calendarId).with(csrf())
                        .cookie(session.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holidays":[
                                  {"date":"2026-01-01","name":"Neujahr"},
                                  {"date":"2026-05-01","name":"Tag der Arbeit"},
                                  {"date":"2026-08-15","name":"Mariä Himmelfahrt","halfDay":false}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(get("/api/v1/calendars/{id}", calendarId).param("year", "2026")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holidays.length()").value(3))
                .andExpect(jsonPath("$.holidays[0].name").value("Neujahr"));
    }

    @Test
    @DisplayName("importing the same date again corrects it instead of duplicating it")
    void importIsIdempotentForADate() throws Exception {
        Session session = register();
        String calendarId = createCalendar(session, true);

        mockMvc.perform(post("/api/v1/calendars/{id}/holidays", calendarId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holidays\":[{\"date\":\"2026-01-01\",\"name\":\"Neujahr\"}]}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/calendars/{id}/holidays", calendarId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holidays\":[{\"date\":\"2026-01-01\",\"name\":\"Neujahr (korrigiert)\"}]}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/calendars/{id}", calendarId).param("year", "2026")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holidays.length()").value(1))
                .andExpect(jsonPath("$.holidays[0].name").value("Neujahr (korrigiert)"));
    }

    @Test
    @DisplayName("working days account for the imported holidays")
    void workingDaysUseImportedHolidays() throws Exception {
        Session session = register();
        String calendarId = createCalendar(session, true);
        mockMvc.perform(post("/api/v1/calendars/{id}/holidays", calendarId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holidays\":[{\"date\":\"2026-03-03\",\"name\":\"Ferientag\"}]}"))
                .andExpect(status().isCreated());

        // Monday 2026-03-02 to Monday 2026-03-09 is five working days; the imported Tuesday makes four.
        mockMvc.perform(get("/api/v1/calendars/working-days").param("from", "2026-03-02")
                        .param("to", "2026-03-09").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDays").value(4))
                .andExpect(jsonPath("$.calendar").value("Bayern (DE-BY)"));

        mockMvc.perform(get("/api/v1/calendars/working-days").param("from", "2026-03-02")
                        .param("to", "2026-03-09").param("skipHolidays", "false")
                        .cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDays").value(5));
    }

    @Test
    @DisplayName("without a default calendar the platform says holidays are not accounted for")
    void warnsWithoutADefaultCalendar() throws Exception {
        Session session = register();
        createCalendar(session, false);

        mockMvc.perform(get("/api/v1/calendars/working-days").param("from", "2026-03-02")
                        .param("to", "2026-03-09").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workingDays").value(5))
                .andExpect(jsonPath("$.warning")
                        .value(org.hamcrest.Matchers.containsString("public holidays")));
    }

    @Test
    @DisplayName("the movable dates are offered as candidates with their arithmetic shown")
    void movableCandidatesAreCandidates() throws Exception {
        Session session = register();

        MvcResult result = mockMvc.perform(get("/api/v1/calendars/movable-candidates")
                        .param("year", "2026").cookie(session.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].candidate").value(true))
                .andExpect(jsonPath("$[?(@.name=='Karfreitag')].date")
                        .value(org.hamcrest.Matchers.contains("2026-04-03")))
                .andExpect(jsonPath("$[?(@.name=='Pfingstmontag')].offsetFromEaster")
                        .value(org.hamcrest.Matchers.contains(50)))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("the platform must state that these are computed, not legislated")
                .contains("kein gesetzlicher Feiertag");
    }

    @Test
    @DisplayName("an import with nothing in it is refused, because there is no data to fall back on")
    void refusesEmptyImport() throws Exception {
        Session session = register();
        String calendarId = createCalendar(session, true);

        mockMvc.perform(post("/api/v1/calendars/{id}/holidays", calendarId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holidays\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a calendar needs a two letter country code, and a bad date is refused clearly")
    void validatesInput() throws Exception {
        Session session = register();

        mockMvc.perform(post("/api/v1/calendars").with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"x\",\"name\":\"X\",\"countryCode\":\"Deutschland\"}"))
                .andExpect(status().isBadRequest());

        String calendarId = createCalendar(session, true);
        mockMvc.perform(post("/api/v1/calendars/{id}/holidays", calendarId).with(csrf())
                        .cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"holidays\":[{\"date\":\"03.01.2026\",\"name\":\"Neujahr\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("YYYY-MM-DD")));
    }
}
