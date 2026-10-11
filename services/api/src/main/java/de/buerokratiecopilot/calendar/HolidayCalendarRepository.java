package de.buerokratiecopilot.calendar;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.calendar.domain.HolidayCalendar;

public interface HolidayCalendarRepository extends JpaRepository<HolidayCalendar, UUID> {

    @Query("""
            select c from HolidayCalendar c
            where c.tenantId is null or c.tenantId = :tenantId
            order by c.defaultCalendar desc, c.code
            """)
    List<HolidayCalendar> findVisible(@Param("tenantId") UUID tenantId);

    Optional<HolidayCalendar> findByTenantIdAndCode(UUID tenantId, String code);

    @Query("""
            select c from HolidayCalendar c
            where c.defaultCalendar = true and (c.tenantId is null or c.tenantId = :tenantId)
            """)
    List<HolidayCalendar> findDefault(@Param("tenantId") UUID tenantId);
}
