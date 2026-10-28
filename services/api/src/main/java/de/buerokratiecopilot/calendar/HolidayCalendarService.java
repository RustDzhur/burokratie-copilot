package de.buerokratiecopilot.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.calendar.domain.Holiday;
import de.buerokratiecopilot.calendar.domain.HolidayCalendar;
import de.buerokratiecopilot.deadlines.DeadlineCalculator;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Calendars of non-working days (spec §13.2).
 *
 * <p>The platform holds <strong>no holiday data of its own</strong>. Public holidays are set by law,
 * differ by country and region, and change; a bundled list would be wrong in some places from the day
 * it was written and silently wrong everywhere nobody checked. The service therefore offers a container
 * and an import, and it offers one piece of genuine arithmetic: the date of Easter, from which the
 * movable feasts follow.</p>
 *
 * <p>The movable candidates are returned as <em>candidates</em>. Whether a region observes
 * Pfingstmontag is a legal question, and the platform does not answer it — it proposes dates and a
 * person confirms them. That is also why an import never silently fills a calendar with a year's
 * public holidays: it writes exactly what the operator supplied.</p>
 */
@Service
public class HolidayCalendarService {

    private static final Logger log = LoggerFactory.getLogger(HolidayCalendarService.class);

    private final HolidayCalendarRepository calendars;
    private final HolidayRepository holidays;

    public HolidayCalendarService(HolidayCalendarRepository calendars, HolidayRepository holidays) {
        this.calendars = calendars;
        this.holidays = holidays;
    }

    /** A day that is not worked, as it is imported or listed. */
    public record HolidayEntry(LocalDate date, String name, boolean halfDay, boolean moveable) {
    }

    /** A calendar with its days, as a client sees it. */
    public record CalendarView(HolidayCalendar calendar, List<Holiday> holidays, int year,
                               List<Map<String, Object>> movableCandidates) {
    }

    @Transactional
    public HolidayCalendar create(UUID tenantId, String code, String name, String countryCode,
                                  String region, String timeZone, boolean defaultCalendar, UUID userId) {
        if (code == null || code.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A calendar needs a code");
        }
        if (countryCode == null || !countryCode.matches("[A-Za-z]{2}")) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A calendar needs a two letter country code, for example DE");
        }
        calendars.findByTenantIdAndCode(tenantId, code).ifPresent(existing -> {
            throw ApiException.duplicate("A calendar with this code already exists");
        });

