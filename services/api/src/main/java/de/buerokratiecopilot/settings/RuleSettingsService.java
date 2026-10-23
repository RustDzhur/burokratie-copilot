package de.buerokratiecopilot.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The thresholds and switches of the Radar and of document recognition, as a workspace may tune them (UI-SET-01).
 *
 * <p>Only the keys listed here can be changed, each with the range it may take, and a few with a relation to another key
 * (a "high" threshold may not lie below the plain one). Every change needs a reason and is written as a new version with the
 * previous value, who changed it and when; the platform default stays untouched and can always be returned to, again as a
 * new version. Values that carry a legal or financial meaning ({@code requires_verification}) are not offered here: they are
 * not a matter of sensitivity.</p>
 */
@Service
public class RuleSettingsService {

    /** What a key may hold. */
    private record Spec(String type, Double min, Double max, Set<String> allowed, String notAbove, String notBelow) {
        static Spec number(double min, double max) {
            return new Spec("NUMBER", min, max, null, null, null);
        }

        static Spec number(double min, double max, String notAbove, String notBelow) {
            return new Spec("NUMBER", min, max, null, notAbove, notBelow);
        }

        static Spec bool() {
            return new Spec("BOOLEAN", null, null, null, null, null);
        }

        static Spec choice(Set<String> allowed) {
            return new Spec("STRING", null, null, allowed, null, null);
        }
    }

    private static final Map<String, Spec> EDITABLE = new LinkedHashMap<>();

