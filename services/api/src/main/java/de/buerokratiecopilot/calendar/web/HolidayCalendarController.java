package de.buerokratiecopilot.calendar.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.calendar.HolidayCalendarService;
import de.buerokratiecopilot.calendar.domain.Holiday;
import de.buerokratiecopilot.calendar.domain.HolidayCalendar;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Calendar API (spec §13.2, §20).
 *
 * <p>The import endpoint is how the platform gets holiday data: from the operator, not from a list
 * compiled into the product. The only dates the platform computes itself are the ones that follow from
 * Easter, and those are returned as candidates for a person to confirm.</p>
 */
@RestController
@RequestMapping("/api/v1/calendars")
@Tag(name = "Calendars", description = "Calendars of non-working days and the dates that move with Easter")
public class HolidayCalendarController {

    private final HolidayCalendarService calendars;

    public HolidayCalendarController(HolidayCalendarService calendars) {
        this.calendars = calendars;
    }

    public record CreateCalendarRequest(String code, String name, String countryCode, String region,
                                        String timeZone, boolean defaultCalendar) {
    }

    public record HolidayBody(String date, String name, boolean halfDay, boolean moveable) {
    }

    public record ImportRequest(List<HolidayBody> holidays) {
    }

    public record CalendarResponse(String id, String code, String name, String displayName,
                                   String countryCode, String region, String timeZone,
                                   boolean defaultCalendar, long holidayCount) {

        static CalendarResponse from(HolidayCalendar calendar, long holidayCount) {
            return new CalendarResponse(calendar.getId().toString(), calendar.getCode(),
                    calendar.nameIn("de"), calendar.displayName(), calendar.getCountryCode(),
                    calendar.getRegion(), calendar.getTimeZone(), calendar.isDefaultCalendar(),
                    holidayCount);
        }
    }

    public record HolidayResponse(String id, String date, String name, boolean halfDay,
                                  boolean moveable) {

        static HolidayResponse from(Holiday holiday) {
            return new HolidayResponse(holiday.getId().toString(), holiday.getHolidayDate().toString(),
                    holiday.nameIn("de"), holiday.isHalfDay(), holiday.isMoveable());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settings.view')")
    @Operation(summary = "Calendars the tenant can use")
    public ResponseEntity<List<CalendarResponse>> list() {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        return ResponseEntity.ok(calendars.visible(tenantId).stream()
                .map(calendar -> CalendarResponse.from(calendar,
                        calendars.holidaysOf(calendar.getId(), null).size()))
                .toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    @Operation(summary = "Create a calendar; it starts empty, because the platform has no holiday data")
    public ResponseEntity<CalendarResponse> create(@RequestBody CreateCalendarRequest request) {
        HolidayCalendar calendar = calendars.create(RequestContext.tenantId().orElseThrow(),
                request.code(), request.name(), request.countryCode(), request.region(),
                request.timeZone(), request.defaultCalendar(), CurrentUser.required());
        return ResponseEntity.status(HttpStatus.CREATED).body(CalendarResponse.from(calendar, 0));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('settings.view')")
    @Operation(summary = "A calendar with its days and the movable candidates of a year")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable UUID id,
                                                      @RequestParam(required = false) Integer year) {
        HolidayCalendarService.CalendarView view = calendars.view(id, year);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("calendar", CalendarResponse.from(view.calendar(), view.holidays().size()));
        body.put("year", view.year());
        body.put("holidays", view.holidays().stream().map(HolidayResponse::from).toList());
        body.put("movableCandidates", view.movableCandidates());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/holidays")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    @Operation(summary = "Import the days of this calendar from the operator's own source")
    public ResponseEntity<List<HolidayResponse>> importHolidays(@PathVariable UUID id,
                                                                @RequestBody ImportRequest request) {
        List<HolidayCalendarService.HolidayEntry> entries = request.holidays() == null ? List.of()
                : request.holidays().stream()
                        .map(body -> new HolidayCalendarService.HolidayEntry(parseDate(body.date()),
                                body.name(), body.halfDay(), body.moveable()))
                        .toList();

        return ResponseEntity.status(HttpStatus.CREATED).body(calendars.importHolidays(id, entries)
                .stream().map(HolidayResponse::from).toList());
    }

    @DeleteMapping("/{id}/holidays/{date}")
    @PreAuthorize("hasAuthority('settings.manage_rules')")
    @Operation(summary = "Remove a day from a calendar")
    public ResponseEntity<Void> removeHoliday(@PathVariable UUID id,
                                              @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                              LocalDate date) {
        calendars.removeHoliday(id, date);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/movable-candidates")
    @PreAuthorize("hasAuthority('settings.view')")
    @Operation(summary = "Dates that follow from Easter, as candidates rather than holidays")
    public ResponseEntity<List<Map<String, Object>>> movableCandidates(
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(calendars.movableCandidates(
                year == null ? LocalDate.now().getYear() : year));
    }

    @GetMapping("/working-days")
    @PreAuthorize("hasAuthority('cases.view')")
    @Operation(summary = "Working days between two dates in the tenant's default calendar")
    public ResponseEntity<Map<String, Object>> workingDays(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "true") boolean skipHolidays) {

        UUID tenantId = RequestContext.tenantId().orElseThrow();
        Map<String, Object> context = calendars.workingDayContext(tenantId, from.getYear());
        Map<String, Object> body = new java.util.LinkedHashMap<>(context);
        body.put("from", from.toString());
        body.put("to", to.toString());
        body.put("workingDays", calendars.workingDaysBetween(tenantId, from, to, skipHolidays));
        return ResponseEntity.ok(body);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        }
        catch (RuntimeException ex) {
            throw new de.buerokratiecopilot.shared.error.ApiException(
                    de.buerokratiecopilot.shared.error.ErrorCode.INVALID_REQUEST,
                    "A holiday date must be written as YYYY-MM-DD, but was: " + value);
        }
    }
}
