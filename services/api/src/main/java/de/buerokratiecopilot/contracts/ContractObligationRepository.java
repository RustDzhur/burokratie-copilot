package de.buerokratiecopilot.contracts;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.contracts.domain.ContractObligation;

public interface ContractObligationRepository extends JpaRepository<ContractObligation, UUID> {

    List<ContractObligation> findByContractIdOrderByDueDateAsc(UUID contractId);

    List<ContractObligation> findByTenantIdAndDueDateBetweenOrderByDueDateAsc(UUID tenantId,
                                                                             LocalDate from,
                                                                             LocalDate to);

    @Query("""
            select o from ContractObligation o
            where o.tenantId = :tenantId and o.dueDate is not null and o.reminderDaysBefore is not null
              and o.status in (de.buerokratiecopilot.contracts.domain.ContractObligation$Status.OPEN,
                               de.buerokratiecopilot.contracts.domain.ContractObligation$Status.DUE,
                               de.buerokratiecopilot.contracts.domain.ContractObligation$Status.MISSED)
            order by o.dueDate asc
            """)
    List<ContractObligation> findRemindable(@Param("tenantId") UUID tenantId);
}
