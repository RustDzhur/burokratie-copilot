package de.buerokratiecopilot.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.calendar.domain.Holiday;

public interface HolidayRepository extends JpaRepository<Holiday, UUID> {

    List<Holiday> findByCalendarIdOrderByHolidayDateAsc(UUID calendarId);

    List<Holiday> findByCalendarIdAndHolidayDateBetweenOrderByHolidayDateAsc(UUID calendarId,
                                                                            LocalDate from,
                                                                            LocalDate to);

    Optional<Holiday> findByCalendarIdAndHolidayDate(UUID calendarId, LocalDate holidayDate);
}
