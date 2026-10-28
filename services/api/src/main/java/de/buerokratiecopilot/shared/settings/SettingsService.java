package de.buerokratiecopilot.shared.settings;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Reads configurable values with a tenant override (spec §31).
 *
 * <p>Resolution order is: the tenant's own value, then the system default, then the caller's fallback.
 * A value that cannot be parsed is logged and the fallback is used — a malformed configuration must
 * not break document processing, but it must also not be silently ignored.</p>
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    private final SystemSettingRepository repository;

    public SettingsService(SystemSettingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<String> getString(String key) {
        UUID tenantId = RequestContext.tenantId().orElse(null);
        if (tenantId != null) {
            Optional<SystemSetting> tenantValue = repository.findByTenantIdAndSettingKey(tenantId, key);
            // A stored JSON null is "not set", so the platform default still applies.
            if (tenantValue.isPresent() && tenantValue.get().valueAsString() != null) {
                return Optional.of(tenantValue.get().valueAsString());
            }
        }
        return repository.findByTenantIdIsNullAndSettingKey(key)
                .map(SystemSetting::valueAsString);
    }

    @Transactional(readOnly = true)
    public double getDouble(String key, double fallback) {
        return getString(key).map(raw -> {
            try {
                return Double.parseDouble(raw.strip());
            }
            catch (NumberFormatException ex) {
                log.warn("Setting {} has a non numeric value '{}'; using {}", key, raw, fallback);
                return fallback;
            }
        }).orElse(fallback);
    }

    @Transactional(readOnly = true)
    public int getInt(String key, int fallback) {
        return (int) getDouble(key, fallback);
    }

    @Transactional(readOnly = true)
    public boolean getBoolean(String key, boolean fallback) {
        return getString(key).map(raw -> {
            String value = raw.strip().toLowerCase(java.util.Locale.ROOT);
            if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) {
                return true;
            }
            if ("false".equals(value) || "0".equals(value) || "no".equals(value)) {
                return false;
            }
            log.warn("Setting {} has a non boolean value '{}'; using {}", key, raw, fallback);
            return fallback;
        }).orElse(fallback);
    }
}
