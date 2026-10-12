package de.buerokratiecopilot.calendar.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** The calendar screen's data: deadlines of the caller's work plus hand-entered appointments and reminders. */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/calendar/events")
@Tag(name = "Calendar events", description = "Deadlines, appointments and reminders in a date range")
public class CalendarEventsController {

    private static final String SEE = "hasAnyAuthority('tasks.view','cases.view')";

    private final CalendarEventService events;

    public CalendarEventsController(CalendarEventService events) {
        this.events = events;
    }

    public record CreateEventRequest(String title, LocalDate date, LocalDate endDate,
                                     CalendarEventService.Kind type, UUID caseId) {
    }

    @GetMapping
    @PreAuthorize(SEE)
    @Operation(summary = "Events from one date to another, both inclusive")
    public List<Map<String, Object>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        boolean seeAll = hasAuthority("tasks.assign") || hasAuthority("cases.assign");
        return events.events(RequestContext.tenantId().orElseThrow(), CurrentUser.required(), seeAll, from, to);
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('tasks.create','cases.create','cases.edit')")
    @Operation(summary = "Enters an appointment or a reminder")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(events.create(
                RequestContext.tenantId().orElseThrow(),
                new CalendarEventService.Draft(request.title(), request.date(), request.endDate(), request.type(),
                        request.caseId()),
                CurrentUser.required()));
    }

    private static boolean hasAuthority(String authority) {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> granted.getAuthority().equals(authority));
    }
}
