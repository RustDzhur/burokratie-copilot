package de.buerokratiecopilot.agents.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Small parsing helpers shared by the catalogue agents.
 *
 * <p>Agent inputs arrive as strings from an HTTP body, a webhook or a test. Parsing them in one
 * place keeps the refusal behaviour consistent: a blank value is "not supplied" and returns
 * {@code null}, an invalid value also returns {@code null} — the caller decides which of the two
 * it is and refuses with the matching reason code instead of guessing a default.</p>
 *
 * <p>The methods also cover the shapes the industry agents need for their own output: a stable
 * idempotency key, an HTML escape for the reports they generate, and a deterministic hash for a
 * blob that has to be recognised on a rerun. Nothing here touches the database or the request
 * context; every method is a pure function.</p>
 */
public final class AgentInputs {

    /** The business time zone of the platform; due dates are interpreted in it. */
    public static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int MAX_SOURCE_KEY = 300;

    private AgentInputs() {
    }

    /** The trimmed text, or {@code null} when the value is absent or blank. */
    public static String text(String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip();
    }

    /** Alias of {@link #text(String)} used by the first industry wave. */
    public static String clean(String raw) {
        return text(raw);
    }

    public static String blankTo(String raw, String fallback) {
        String value = text(raw);
        return value == null ? fallback : value;
    }

    public static UUID uuid(String raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Parses an ISO date ({@code 2026-10-26}) or a German date ({@code 26.10.2026}). */
    public static LocalDate date(String raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        }
        catch (DateTimeParseException ignored) {
            // Fall through to the German notation.
        }
        try {
            return LocalDate.parse(value, GERMAN_DATE);
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** Reads a date from a JDBC value; the text form is parsed by {@link #date(String)}. */
    public static LocalDate date(Object raw) {
        return raw == null ? null : date(String.valueOf(raw));
    }

    /** Parses an ISO date-time, tolerating a missing time (then midnight). */
    public static LocalDateTime dateTime(String raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        }
        catch (DateTimeParseException ignored) {
            // Try a date-only value next.
        }
        LocalDate date = date(value);
        return date == null ? null : date.atStartOfDay();
    }

    /** Like {@link #dateTime(String)}, but also accepts an {@link Instant} in ISO form. */
    public static LocalDateTime localDateTime(String raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        }
        catch (DateTimeParseException ignored) {
            // continue
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), BERLIN);
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** An instant from an ISO instant, a local date-time or a plain date; null when unparsable. */
    public static Instant instant(String raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        }
        catch (DateTimeParseException ignored) {
            // A local date/time without a zone is read in Berlin, the tenant's business zone.
        }
        try {
            return LocalDateTime.parse(value).atZone(BERLIN).toInstant();
        }
        catch (DateTimeParseException ignored) {
            // A plain date means the start of that business day.
        }
        LocalDate date = date(value);
        return date == null ? null : date.atStartOfDay(BERLIN).toInstant();
    }

