package de.buerokratiecopilot.shared.settings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, UUID> {

    Optional<SystemSetting> findByTenantIdIsNullAndSettingKey(String settingKey);

    Optional<SystemSetting> findByTenantIdAndSettingKey(UUID tenantId, String settingKey);

    @Query("""
            select s from SystemSetting s
            where s.active = true and (s.tenantId is null or s.tenantId = :tenantId)
            """)
    List<SystemSetting> findEffective(@Param("tenantId") UUID tenantId);
}
