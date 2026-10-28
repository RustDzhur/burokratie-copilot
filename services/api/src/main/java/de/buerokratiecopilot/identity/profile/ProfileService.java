package de.buerokratiecopilot.identity.profile;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * What a person can change about themselves: the name shown, the language, the time zone and the photo.
 *
 * <p>Everything here is about the signed-in person and nobody else; there is no identifier a caller could change to reach
 * another person's profile. The e-mail address is shown but not changed here: it is the way in, and changing it needs to be
 * proven by the new address first.</p>
 */
@Service
public class ProfileService {

    static final Set<String> LOCALES = Set.of("de", "en", "ru");
    static final int MAX_AVATAR_BYTES = 400 * 1024;

    public record Avatar(String contentType, byte[] data, long version) {
    }

    private final JdbcTemplate jdbc;

    public ProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID userId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select u.email, u.display_name, u.locale, u.time_zone, a.version as avatar_version
                from users u left join user_avatars a on a.user_id = u.id where u.id = ?
                """, userId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("email", row.get("email"));
        view.put("displayName", row.get("display_name"));
        view.put("locale", row.get("locale"));
        view.put("timeZone", row.get("time_zone"));
        view.put("avatarVersion", row.get("avatar_version"));
        return view;
    }

    @Transactional(readOnly = true)
    public Long avatarVersion(UUID userId) {
        List<Long> rows = jdbc.query("select version from user_avatars where user_id = ?", (rs, i) -> rs.getLong(1), userId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Transactional
    public Map<String, Object> update(UUID userId, String displayName, String locale, String timeZone) {
        String name = displayName == null ? "" : displayName.strip();
        if (name.length() < 2 || name.length() > 120) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The name needs 2 to 120 characters");
        }
        if (locale == null || !LOCALES.contains(locale)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The language must be one of " + LOCALES);
        }
        try {
            ZoneId.of(timeZone == null ? "" : timeZone);
        }
        catch (DateTimeException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown time zone");
        }
        jdbc.update("update users set display_name = ?, locale = ?, time_zone = ?, updated_at = now() where id = ?",
                name, locale, timeZone, userId);
        return get(userId);
    }

    /** Stores the photo. The type is read from the bytes, never taken from what the caller says it is. */
    @Transactional
    public long saveAvatar(UUID userId, byte[] data) {
        if (data == null || data.length == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The picture is empty");
        }
        if (data.length > MAX_AVATAR_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The picture is larger than 400 KB");
        }
        String type = sniff(data);
        if (type == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only PNG, JPEG and WebP pictures are accepted");
        }
        Long version = jdbc.queryForObject("""
                insert into user_avatars (user_id, content_type, data) values (?, ?, ?)
                on conflict (user_id) do update set content_type = excluded.content_type, data = excluded.data,
                    version = user_avatars.version + 1, updated_at = now()
                returning version
                """, Long.class, userId, type, data);
        return version == null ? 1 : version;
    }

    @Transactional
    public void deleteAvatar(UUID userId) {
        jdbc.update("delete from user_avatars where user_id = ?", userId);
    }

    @Transactional(readOnly = true)
    public Optional<Avatar> avatar(UUID userId) {
        List<Avatar> rows = jdbc.query("select content_type, data, version from user_avatars where user_id = ?",
                (rs, i) -> new Avatar(rs.getString(1), rs.getBytes(2), rs.getLong(3)), userId);
        return rows.stream().findFirst();
    }

    static String sniff(byte[] d) {
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E'
                && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
