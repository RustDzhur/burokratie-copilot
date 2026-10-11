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

    /**
     * The tenant's own default calendar.
     *
     * <p>Looked up separately from the system default, because a tenant that sets its own default must
     * override the shared one rather than switch it off for everybody. Writing to a row whose
     * {@code tenant_id} is NULL is a change to reference data, not to the tenant's own settings.</p>
     */
    Optional<HolidayCalendar> findByTenantIdAndDefaultCalendarTrue(UUID tenantId);

    /** The system default, which applies wherever a tenant has not chosen one of its own. */
    Optional<HolidayCalendar> findByTenantIdIsNullAndDefaultCalendarTrue();
}
