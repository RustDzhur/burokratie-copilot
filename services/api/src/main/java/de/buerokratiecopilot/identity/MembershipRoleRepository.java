package de.buerokratiecopilot.identity;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.identity.domain.MembershipRole;

public interface MembershipRoleRepository extends JpaRepository<MembershipRole, MembershipRole.Key> {

    List<MembershipRole> findByMembershipId(UUID membershipId);

    @Query("""
            select distinct p.code from MembershipRole mr
            join Role r on r.id = mr.roleId
            join RolePermission rp on rp.roleId = r.id
            join Permission p on p.id = rp.permissionId
            where mr.membershipId = :membershipId
            """)
    List<String> findPermissionCodes(@Param("membershipId") UUID membershipId);

    @Query("select r.code from Role r where r.id in (select mr.roleId from MembershipRole mr where mr.membershipId = :membershipId)")
    List<String> findRoleCodes(@Param("membershipId") UUID membershipId);

    /**
     * Effective permissions of a user inside a tenant: only active memberships, only
     * non-expired role assignments (spec §4 — temporary access really expires).
     */
    @Query("""
            select distinct p.code
            from Membership m
            join MembershipRole mr on mr.membershipId = m.id
            join Role r on r.id = mr.roleId
            join RolePermission rp on rp.roleId = r.id
            join Permission p on p.id = rp.permissionId
            where m.tenantId = :tenantId
              and m.userId = :userId
              and m.status = de.buerokratiecopilot.identity.domain.Membership$Status.ACTIVE
              and (mr.expiresAt is null or mr.expiresAt > :now)
              and (m.accessValidFrom is null or m.accessValidFrom <= :now)
              and (m.accessValidUntil is null or m.accessValidUntil > :now)
            """)
    List<String> findEffectivePermissionCodes(@Param("tenantId") UUID tenantId,
                                              @Param("userId") UUID userId,
                                              @Param("now") java.time.Instant now);
}
