package de.buerokratiecopilot.billing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Settings of the platform itself (trial length, retention, bank details, wallet), kept in the database so the
 * platform administrator changes them in the admin area and not in a file on the server.
 */
@Component
public class PlatformSettings {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PlatformSettings(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public int trialDays() {
        return integer("billing.trial_days", 60);
    }

    public int retentionDays() {
        return integer("billing.retention_days", 100);
    }

    public double vatPercent() {
        Map<String, Object> raw = all();
        Object value = raw.get("billing.vat_percent");
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    public Map<String, Object> object(String key) {
        Object value = all().get(key);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        return new LinkedHashMap<>();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> all() {
        Map<String, Object> result = new LinkedHashMap<>();
        jdbc.query("select setting_key, setting_value::text as value from platform_settings order by setting_key",
                rs -> {
                    try {
                        result.put(rs.getString("setting_key"), json.readValue(rs.getString("value"),
                                new TypeReference<Object>() { }));
                    }
                    catch (Exception ex) {
                        throw new IllegalStateException("Unreadable platform setting " + rs.getString("setting_key"), ex);
                    }
                });
        return result;
    }

    @Transactional
    public void put(String key, Object value, UUID actor) {
        if (!key.startsWith("billing.")) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Einstellung: " + key);
        }
        try {
            jdbc.update("""
                    insert into platform_settings (setting_key, setting_value, updated_by) values (?, ?::jsonb, ?)
                    on conflict (setting_key) do update set setting_value = excluded.setting_value,
                        updated_at = now(), updated_by = excluded.updated_by
                    """, key, json.writeValueAsString(value), actor);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Wert ist ungültig");
        }
    }

    private int integer(String key, int fallback) {
        Object value = all().get(key);
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : fallback;
    }
}
