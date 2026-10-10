package de.buerokratiecopilot.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes secrets and personal data from anything that is about to be written to a log or to
 * the audit trail (spec §33: "Не логируй пароли, токены, полные банковские реквизиты").
 *
 * <p>It is a defensive last line, not a licence to log sensitive values: callers must still
 * pass only what is needed. Values are masked, names of fields are kept so an audit entry
 * stays meaningful.</p>
 */
public final class LogSanitizer {

    /** Field names whose values are never written in clear text. */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwort", "passwd", "pwd", "newpassword", "oldpassword", "currentpassword",
            "passwordhash", "token", "accesstoken", "refreshtoken", "idtoken", "sessiontoken",
            "tokenhash", "apikey", "api_key", "secret", "clientsecret", "client_secret",
            "authorization", "cookie", "set-cookie", "privatekey", "encryptionkey",
            "secretencrypted", "credentialencrypted", "encryptedpayload", "tan", "pin",
            "iban", "bic", "cardnumber", "cvv", "accountnumber", "steuerid", "taxid",
            "recoverycode", "otp", "totpsecret");

    private static final Pattern IBAN_LIKE = Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{10,30}\\b");
    private static final Pattern EMAIL_LIKE = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._\\-]+");

    private LogSanitizer() {
    }

    /** Masks the value of every sensitive key in a map of structured data. */
    public static Map<String, Object> sanitize(Map<String, ?> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, isSensitiveKey(key) ? "***" : sanitizeValue(value)));
        return result;
    }

    /** Masks obvious secrets inside a free-text message (exception messages, user agents). */
    public static String sanitizeText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = BEARER.matcher(text).replaceAll("Bearer ***");
        result = EMAIL_LIKE.matcher(result).replaceAll(m -> maskEmail(m.group()));
        result = IBAN_LIKE.matcher(result).replaceAll("***IBAN***");
        return result;
    }

    /** Keeps the first character and the domain of an address so support can still triage. */
    public static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("_", "");
        return SENSITIVE_KEYS.stream()
                .map(k -> k.replace("_", "").replace("-", ""))
                .anyMatch(normalized::contains);
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof String string) {
            return sanitizeText(string);
        }
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, ?> typed = (Map<String, ?>) map;
            return sanitize(typed);
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.stream().map(LogSanitizer::sanitizeValue).toList();
        }
        return value;
    }
}
