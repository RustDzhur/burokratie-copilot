package de.buerokratiecopilot.identity.navigation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Who sees which entries of the sidebar, in which order (table {@code user_navigation_preferences}).
 *
 * <p><strong>Why this belongs to a person and not to the account.</strong> The sidebar used to be the
 * same for everybody. Whoever checks invoices every day wants the finance entries at the top, and somebody
 * who never opens the integrations wants them out of the way — a setting of the whole account would have
 * changed the order for every colleague. The row is therefore keyed by {@code user_id}; {@code tenant_id}
 * is the account the row was saved in and the scope the row level security policy checks.</p>
 *
 * <p><strong>Why the entry identifiers are not validated against a catalogue.</strong> The list of screens
 * lives in the frontend. A backend list would be a second catalogue that goes stale with the first one, and
 * a screen added in a frontend release would make every already saved setting invalid — an unknown
 * identifier is therefore stored as it is. That is the same decision as in the date marker vocabulary
 * ({@link de.buerokratiecopilot.documents.dates.DateMarkerService}): the set grows, the stored data must
 * not have to be migrated for it.</p>
 *
 * <p><strong>What is checked.</strong> Only the shape: both fields are arrays of at most
 * {@value #MAX_ENTRIES} non-blank strings of at most {@value #MAX_ENTRY_LENGTH} characters, no entry twice
 * within a list, and no entry in both lists. A visible entry that is also hidden has no meaning, and a
 * stored contradiction would make every client disagree about the screen.</p>
 */
@Service
public class UserNavigationService {

    /** The most entries a list may hold; more than any real menu has. */
    static final int MAX_ENTRIES = 200;

    /** The longest entry identifier: a name, not prose. */
    static final int MAX_ENTRY_LENGTH = 64;

    /** The shape of the stored document. Bumped only when the meaning of the fields changes. */
    private static final int FORMAT_VERSION = 1;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;

    public UserNavigationService(JdbcTemplate jdbc, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    /**
     * What the interface needs to render the sidebar: the visible entries in their explicit order, the
     * hidden ones, and when the person last saved them.
     *
     * <p>{@code updatedAt} is {@code null} when nothing was saved; the client then applies its default.
     * An empty {@code order} and an empty {@code hidden} list are the shape the contract asks for, not an
     * error. The field is emitted even when it is {@code null}, although the application default omits
     * null properties: "never saved" is part of the contract and the client distinguishes it from a
     * missing field.</p>
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NavigationPreferences(List<String> order, List<String> hidden, Instant updatedAt) {
    }

    /** A row as the table stores it. */
    private record Stored(String preference, Instant updatedAt) {
    }

    /**
     * The saved settings of one person, or empty lists when nothing was saved.
     *
     * <p>The query names the tenant explicitly although the row level security policy already filters by
     * it: the policy is the second line of defence, not a reason to write a query that could return a
     * foreign row if the policy were ever missing (the same rule as in {@code DateMarkerService}).</p>
     */
    @Transactional(readOnly = true)
    public NavigationPreferences read(UUID tenantId, UUID userId) {
        requireScope(tenantId, userId);
        List<Stored> rows = jdbc.query("""
                select preference, updated_at
                  from user_navigation_preferences
                 where user_id = ? and tenant_id = ?
                """, (rs, index) -> new Stored(rs.getString("preference"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()), userId, tenantId);
        if (rows.isEmpty()) {
            return new NavigationPreferences(List.of(), List.of(), null);
        }
        Stored row = rows.getFirst();
        JsonNode document = parse(row.preference());
        return new NavigationPreferences(entries(document, "order"), entries(document, "hidden"), row.updatedAt());
    }

    /**
     * Replaces the saved settings of one person.
     *
     * <p>A full replacement, not a merge: the interface sends both lists it has, and a merge would make
     * removing an entry impossible.</p>
     */
    @Transactional
    public NavigationPreferences save(UUID tenantId, UUID userId, List<String> order, List<String> hidden) {
        requireScope(tenantId, userId);
        List<String> visible = validate(order, "order");
        List<String> invisible = validate(hidden, "hidden");
        rejectOverlap(visible, invisible);

        Instant savedAt = write(tenantId, userId, serialize(visible, invisible));
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.SETTINGS, "USER_NAVIGATION_SAVED")
                .tenant(tenantId)
                .entity("UserNavigationPreference", userId, null)
                .description("Menüeinstellungen gespeichert: " + visible.size() + " sichtbare, "
                        + invisible.size() + " ausgeblendete Einträge")
                .build());
        return new NavigationPreferences(visible, invisible, savedAt);
    }

    /**
     * Removes the settings of one person, so the interface shows its default again.
     *
     * <p>Only the caller's own row is removed: the tenant is part of the {@code where} clause, and the
     * row level security policy refuses a foreign row even if the clause were wrong. Resetting when
     * nothing was saved is not an error — the interface asked for the default and the default applies.</p>
     */
    @Transactional
    public void reset(UUID tenantId, UUID userId) {
        requireScope(tenantId, userId);
        int deleted = jdbc.update("""
                delete from user_navigation_preferences
                 where user_id = ? and tenant_id = ?
                """, userId, tenantId);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.SETTINGS, "USER_NAVIGATION_RESET")
                .tenant(tenantId)
                .entity("UserNavigationPreference", userId, null)
                .description("Menüeinstellungen auf den Standard zurückgesetzt (" + deleted
                        + (deleted == 1 ? " Eintrag entfernt)" : " Einträge entfernt)"))
                .build());
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes the row and returns the timestamp the database used.
     *
     * <p>An update first, then an insert: the two statements are the readable form of an upsert whose
     * conflict target is the primary key. The insert can only fail if the row exists for this person in
     * <em>another</em> account (row level security hides it from the update, so the update reports zero
     * rows). That is a conflict the caller can do nothing about silently, so it is answered as one
     * instead of surfacing as a duplicate key.</p>
     */
    private Instant write(UUID tenantId, UUID userId, String preference) {
        int updated = jdbc.update("""
                update user_navigation_preferences
                   set preference = ?::jsonb, updated_at = now()
                 where user_id = ? and tenant_id = ?
                """, preference, userId, tenantId);
        if (updated > 0) {
            return savedAt(tenantId, userId);
        }
        try {
            return jdbc.queryForObject("""
                    insert into user_navigation_preferences (user_id, tenant_id, preference, updated_at)
                    values (?, ?, ?::jsonb, now())
                    returning updated_at
                    """, (rs, index) -> rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                    userId, tenantId, preference);
        }
        catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Für diese Person sind bereits Menüeinstellungen eines anderen Kontos gespeichert.");
        }
    }

    private Instant savedAt(UUID tenantId, UUID userId) {
        return jdbc.queryForObject("""
                select updated_at
                  from user_navigation_preferences
                 where user_id = ? and tenant_id = ?
                """, (rs, index) -> rs.getObject("updated_at", OffsetDateTime.class).toInstant(), userId, tenantId);
    }

    private String serialize(List<String> order, List<String> hidden) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("version", FORMAT_VERSION);
        stored.put("order", order);
        stored.put("hidden", hidden);
        try {
            return json.writeValueAsString(stored);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("A list of strings cannot fail to serialise", ex);
        }
    }

    /** One of the two arrays of the stored document. */
    private static List<String> entries(JsonNode document, String field) {
        JsonNode node = document.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalStateException("Stored navigation preferences are malformed: " + field
                    + " is not an array");
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode entry : node) {
            values.add(entry.asText());
        }
        return List.copyOf(values);
    }

    private JsonNode parse(String preference) {
        try {
            return json.readTree(preference);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored navigation preferences are not valid JSON", ex);
        }
    }

    // ------------------------------------------------------------------ validation

    /**
     * The checked form of a list.
     *
     * <p>{@code null} is refused rather than treated as an empty list: a client that omits a field made a
     * mistake, and silently hiding every entry of the other list would be a surprising repair. What is
     * stored is exactly what was checked — entries are not trimmed or lower-cased, because an identifier
     * is a name the frontend compares verbatim.</p>
     */
    private static List<String> validate(List<String> values, String field) {
        if (values == null) {
            throw invalid("Das Feld „" + field + "“ muss ein Array von Kennungen sein.");
        }
        if (values.size() > MAX_ENTRIES) {
            throw invalid("Das Feld „" + field + "“ darf höchstens " + MAX_ENTRIES + " Einträge enthalten.");
        }
        Set<String> seen = new HashSet<>();
        List<String> clean = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw invalid("Ein Eintrag in „" + field + "“ darf nicht leer sein.");
            }
            if (value.length() > MAX_ENTRY_LENGTH) {
                throw invalid("Ein Eintrag in „" + field + "“ darf höchstens " + MAX_ENTRY_LENGTH
                        + " Zeichen lang sein.");
            }
            if (!seen.add(value)) {
                throw invalid("Der Eintrag „" + value + "“ steht in „" + field + "“ doppelt.");
            }
            clean.add(value);
        }
        return List.copyOf(clean);
    }

    /** An entry cannot be visible and hidden at the same time: the interface would have to choose one. */
    private static void rejectOverlap(List<String> order, List<String> hidden) {
        Set<String> visible = new HashSet<>(order);
        for (String value : hidden) {
            if (visible.contains(value)) {
                throw invalid("Der Eintrag „" + value + "“ darf nicht gleichzeitig sichtbar und "
                        + "ausgeblendet sein.");
            }
        }
    }

    private static void requireScope(UUID tenantId, UUID userId) {
        if (tenantId == null || userId == null) {
            throw new IllegalStateException("Navigation preferences require a tenant and a user");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }
}