    /** The business-day instant the recalculation agents use for a due date: 09:00 Berlin. */
    public static Instant instant(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.of(9, 0)).atZone(BERLIN).toInstant();
    }

    public static LocalTime time(String raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** Parses a number, accepting a comma as decimal separator and a trailing currency symbol. */
    public static BigDecimal decimal(String raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        value = value.replace("€", "").replace("EUR", "").replace(" ", "").strip();
        if (value.contains(",") && !value.contains(".")) {
            value = value.replace(',', '.');
        }
        try {
            return new BigDecimal(value);
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Parses an integer, or {@code null} when absent/unparsable. */
    public static Integer integer(String raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Parses an integer inside {@code [min, max]}; anything else returns the fallback. */
    public static int integer(String raw, int fallback, int min, int max) {
        Integer value = integer(raw);
        if (value == null || value < min || value > max) {
            return fallback;
        }
        return value;
    }

    public static BigDecimal decimalInRange(String raw, BigDecimal fallback, BigDecimal min,
                                            BigDecimal max) {
        BigDecimal value = decimal(raw);
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            return fallback;
        }
        return value;
    }

    public static boolean bool(String raw, boolean fallback) {
        String value = text(raw);
        if (value == null) {
            return fallback;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "ja", "yes", "1" -> true;
            case "false", "nein", "no", "0" -> false;
            default -> fallback;
        };
    }

    /**
     * Splits a comma-, semicolon-, pipe- or newline-separated list. Blank entries are dropped and
     * repeated entries are collapsed: the list is used for tags and keywords, where a duplicate
     * would only repeat the same task.
     */
    public static List<String> list(String raw) {
        String value = text(raw);
        if (value == null) {
            return List.of();
        }
        Set<String> parts = new LinkedHashSet<>();
        for (String part : value.split("[,;|\\n\\r]+")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return List.copyOf(parts);
    }

    public static List<UUID> uuidList(String raw) {
        List<UUID> values = new ArrayList<>();
        for (String part : list(raw)) {
            UUID id = uuid(part);
            if (id != null) {
                values.add(id);
            }
        }
        return List.copyOf(values);
    }

    /** Parses JSON, returning {@code null} when the text is not valid JSON. */
    public static JsonNode json(String raw) {
        String value = text(raw);
        if (value == null) {
            return null;
        }
        try {
            return JSON.readTree(value);
        }
        catch (JsonProcessingException ex) {
            return null;
        }
    }

    /** Converts a JSON value to text without inventing one; missing nodes return {@code null}. */
    public static String nodeText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        return value.isValueNode() ? value.asText().strip() : null;
    }

    public static BigDecimal nodeDecimal(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value.isNumber()) {
            return value.decimalValue();
        }
        return value.isTextual() ? decimal(value.asText()) : null;
    }

    public static Instant dueAt(LocalDate date, int daysOffset) {
        return dueAt(date, daysOffset, LocalTime.of(9, 0));
    }

    public static Instant dueAt(LocalDate date, int daysOffset, LocalTime time) {
        return date.plusDays(daysOffset).atTime(time).atZone(BERLIN).toInstant();
    }

    public static String money(BigDecimal value) {
        return value == null ? "0,00 EUR" : String.format(Locale.GERMANY, "%,.2f", value)
                + " EUR";
    }

    /** Trims and caps the text; the ellipsis is part of the returned value. */
    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    /** A stable, readable prefix for an artifact source key; never longer than the column. */
    public static String sourceKey(String prefix, String... parts) {
        StringBuilder key = new StringBuilder(prefix);
        for (String part : parts) {
            key.append(':').append(part == null || part.isBlank() ? "-" : part.strip());
        }
        return truncate(key.toString(), MAX_SOURCE_KEY);
    }

    /** Lower-case, hyphenated form of a label, safe for tags and source keys. */
    public static String slug(String raw, int max) {
        String value = text(raw);
        if (value == null) {
            return "unbenannt";
        }
        String slug = value.toLowerCase(Locale.ROOT).replace('ä', 'a').replace('ö', 'o')
                .replace('ü', 'u').replace('ß', 's')
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = "unbenannt";
        }
        return truncate(slug, max);
    }

    /** Deterministic hash of a string, used where a source key has to be short. */
    public static String sha256(String raw) {
        return sha256(raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * Stable key for idempotency: SHA-256 over the parts, separated so concatenation is
     * unambiguous. Shortened to 24 hex characters, enough for a lookup key.
     */
    public static String key(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1f);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 24);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /** Non-blank values of a list, capped at {@code max}, preserving order. */
    public static List<String> distinct(List<String> values, int max) {
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String trimmed = text(value);
            if (trimmed != null) {
                seen.add(trimmed);
            }
            if (seen.size() >= max) {
                break;
            }
        }
        return new ArrayList<>(seen);
    }

    /** A JSON-free map builder, for small metadata documents. */
    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    /** Escapes the five characters that would break an HTML report. */
    public static String html(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** Maps a JDBC {@link java.sql.Date}/{@link java.sql.Timestamp} to a local date, or null. */
    public static LocalDate toLocalDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate date) {
            return date;
        }
        if (raw instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (raw instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().atZone(BERLIN).toLocalDate();
        }
        if (raw instanceof java.time.OffsetDateTime timestamp) {
            return timestamp.atZoneSameInstant(BERLIN).toLocalDate();
        }
        return null;
    }

    public static Instant toInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (raw instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (raw instanceof java.time.OffsetDateTime timestamp) {
            return timestamp.toInstant();
        }
        return null;
    }
}
