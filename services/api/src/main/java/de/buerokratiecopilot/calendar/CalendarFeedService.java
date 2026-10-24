package de.buerokratiecopilot.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The calendar as an iCalendar feed that a phone subscribes to.
 *
 * <p>Why a feed and not a push: every calendar app reads iCalendar from a URL (iPhone Calendar natively, Android
 * through ICSx5 or Google Calendar), nothing has to be installed in the platform's name, and the platform never holds
 * a login to somebody's Google or Apple account. The phone asks again on its own (the feed says hourly), so an entry the
 * platform creates from a document appears on the phone within the hour, and the phone itself rings: every entry
 * carries alarms, which the calendar app schedules locally.</p>
 *
 * <p>The URL is the credential. It holds a secret of 256 random bits, only its hash is stored, it can be revoked, and a
 * revoked or unknown secret gets the same 404. Entries of confidential documents (medical) are published without their
 * details: a calendar entry appears on a lock screen.</p>
 */
@Service
public class CalendarFeedService {

    private static final SecureRandom RANDOM = new SecureRandom();
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> CONFIDENTIAL_TYPES = List.of("medizinische_rechnung", "medizinisches_dokument");

    private final JdbcTemplate jdbc;
    private final CalendarEventService events;
    private final String baseUrl;

    public CalendarFeedService(JdbcTemplate jdbc, CalendarEventService events,
                               @Value("${burokratie.security.public-base-url:http://localhost:3000}") String baseUrl) {
        this.jdbc = jdbc;
        this.events = events;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** A feed address as the person sees it in the list: without the secret, which is shown once. */
    public record Feed(UUID id, String label, String locale, String createdAt, String lastUsedAt) {
    }

    /** The one time the secret is visible. */
    public record Created(UUID id, String label, String token) {
    }

    /** Who a secret belongs to. */
    public record Owner(UUID tokenId, UUID tenantId, UUID userId, String locale) {
    }

    @Transactional
    public Created create(UUID tenantId, UUID userId, String label, String locale) {
        String cleanLabel = label == null || label.isBlank() ? "Handy" : label.strip();
        if (cleanLabel.length() > 100) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "The name is too long");
        }
        String language = locale != null && List.of("de", "en", "ru").contains(locale) ? locale : "de";
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = jdbc.queryForObject("""
                insert into calendar_feed_tokens (id, tenant_id, user_id, token_hash, label, locale)
                values (gen_random_uuid(), ?, ?, ?, ?, ?) returning id
                """, UUID.class, tenantId, userId, hash(token), cleanLabel, language);
        return new Created(id, cleanLabel, token);
    }

    @Transactional(readOnly = true)
    public List<Feed> list(UUID tenantId, UUID userId) {
        return jdbc.query("""
                select id, label, locale, created_at, last_used_at from calendar_feed_tokens
                where tenant_id = ? and user_id = ? and revoked_at is null order by created_at
                """, (rs, i) -> new Feed(rs.getObject("id", UUID.class), rs.getString("label"),
                rs.getString("locale"), String.valueOf(rs.getObject("created_at", OffsetDateTime.class)),
                rs.getObject("last_used_at") == null ? null
                        : String.valueOf(rs.getObject("last_used_at", OffsetDateTime.class))), tenantId, userId);
    }

    @Transactional
    public void revoke(UUID tenantId, UUID userId, UUID id) {
        int changed = jdbc.update("""
                update calendar_feed_tokens set revoked_at = now()
                where id = ? and tenant_id = ? and user_id = ? and revoked_at is null
                """, id, tenantId, userId);
        if (changed == 0) {
            throw ApiException.notFound("calendar address");
        }
    }

    /** Looks the secret up (without a tenant: the address is opened without a login) and notes the use. */
    @Transactional
    public Optional<Owner> authenticate(String token) {
        if (token == null || token.length() < 20 || token.length() > 80) {
            return Optional.empty();
        }
        List<Owner> found = jdbc.query("""
                update calendar_feed_tokens set last_used_at = now()
                where token_hash = ? and revoked_at is null
                returning id, tenant_id, user_id, locale
                """, (rs, i) -> new Owner(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("locale")), hash(token));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    /** The feed of one person, as iCalendar text. */
    public String feedOf(Owner owner) {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Europe/Berlin"));
        List<Map<String, Object>> entries = RequestContext.runAs(owner.tenantId(), () -> {
            List<Map<String, Object>> list = events.events(owner.tenantId(), owner.userId(), false,
                    today.minusDays(7), today.plusDays(390));
            return withConfidentialFlags(jdbc, list);
        });
        return render(entries, labels(owner.locale()));
    }

    /**
     * Marks the entries that belong to a task of a confidential document type.
     *
     * <p>Shared with the CalDAV connection: what must not appear on a phone's lock screen must not be
     * pushed to an external calendar either.</p>
     */
    public static List<Map<String, Object>> withConfidentialFlags(JdbcTemplate jdbc,
                                                                  List<Map<String, Object>> entries) {
        java.util.Set<String> confidential = new java.util.HashSet<>();
        for (Map<String, Object> entry : entries) {
            if (entry.get("task_id") == null) {
                continue;
            }
            // jsonb_exists_any is the function behind the "?|" operator; the operator itself is a JDBC placeholder.
            Boolean secret = jdbc.queryForObject("""
                    select exists (select 1 from tasks t where t.id = ?::uuid
                                   and jsonb_exists_any(t.tags, ?::text[]))
                    """, Boolean.class, String.valueOf(entry.get("task_id")),
                    "{" + String.join(",", CONFIDENTIAL_TYPES) + "}");
            if (Boolean.TRUE.equals(secret)) {
                confidential.add(String.valueOf(entry.get("task_id")));
            }
        }
        List<Map<String, Object>> marked = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>(entry);
            copy.put("confidential",
                    entry.get("task_id") != null && confidential.contains(String.valueOf(entry.get("task_id"))));
            marked.add(copy);
        }
        return marked;
    }

    record Labels(String calendar, String reminder, String deadline, String appointment, String openInApp,
                  String type, String caseLabel, String confidentialTitle, String confidentialBody) {
    }

    static Labels labels(String locale) {
        return switch (locale == null ? "de" : locale) {
            case "en" -> new Labels("Bürokratie-Copilot", "Reminder", "Deadline", "Appointment", "Open in the app",
                    "Type", "Case", "Confidential entry", "Details are not shown on the phone. Open the app.");
            case "ru" -> new Labels("Bürokratie-Copilot", "Напоминание", "Срок", "Встреча", "Открыть в приложении",
                    "Вид", "Дело", "Конфиденциальная запись",
                    "Подробности на телефоне не показываются. Откройте приложение.");
            default -> new Labels("Bürokratie-Copilot", "Erinnerung", "Frist", "Termin", "In der Anwendung öffnen",
                    "Art", "Vorgang", "Vertraulicher Eintrag",
                    "Einzelheiten werden auf dem Telefon nicht angezeigt. In der Anwendung öffnen.");
        };
    }

    String render(List<Map<String, Object>> entries, Labels labels) {
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//Buerokratie-Copilot//Kalender//DE");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:PUBLISH");
        line(out, "X-WR-CALNAME:" + escape(labels.calendar()));
        line(out, "X-WR-TIMEZONE:Europe/Berlin");
        // The phone asks again by itself; Apple reads the first line, Google the second.
        line(out, "REFRESH-INTERVAL;VALUE=DURATION:PT1H");
        line(out, "X-PUBLISHED-TTL:PT1H");

        String stamp = OffsetDateTime.now(ZoneOffset.UTC).format(STAMP);
        for (Map<String, Object> entry : entries) {
            renderVEvent(out, entry, labels, stamp, baseUrl);
        }
        line(out, "END:VCALENDAR");
        return out.toString();
    }

    /**
     * One entry as a VEVENT block.
     *
     * <p>Shared with the CalDAV connection: an event pushed to an external calendar must carry the same
     * shape as the feed's entries, including the same UID, alarms and confidential masking.</p>
     */
    static void renderVEvent(StringBuilder out, Map<String, Object> entry, Labels labels, String stamp,
                             String baseUrl) {
        LocalDate start = LocalDate.parse(String.valueOf(entry.get("date")));
        LocalDate end = entry.get("end_date") == null ? start
                : LocalDate.parse(String.valueOf(entry.get("end_date")));
        boolean confidential = Boolean.TRUE.equals(entry.get("confidential"));
        String type = String.valueOf(entry.get("type"));
        boolean deadline = "DEADLINE".equals(type);
        String typeLabel = deadline ? labels.deadline()
                : "REMINDER".equals(type) ? labels.reminder() : labels.appointment();
        String title = confidential ? labels.confidentialTitle() : String.valueOf(entry.get("title"));

        StringBuilder description = new StringBuilder();
        if (confidential) {
            description.append(labels.confidentialBody());
        }
        else {
            description.append(labels.type()).append(": ").append(typeLabel);
            if (entry.get("case_reference") != null) {
                description.append("\n").append(labels.caseLabel()).append(": ")
                        .append(entry.get("case_reference"));
            }
            if (entry.get("note") != null && !String.valueOf(entry.get("note")).isBlank()) {
                description.append("\n\n").append(entry.get("note"));
            }
        }
        String link = entry.get("document_id") != null && !confidential
                ? baseUrl + "/documents/" + entry.get("document_id") : baseUrl + "/calendar";
        description.append("\n\n").append(labels.openInApp()).append(": ").append(link);

        line(out, "BEGIN:VEVENT");
        line(out, "UID:" + escape(String.valueOf(entry.get("id"))) + "@copilot");
        line(out, "DTSTAMP:" + stamp);
        line(out, "DTSTART;VALUE=DATE:" + start.format(DAY));
        line(out, "DTEND;VALUE=DATE:" + end.plusDays(1).format(DAY));
        line(out, "SUMMARY:" + escape((deadline ? typeLabel + ": " : "") + title));
        line(out, "DESCRIPTION:" + escape(description.toString()));
        line(out, "URL:" + link);
        line(out, "CATEGORIES:" + escape(typeLabel));
        line(out, "STATUS:CONFIRMED");
        line(out, "TRANSP:TRANSPARENT");
        // Alarms the phone schedules itself. For an all-day entry the trigger counts from midnight of its day:
        // -PT15H is nine in the morning on the day before, PT8H is eight in the morning on the day itself.
        if (deadline) {
            alarm(out, title, "-P2DT15H"); // three days ahead, 09:00
        }
        alarm(out, title, "-PT15H"); // the day before, 09:00
        alarm(out, title, "PT8H"); // the day itself, 08:00
        line(out, "END:VEVENT");
    }

    private static void alarm(StringBuilder out, String title, String trigger) {
        line(out, "BEGIN:VALARM");
        line(out, "ACTION:DISPLAY");
        line(out, "DESCRIPTION:" + escape(title));
        line(out, "TRIGGER:" + trigger);
        line(out, "END:VALARM");
    }

    /** iCalendar text values: backslash, semicolon, comma and newlines are escaped. */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n");
    }

    /** One content line, folded at 75 octets as RFC 5545 requires, ended by CRLF. */
    static void line(StringBuilder out, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 75) {
            out.append(content).append("\r\n");
            return;
        }
        StringBuilder current = new StringBuilder();
        int size = 0;
        int limit = 75;
        for (int i = 0; i < content.length(); ) {
            int codePoint = content.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            int width = character.getBytes(StandardCharsets.UTF_8).length;
            if (size + width > limit) {
                out.append(current).append("\r\n ");
                current.setLength(0);
                size = 0;
                limit = 74; // the continuation line starts with one space
            }
            current.append(character);
            size += width;
            i += Character.charCount(codePoint);
        }
        out.append(current).append("\r\n");
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