    static {
        EDITABLE.put("radar.enabled", Spec.bool());
        EDITABLE.put("radar.explain.enabled", Spec.bool());
        EDITABLE.put("radar.notify.min_severity", Spec.choice(Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")));
        EDITABLE.put("radar.invoice.amount_change_percent", Spec.number(0, 1000, "radar.invoice.amount_change_high_percent", null));
        EDITABLE.put("radar.invoice.amount_change_high_percent", Spec.number(0, 1000, null, "radar.invoice.amount_change_percent"));
        EDITABLE.put("radar.contract.notice_warning_days", Spec.number(0, 365, null, "radar.contract.notice_high_days"));
        EDITABLE.put("radar.contract.notice_high_days", Spec.number(0, 365, "radar.contract.notice_warning_days", null));
        EDITABLE.put("radar.case.missing_document_after_days", Spec.number(0, 90));
        EDITABLE.put("radar.task.overdue_high_days", Spec.number(0, 365));
        EDITABLE.put("classification.auto_accept_threshold", Spec.number(0.5, 1.0, null, "classification.review_threshold"));
        EDITABLE.put("classification.review_threshold", Spec.number(0, 1.0, "classification.auto_accept_threshold", null));
        EDITABLE.put("extraction.critical_field_review", Spec.bool());
        EDITABLE.put("document.duplicate_window_days", Spec.number(1, 3650));
        EDITABLE.put("documents.explain.enabled", Spec.bool());
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper json;

    public RuleSettingsService(JdbcTemplate jdbc, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.json = json;
    }

    private static void requireEditable(String key) {
        if (!EDITABLE.containsKey(key)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Diese Einstellung kann hier nicht geändert werden: " + key);
        }
    }

    private Object effective(UUID tenantId, String key, Function<String, Object> fallback) {
        List<String> own = jdbc.queryForList("select value::text from system_settings where tenant_id = ? and setting_key = ?",
                String.class, tenantId, key);
        if (!own.isEmpty()) {
            return parse(own.getFirst());
        }
        return fallback.apply(key);
    }

    private Object global(String key) {
        List<String> rows = jdbc.queryForList("select value::text from system_settings where tenant_id is null and setting_key = ?", String.class, key);
        return rows.isEmpty() ? null : parse(rows.getFirst());
    }

    private Object parse(String text) {
        try {
            return json.readValue(text, Object.class);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("A stored setting is not valid JSON", ex);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise a setting", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID tenantId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Spec> entry : EDITABLE.entrySet()) {
            String key = entry.getKey();
            // §4.3 removed the radar module. Its switches still exist as rows, and offering them would present a
            // control that changes nothing - the same lie as a menu entry for a module that is gone.
            if (key.startsWith("radar.")) {
                continue;
            }
            List<Map<String, Object>> defaults = jdbc.queryForList(
                    "select category, description::text as description, requires_verification from system_settings where tenant_id is null and setting_key = ?", key);
            if (defaults.isEmpty() || Boolean.TRUE.equals(defaults.getFirst().get("requires_verification"))) {
                continue;
            }
            List<Map<String, Object>> own = jdbc.queryForList("select version from system_settings where tenant_id = ? and setting_key = ?", tenantId, key);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("category", defaults.getFirst().get("category"));
            item.put("description", parse(String.valueOf(defaults.getFirst().get("description"))));
            item.put("type", entry.getValue().type());
            item.put("min", entry.getValue().min());
            item.put("max", entry.getValue().max());
            item.put("allowed", entry.getValue().allowed() == null ? null : entry.getValue().allowed().stream().sorted().toList());
            item.put("default", global(key));
            item.put("value", effective(tenantId, key, this::global));
            item.put("overridden", !own.isEmpty());
            item.put("version", own.isEmpty() ? 0 : own.getFirst().get("version"));
            out.add(item);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> change(UUID tenantId, UUID userId, String key, Object value, String reason) {
        requireEditable(key);
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Änderung braucht eine Begründung");
        }
        Spec spec = EDITABLE.get(key);
        Object checked = validate(spec, key, value);
        crossCheck(tenantId, spec, key, checked);

        Object previous = effective(tenantId, key, this::global);
        List<Map<String, Object>> own = jdbc.queryForList("select id, version from system_settings where tenant_id = ? and setting_key = ?", tenantId, key);
        UUID settingId;
        int version;
        if (own.isEmpty()) {
            Map<String, Object> base = jdbc.queryForMap(
                    "select category, value_type, description::text as description from system_settings where tenant_id is null and setting_key = ?", key);
            version = 1;
            settingId = jdbc.queryForObject("""
                    insert into system_settings (tenant_id, setting_key, category, value, value_type, description, requires_verification, version, updated_by)
                    values (?, ?, ?, ?::jsonb, ?, ?::jsonb, false, 1, ?) returning id
                    """, UUID.class, tenantId, key, base.get("category"), write(checked), base.get("value_type"), base.get("description"), userId);
        }
        else {
            settingId = (UUID) own.getFirst().get("id");
            version = ((Number) own.getFirst().get("version")).intValue() + 1;
            jdbc.update("update system_settings set value = ?::jsonb, version = ?, updated_by = ? where id = ?", write(checked), version, userId, settingId);
        }
        jdbc.update("""
                insert into system_setting_history (setting_id, tenant_id, setting_key, version, previous_value, new_value, changed_by, change_reason)
                values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, settingId, tenantId, key, version, write(previous), write(checked), userId, reason.strip());
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.SETTINGS, "RULE_SETTING_CHANGED")
                .entity("SystemSetting", settingId, key).description("Regel „" + key + "“ geändert (Version " + version + ")").build());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("value", checked);
        result.put("previous", previous);
        result.put("version", version);
        return result;
    }

    /** Back to the platform default, as a new version with its own reason. */
    @Transactional
    public Map<String, Object> reset(UUID tenantId, UUID userId, String key, String reason) {
        requireEditable(key);
        return change(tenantId, userId, key, global(key), reason);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(UUID tenantId, String key) {
        requireEditable(key);
        return jdbc.queryForList("""
                select version, previous_value::text as previous_value, new_value::text as new_value, changed_by, change_reason, created_at
                from system_setting_history where tenant_id = ? and setting_key = ? order by version desc
                """, tenantId, key);
    }

    private Object validate(Spec spec, String key, Object value) {
        switch (spec.type()) {
            case "BOOLEAN" -> {
                if (value instanceof Boolean) {
                    return value;
                }
                throw bad(key, "muss wahr oder falsch sein");
            }
            case "STRING" -> {
                if (value instanceof String text && spec.allowed().contains(text)) {
                    return text;
                }
                throw bad(key, "muss einer von " + spec.allowed().stream().sorted().toList() + " sein");
            }
            default -> {
                if (!(value instanceof Number number)) {
                    throw bad(key, "muss eine Zahl sein");
                }
                double d = number.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d) || d < spec.min() || d > spec.max()) {
                    throw bad(key, "muss zwischen " + spec.min() + " und " + spec.max() + " liegen");
                }
                return d == Math.rint(d) ? (Object) (long) d : (Object) d;
            }
        }
    }

    private void crossCheck(UUID tenantId, Spec spec, String key, Object checked) {
        if (!(checked instanceof Number number)) {
            return;
        }
        double value = number.doubleValue();
        if (spec.notAbove() != null) {
            double other = ((Number) effective(tenantId, spec.notAbove(), this::global)).doubleValue();
            if (value > other) {
                throw bad(key, "darf nicht über " + spec.notAbove() + " (" + other + ") liegen");
            }
        }
        if (spec.notBelow() != null) {
            double other = ((Number) effective(tenantId, spec.notBelow(), this::global)).doubleValue();
            if (value < other) {
                throw bad(key, "darf nicht unter " + spec.notBelow() + " (" + other + ") liegen");
            }
        }
    }

    private static ApiException bad(String key, String why) {
        return new ApiException(ErrorCode.INVALID_REQUEST, "Der Wert für " + key + " " + why);
    }
}