        HolidayCalendar calendar = new HolidayCalendar(UUID.randomUUID(), tenantId, code.strip(),
                name == null || name.isBlank() ? code.strip() : name.strip(),
                countryCode.toUpperCase(java.util.Locale.ROOT));
        calendar.setRegion(region);
        if (timeZone != null && !timeZone.isBlank()) {
            calendar.setTimeZone(timeZone);
        }
        if (defaultCalendar) {
            // Only one calendar can be a tenant's default, otherwise "the" calendar is ambiguous. The
            // system default is deliberately left alone: overriding it is the tenant's choice, and
            // switching it off would change reference data for every other tenant.
            calendars.findByTenantIdAndDefaultCalendarTrue(tenantId).ifPresent(existing -> {
                existing.setDefaultCalendar(false);
                calendars.save(existing);
            });
        }
        calendar.setDefaultCalendar(defaultCalendar);
        return calendars.save(calendar);
    }

    @Transactional(readOnly = true)
    public List<HolidayCalendar> visible(UUID tenantId) {
        return calendars.findVisible(tenantId);
    }

    /**
     * Imports days into a calendar.
     *
     * <p>The caller supplies the days and their source; the platform stores what it is given. An entry
     * that duplicates a date updates it, because an operator correcting a name should not have to
     * delete the day first.</p>
     */
    @Transactional
    public List<Holiday> importHolidays(UUID calendarId, List<HolidayEntry> entries) {
        HolidayCalendar calendar = calendars.findById(calendarId)
                .orElseThrow(() -> ApiException.notFound("calendar"));
        if (entries == null || entries.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "There is nothing to import; the platform has no holiday data of its own to fall "
                            + "back on");
        }

        List<Holiday> saved = new ArrayList<>();
        for (HolidayEntry entry : entries) {
            if (entry.date() == null || entry.name() == null || entry.name().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Every imported day needs a date and a name");
            }
            boolean moveable = entry.moveable() || EasterCalculator.offsetFromEaster(entry.date()) != null;
            Holiday holiday = holidays.findByCalendarIdAndHolidayDate(calendar.getId(), entry.date())
                    .orElseGet(() -> new Holiday(UUID.randomUUID(), calendar.getId(), entry.date(),
                            entry.name().strip(), entry.halfDay(), moveable));
            holiday.setName(entry.name().strip());
            holiday.setHalfDay(entry.halfDay());
            holiday.setMoveable(moveable);
            saved.add(holidays.save(holiday));
        }
        log.info("Imported {} day(s) into calendar {}", saved.size(), calendar.getCode());
        return saved;
    }

    @Transactional
    public void removeHoliday(UUID calendarId, LocalDate date) {
        holidays.findByCalendarIdAndHolidayDate(calendarId, date).ifPresent(holidays::delete);
    }

    /** The days of a calendar, optionally narrowed to one year. */
    @Transactional(readOnly = true)
    public List<Holiday> holidaysOf(UUID calendarId, Integer year) {
        if (year == null) {
            return holidays.findByCalendarIdOrderByHolidayDateAsc(calendarId);
        }
        return holidays.findByCalendarIdAndHolidayDateBetweenOrderByHolidayDateAsc(calendarId,
                LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /** The non-working days of a calendar as a set, which is what the counters need. */
    @Transactional(readOnly = true)
    public Set<LocalDate> nonWorkingDays(UUID calendarId, int year) {
        return holidaysOf(calendarId, year).stream()
                .filter(holiday -> !holiday.isHalfDay())
                .map(Holiday::getHolidayDate)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * The default calendar of a tenant, as a set of days for a year.
     *
     * <p>Without a calendar the counter skips weekends only. That is stated in the result rather than
     * hidden, because counting working days without public holidays produces dates that are too early —
     * and a deadline that is too early is merely annoying, while one that is too late is missed.</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> workingDayContext(UUID tenantId, int year) {
        java.util.Optional<HolidayCalendar> resolved = defaultCalendar(tenantId);
        Map<String, Object> context = new LinkedHashMap<>();
        if (resolved.isEmpty()) {
            context.put("calendar", null);
            context.put("holidays", Set.of());
            context.put("warning", "No default calendar is configured, so working days skip weekends "
                    + "only and public holidays are not accounted for");
            return context;
        }
        HolidayCalendar calendar = resolved.get();
        context.put("calendar", calendar.displayName());
        context.put("calendarId", calendar.getId());
        context.put("holidays", nonWorkingDays(calendar.getId(), year));
        return context;
    }

    /**
     * The dates that move with Easter in a year, as candidates.
     *
     * <p>Returned with their offset so a reviewer can see why each date is in the list, and marked as
     * candidates: the platform computes the arithmetic and a person decides which of them their region
     * observes.</p>
     */
    public List<Map<String, Object>> movableCandidates(int year) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (EasterCalculator.MovableFeast feast : EasterCalculator.movableCandidates(year)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", feast.name());
            row.put("date", feast.date().toString());
            row.put("offsetFromEaster", feast.offsetDays());
            row.put("easterSunday", EasterCalculator.easterSunday(year).toString());
            row.put("candidate", true);
            row.put("note", "Rechenergebnis, kein gesetzlicher Feiertag: ob dieser Tag gilt, "
                    + "entscheidet die zuständige Regelung");
            candidates.add(row);
        }
        return candidates;
    }

    /** Working days between two dates in a calendar, for the "days remaining" a user sees. */
    @Transactional(readOnly = true)
    public long workingDaysBetween(UUID tenantId, LocalDate from, LocalDate to, boolean skipHolidays) {
        Set<LocalDate> nonWorking = skipHolidays ? defaultHolidays(tenantId, from.getYear(), to.getYear())
                : Set.of();
        return DeadlineCalculator.workingDaysBetween(from, to, nonWorking);
    }

    /** Adds working days within a calendar, used when a rule counts in working days. */
    @Transactional(readOnly = true)
    public LocalDate addWorkingDays(UUID tenantId, LocalDate start, int workingDays,
                                    boolean skipHolidays) {
        Set<LocalDate> nonWorking = skipHolidays
                ? defaultHolidays(tenantId, start.getYear(), start.plusYears(1).getYear())
                : Set.of();
        return DeadlineCalculator.addWorkingDays(start, workingDays, nonWorking);
    }

    /**
     * The calendar that applies to a tenant.
     *
     * <p>The tenant's own default wins; without one the system calendar applies. A tenant therefore
     * starts with a usable calendar rather than with none, which is the difference between counting
     * public holidays and silently ignoring them.</p>
     */
    @Transactional(readOnly = true)
    public java.util.Optional<HolidayCalendar> defaultCalendar(UUID tenantId) {
        java.util.Optional<HolidayCalendar> own =
                calendars.findByTenantIdAndDefaultCalendarTrue(tenantId);
        return own.isPresent() ? own : calendars.findByTenantIdIsNullAndDefaultCalendarTrue();
    }

    /** The holidays of the default calendar across the years a period can span. */
    private Set<LocalDate> defaultHolidays(UUID tenantId, int fromYear, int toYear) {
        java.util.Optional<HolidayCalendar> resolved = defaultCalendar(tenantId);
        if (resolved.isEmpty()) {
            return Set.of();
        }
        Set<LocalDate> dates = new java.util.HashSet<>();
        for (int year = Math.min(fromYear, toYear); year <= Math.max(fromYear, toYear); year++) {
            dates.addAll(nonWorkingDays(resolved.get().getId(), year));
        }
        return dates;
    }

    /** A calendar with its days and the movable candidates of a year. */
    @Transactional(readOnly = true)
    public CalendarView view(UUID calendarId, Integer year) {
        HolidayCalendar calendar = calendars.findById(calendarId)
                .orElseThrow(() -> ApiException.notFound("calendar"));
        int effectiveYear = year == null ? LocalDate.now().getYear() : year;
        // The candidates come from the calendar's own year, so the view stays consistent when a
        // caller asks about a different one.
        return new CalendarView(calendar, holidaysOf(calendarId, year), effectiveYear,
                movableCandidates(effectiveYear));
    }
}
