package de.buerokratiecopilot.identity.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Per-person interface settings, stored once for the person and not per workspace (table
 * {@code user_preferences}).
 *
 * <p>The accepted keys and values are a closed set. A preference is rendered into the page by the client,
 * so a value the application does not recognise is refused instead of stored: what is stored is what the
 * screen will later be told to apply.</p>
 */
@Service
public class UserPreferencesService {

    public static final String ACCESSIBILITY = "accessibility";

    private static final Map<String, Set<Object>> ACCESSIBILITY_VALUES = Map.of(
            "theme", Set.of("light", "dark", "system"),
            "contrast", Set.of("normal", "high"),
            "textScale", Set.of(100, 112, 125),
            "motion", Set.of("auto", "reduced"),
            "effects", Set.of("full", "reduced"),
            "colorCues", Set.of("off", "patterns"),
            "density", Set.of("comfortable", "compact"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public UserPreferencesService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The saved accessibility settings; an empty map when the person never saved any. */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> accessibility(UUID userId) {
        List<String> stored = jdbc.queryForList(
                "select pref_value::text from user_preferences where user_id = ? and pref_key = ?",
                String.class, userId, ACCESSIBILITY);
        if (stored.isEmpty()) {
            return Map.of();
        }
        try {
            return json.readValue(stored.get(0), Map.class);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored preferences are not valid JSON", ex);
        }
    }

    /** Replaces the saved accessibility settings with {@code values} after checking every one of them. */
    @Transactional
    public Map<String, Object> saveAccessibility(UUID userId, Map<String, Object> values) {
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : (values == null ? Map.<String, Object>of() : values).entrySet()) {
            Set<Object> allowed = ACCESSIBILITY_VALUES.get(entry.getKey());
            if (allowed == null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Einstellung: " + entry.getKey());
            }
            Object value = entry.getValue() instanceof Number number && number.doubleValue() == number.intValue()
                    ? (Object) number.intValue() : entry.getValue();
            if (!allowed.contains(value)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "Ungültiger Wert für " + entry.getKey() + ": " + entry.getValue());
            }
            clean.put(entry.getKey(), value);
        }
        try {
            jdbc.update("""
                    insert into user_preferences (user_id, pref_key, pref_value, updated_at)
                    values (?, ?, ?::jsonb, now())
                    on conflict (user_id, pref_key)
                    do update set pref_value = excluded.pref_value, updated_at = now()
                    """, userId, ACCESSIBILITY, json.writeValueAsString(clean));
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("A map of strings and numbers cannot fail to serialise", ex);
        }
        return clean;
    }
}
