package de.buerokratiecopilot.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.identity.domain.Role;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    /** System roles are visible to every tenant, custom roles only to their own tenant. */
    @Query("select r from Role r where r.code = :code and (r.tenantId is null or r.tenantId = :tenantId)")
    List<Role> findUsableByTenant(@Param("tenantId") UUID tenantId, @Param("code") String code);

    @Query("select r from Role r where r.tenantId is null or r.tenantId = :tenantId order by r.rank")
    List<Role> findAllUsableByTenant(@Param("tenantId") UUID tenantId);

    Optional<Role> findByCodeAndTenantIdIsNull(String code);
}
