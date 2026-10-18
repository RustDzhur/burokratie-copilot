package de.buerokratiecopilot.documents.dates;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The vocabulary that explains a date, as a workspace may see and edit it (spec §9.3).
 *
 * <p><strong>Why this is a table and not a constant.</strong> Until migration V69 the words lived as regular
 * expressions in {@link DateClassifier}. A word that was not written there did not exist — and with a
 * thousand different letters, every fixed list is incomplete, and no user could add the word their own
 * authority uses. The taxonomy has to be maintainable without a deployment, so the defaults ship as data
 * ({@code tenant_id IS NULL}, {@code is_system = true}) and a workspace can add its own words or switch a
 * default off with a word of the same {@code (kind, phrase)} marked {@code enabled = false}.</p>
 *
 * <p><strong>What "effective" means.</strong> For a workspace the visible set is the platform defaults plus
 * the workspace's own rows. A workspace row with the same {@code kind} and the same phrase replaces the
 * default for that workspace — it is either a relabelled word or a switched-off one. This class is the
 * single owner of that merge: the classifier, the settings screen and the reset all go through it.</p>
 *
 * <p><strong>Why it is cached.</strong> The classifier asks for the vocabulary for every date found in a
 * document — a fifty-page letter can mean hundreds of calls. Reading 255+ rows from the database each time
 * would be the slowest thing in the pipeline for no reason. The merged set is cached per workspace for
 * {@value #CACHE_SECONDS} seconds; every write through this class clears the entry. The short lifetime is
 * deliberate: a second instance of the API may also edit the table, and a stale word for a minute is
 * tolerable while a cache that needs cross-instance invalidation is not.</p>
 */
@Service
public class DateMarkerService {

    /** One word as it is shown to a user and used by the classifier. */
    public record EffectiveMarker(UUID id, DateClassifier.Kind kind, String phrase, String label,
                                  boolean enabled, boolean system, boolean tenantOverride) {

        /** The shape the settings API serves. Kept here so the screen and the tests see one definition. */
        public Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id.toString());
            row.put("kind", kind.name());
            row.put("phrase", phrase);
            row.put("label", label);
            row.put("enabled", enabled);
            // system: the phrase belongs to the standard vocabulary (an override of a default keeps this true).
            // tenantOverride: this row is the workspace's own and replaces a default with the same kind and
            // phrase. Only a workspace row may be changed or deleted; a default only through an override.
            row.put("system", system);
            row.put("tenantOverride", tenantOverride);
            return row;
        }
    }

    /** A row as the table stores it. */
    private record Stored(UUID id, UUID tenantId, DateClassifier.Kind kind, String phrase, String label,
                          boolean enabled, boolean system) {
    }

    /** The merged vocabulary of one workspace, cached for a short while. */
    private record Snapshot(List<EffectiveMarker> all, List<DateClassifier.Marker> active, Instant expiresAt) {

        boolean fresh() {
            return Instant.now().isBefore(expiresAt);
        }
    }

    /** How long a merged vocabulary is reused. Short enough that another instance's edit is not a surprise. */
    private static final int CACHE_SECONDS = 60;

    private static final Duration CACHE_TTL = Duration.ofSeconds(CACHE_SECONDS);

    /** Minimum and maximum length of a phrase: one letter is not a word, 120 is the column's width. */
    private static final int MIN_PHRASE = 2;
    private static final int MAX_PHRASE = 120;
    private static final int MAX_LABEL = 200;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ConcurrentHashMap<UUID, Snapshot> cache = new ConcurrentHashMap<>();

    public DateMarkerService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * The effective vocabulary of a workspace: defaults plus own words, own words winning.
     *
     * <p>Includes disabled words, because the settings screen has to show what was switched off. When
     * {@code tenantId} is {@code null} only the platform defaults are returned; that is the fallback for a
     * background thread without a tenant, never a way to read another workspace's words.</p>
     */
    @Transactional(readOnly = true)
    public List<EffectiveMarker> list(UUID tenantId) {
        return snapshot(tenantId).all();
    }

    /**
     * The words the classifier may use: enabled ones only, merged, cached.
     *
     * <p>Called for every date in a document, which is why it is cached rather than queried. A switched-off
     * default never appears here — that is the whole point of storing a word with {@code enabled = false}.
     * </p>
     */
    public List<DateClassifier.Marker> active(UUID tenantId) {
        return snapshot(tenantId).active();
    }

    /** Adds a word to the workspace's own vocabulary, or switches a default off. */
    @Transactional
    public EffectiveMarker create(UUID tenantId, UUID actor, String kind, String phrase, String label,
                                  Boolean enabled) {
        requireTenant(tenantId);
        DateClassifier.Kind parsedKind = requireKind(kind);
        String normalized = requirePhrase(phrase);
        String normalizedLabel = normalizeLabel(label);
        boolean active = enabled == null || enabled;
        UUID id;
        try {
            id = jdbc.queryForObject("""
                    insert into date_markers (tenant_id, kind, phrase, label, enabled, is_system, created_by)
                    values (?, ?, ?, ?, ?, false, ?)
                    returning id
                    """, UUID.class, tenantId, parsedKind.name(), normalized, normalizedLabel, active, actor);
        }
        catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "Das Wort „" + normalized + "“ gibt es für die Datumsart " + parsedKind.name()
                            + " bereits. Ändern Sie den vorhandenen Eintrag statt einen zweiten anzulegen.");
        }
        invalidateAfterCommit(tenantId);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.SETTINGS, "DATE_MARKER_CREATED")
                .tenant(tenantId)
                .entity("DateMarker", id, normalized)
                .description("Wort „" + normalized + "“ für " + parsedKind.name()
                        + (active ? " angelegt" : " angelegt und abgeschaltet"))
                .build());
        return describe(tenantId, id);
    }

    /**
     * Changes one of the workspace's own words.
     *
     * <p>A platform default cannot be changed: it belongs to the platform and to every account. The settings
     * screen offers the way out that keeps the defaults intact — create the same word for this workspace with
     * {@code enabled = false}. That refusal is a conflict, not a validation error, because the request is
     * well-formed and only the target is not the caller's.</p>
     *
     * <p>{@code null} means "leave unchanged"; an empty label clears it.</p>
     */
    @Transactional
    public EffectiveMarker update(UUID tenantId, UUID actor, UUID id, Boolean enabled, String phrase,
                                  String label) {
        requireTenant(tenantId);
        Stored row = requireOwnRow(tenantId, id);
        String normalizedPhrase = phrase == null ? row.phrase() : requirePhrase(phrase);
        String normalizedLabel = label == null ? row.label() : normalizeLabel(label);
        boolean newEnabled = enabled == null ? row.enabled() : enabled;
        try {
            jdbc.update("""
                    update date_markers
                       set phrase = ?, label = ?, enabled = ?, updated_at = now()
                     where id = ? and tenant_id = ?
                    """, normalizedPhrase, normalizedLabel, newEnabled, id, tenantId);
        }
        catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE,
                    "Das Wort „" + normalizedPhrase + "“ gibt es für die Datumsart " + row.kind().name()
                            + " bereits.");
        }
        invalidateAfterCommit(tenantId);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.SETTINGS, "DATE_MARKER_UPDATED")
                .tenant(tenantId)
                .entity("DateMarker", id, normalizedPhrase)
                .description("Wort „" + normalizedPhrase + "“ für " + row.kind().name()
                        + (newEnabled ? " geändert" : " abgeschaltet"))
                .build());
        return describe(tenantId, id);
    }

    /**
     * Removes one of the workspace's own words.
     *
     * <p>For a word that replaced a default this restores the default: the merge simply falls back to the
     * platform row again. Deleting a platform row is refused for the same reason it cannot be edited.</p>
     */
    @Transactional
    public void delete(UUID tenantId, UUID actor, UUID id) {
        requireTenant(tenantId);
        Stored row = requireOwnRow(tenantId, id);
        jdbc.update("delete from date_markers where id = ? and tenant_id = ?", id, tenantId);
        invalidateAfterCommit(tenantId);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.SETTINGS, "DATE_MARKER_DELETED")
                .tenant(tenantId)
                .entity("DateMarker", id, row.phrase())
                .description("Wort „" + row.phrase() + "“ für " + row.kind().name() + " gelöscht")
                .build());
    }

    /**
     * Returns the workspace's vocabulary to the platform defaults by removing every own word.
     *
     * @return how many own words were removed
     */
    @Transactional
    public int reset(UUID tenantId, UUID actor) {
        requireTenant(tenantId);
        int deleted = jdbc.update("delete from date_markers where tenant_id = ?", tenantId);
        invalidateAfterCommit(tenantId);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.SETTINGS, "DATE_MARKERS_RESET")
                .tenant(tenantId)
                .description("Wortschatz der Datumsarten auf den Standard zurückgesetzt (" + deleted
                        + " eigene Wörter entfernt)")
                .build());
        return deleted;
    }

    // ------------------------------------------------------------------ reading and merging

    private Snapshot snapshot(UUID tenantId) {
        if (tenantId == null) {
            // No workspace context: the defaults are visible and safe to use, nothing else is. Not cached,
            // because there is no key that could ever be invalidated for it.
            return load(null);
        }
        Snapshot cached = cache.get(tenantId);
        if (cached != null && cached.fresh()) {
            return cached;
        }
        Snapshot fresh = load(tenantId);
        cache.put(tenantId, fresh);
        return fresh;
    }

    /** Reads the defaults and the workspace's rows, and lets an own row replace the default of the same word. */
    private Snapshot load(UUID tenantId) {
        List<Stored> rows = tenantId == null
                ? jdbc.query("""
                        select id, tenant_id, kind, phrase, label, enabled, is_system
                          from date_markers
                         where tenant_id is null
                        """, (rs, i) -> stored(rs))
                : jdbc.query("""
                        select id, tenant_id, kind, phrase, label, enabled, is_system
                          from date_markers
                         where tenant_id is null or tenant_id = ?
                        """, (rs, i) -> stored(rs), tenantId);

        Map<WordKey, Stored> defaults = new LinkedHashMap<>();
        Map<WordKey, Stored> own = new HashMap<>();
        for (Stored row : rows) {
            WordKey key = new WordKey(row.kind(), normalizePhrase(row.phrase()));
            if (row.system()) {
                defaults.putIfAbsent(key, row);
            }
            else {
                // The unique index allows one own row per kind + phrase, so a put is enough.
                own.put(key, row);
            }
        }

        List<WordKey> keys = new ArrayList<>(defaults.keySet());
        for (WordKey key : own.keySet()) {
            if (!defaults.containsKey(key)) {
                keys.add(key);
            }
        }
        // A stable order for the settings screen: grouped by kind, alphabetical inside a kind. The API's
        // order is part of what a client sees, so it must not depend on the database's row order.
        keys.sort((left, right) -> {
            int byKind = Integer.compare(left.kind().ordinal(), right.kind().ordinal());
            return byKind != 0 ? byKind : left.phrase().compareTo(right.phrase());
        });

        List<EffectiveMarker> all = new ArrayList<>(keys.size());
        List<DateClassifier.Marker> active = new ArrayList<>();
        for (WordKey key : keys) {
            boolean overridesDefault = own.containsKey(key) && defaults.containsKey(key);
            Stored effectiveRow = overridesDefault ? own.get(key) : defaults.getOrDefault(key, own.get(key));
            // `system` answers "does this phrase come from the standard vocabulary?", not "is this row the
            // platform's row". An override of a standard word still uses a standard phrase - the screen shows
            // it as such and only `tenantOverride` says who owns the row.
            boolean systemPhrase = effectiveRow.system() || overridesDefault;
            EffectiveMarker marker = new EffectiveMarker(effectiveRow.id(), effectiveRow.kind(),
                    key.phrase(), effectiveRow.label(), effectiveRow.enabled(), systemPhrase,
                    overridesDefault);
            all.add(marker);
            if (marker.enabled()) {
                active.add(new DateClassifier.Marker(marker.kind(), marker.phrase()));
            }
        }
        return new Snapshot(List.copyOf(all), List.copyOf(active), Instant.now().plus(CACHE_TTL));
    }

    private static Stored stored(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stored((UUID) rs.getObject("id"), (UUID) rs.getObject("tenant_id"),
                DateClassifier.Kind.valueOf(rs.getString("kind")), rs.getString("phrase"),
                rs.getString("label"), rs.getBoolean("enabled"), rs.getBoolean("is_system"));
    }

    /**
     * Reads one own row straight from the database, bypassing the cache.
     *
     * <p>A mutation has to answer with the row it just wrote. Going through {@link #list} would read the
     * cached snapshot, which is only dropped after the commit — the answer would show the word as it was
     * before the edit, or not at all for a new word.</p>
     */
    private EffectiveMarker describe(UUID tenantId, UUID id) {
        List<Stored> rows = jdbc.query("""
                select id, tenant_id, kind, phrase, label, enabled, is_system
                  from date_markers
                 where id = ? and tenant_id = ?
                """, (rs, i) -> stored(rs), id, tenantId);
        if (rows.isEmpty()) {
            throw notFound();
        }
        Stored row = rows.getFirst();
        String phrase = normalizePhrase(row.phrase());
        boolean defaultExists = isDefaultWord(row.kind(), phrase);
        return new EffectiveMarker(row.id(), row.kind(), phrase, row.label(), row.enabled(),
                defaultExists, !row.system() && defaultExists);
    }

    /** The workspace's own row, or the reason why it cannot be changed. */
    private Stored requireOwnRow(UUID tenantId, UUID id) {
        List<Stored> found = jdbc.query("""
                select id, tenant_id, kind, phrase, label, enabled, is_system
                  from date_markers
                 where id = ? and (tenant_id is null or tenant_id = ?)
                """, (rs, i) -> stored(rs), id, tenantId);
        if (found.isEmpty()) {
            throw notFound();
        }
        Stored row = found.getFirst();
        if (row.system()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Standardwörter lassen sich nicht ändern oder löschen: sie gelten für alle Konten. "
                            + "Um dieses Wort abzuschalten, legen Sie dasselbe Wort mit enabled=false an.");
        }
        return row;
    }

    /** Whether the vocabulary contains a platform default with this kind and phrase. */
    private boolean isDefaultWord(DateClassifier.Kind kind, String phrase) {
        Boolean exists = jdbc.queryForObject("""
                select exists (select 1 from date_markers
                                where tenant_id is null and kind = ? and lower(phrase) = ?)
                """, Boolean.class, kind.name(), phrase);
        return Boolean.TRUE.equals(exists);
    }

    // ------------------------------------------------------------------ validation

    private static DateClassifier.Kind requireKind(String kind) {
        if (kind == null || kind.isBlank()) {
            throw invalid("Bitte eine Datumsart angeben.");
        }
        DateClassifier.Kind parsed;
        try {
            parsed = DateClassifier.Kind.valueOf(kind.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw invalid("Unbekannte Datumsart: " + kind + ". Erlaubt sind die Datumsarten aus der Liste.");
        }
        if (parsed == DateClassifier.Kind.OTHER) {
            throw invalid("OTHER ist keine Datumsart, die ein Wort erklärt; bitte eine der anderen wählen.");
        }
        return parsed;
    }

    /**
     * The stored form of a phrase: trimmed, single spaces, lower case.
     *
     * <p>Lower case and collapsed spaces are what makes "Zahlungs-Ziel" written with a stray double space in
     * the settings screen still match the same letters. The length bounds keep a word useful: one letter
     * would match everywhere, and more than the column's width cannot be stored.</p>
     */
    static String normalizePhrase(String phrase) {
        if (phrase == null) {
            return null;
        }
        return phrase.replace('\u00A0', ' ').strip().replaceAll("\\s+", " ").toLowerCase(Locale.GERMAN);
    }

    private static String requirePhrase(String phrase) {
        String normalized = normalizePhrase(phrase);
        if (normalized == null || normalized.length() < MIN_PHRASE) {
            throw invalid("Ein Wort muss mindestens " + MIN_PHRASE + " Zeichen lang sein.");
        }
        if (normalized.length() > MAX_PHRASE) {
            throw invalid("Ein Wort darf höchstens " + MAX_PHRASE + " Zeichen lang sein.");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Ein Wort darf keine Steuerzeichen enthalten.");
        }
        return normalized;
    }

    private static String normalizeLabel(String label) {
        if (label == null) {
            return null;
        }
        String normalized = label.replace('\u00A0', ' ').strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > MAX_LABEL) {
            throw invalid("Eine Bezeichnung darf höchstens " + MAX_LABEL + " Zeichen lang sein.");
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Eine Bezeichnung darf keine Steuerzeichen enthalten.");
        }
        return normalized;
    }

    private static void requireTenant(UUID tenantId) {
        if (tenantId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Wörter lassen sich nur im eigenen Konto pflegen.");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.NOT_FOUND, "Dieses Wort gibt es nicht.");
    }

    // ------------------------------------------------------------------ cache

    /**
     * Clears the cache only after the surrounding transaction committed.
     *
     * <p>Clearing before the commit would let a concurrent reader load the old rows between the clear and the
     * commit and cache them for a minute — the edit would appear to be ignored. After the commit the next
     * read sees the new rows.</p>
     */
    private void invalidateAfterCommit(UUID tenantId) {
        if (tenantId == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cache.remove(tenantId);
                }
            });
        }
        else {
            cache.remove(tenantId);
        }
    }

    private record WordKey(DateClassifier.Kind kind, String phrase) {
    }
}
