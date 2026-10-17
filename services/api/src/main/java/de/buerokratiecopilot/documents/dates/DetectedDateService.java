package de.buerokratiecopilot.documents.dates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.calendar.CalendarEventService;
import de.buerokratiecopilot.documents.fields.FieldValueParser;

/**
 * Dates in a document, classified by their wording, and the ones that name an obligation put in the calendar
 * without anybody clicking anything (spec §13.2).
 *
 * <p><strong>What was wrong before.</strong> Deadlines were computed from a register of legal rules, that
 * register ships empty on purpose, and "the date written on the paper" is not something a rule computes — so
 * a photographed letter with a deadline had its date read and discarded. The first version of this service
 * then offered every date with a button beside it, which turned every letter into a decision and added a
 * control to a screen that already has too many.</p>
 *
 * <p><strong>What it does now.</strong> {@link DateClassifier} reads the words around each date and says what
 * it is. Dates that name a payment, a reply, an appointment or an expiry become calendar entries on their own;
 * the date the letter was written is left out, because a letter's own date is not an appointment. Nothing is
 * asked of the reader.</p>
 *
 * <p><strong>What it deliberately still does not do.</strong> It does not compute a deadline from law. It does
 * not guess: a date whose wording it cannot read stays {@code OTHER} and goes nowhere, and a weak reading is
 * not acted on — an automatic entry that is wrong is relied upon, and a calendar people stop trusting is
 * worse than one that waits.</p>
 *
 * <p><strong>Every entry it makes carries the sentence and the document</strong>, so a wrong entry can be
 * traced to the page that produced it, and reprocessing a document does not duplicate its entries.</p>
 */
@Service
public class DetectedDateService {

    private static final Logger log = LoggerFactory.getLogger(DetectedDateService.class);

    /** The shapes a German letter writes a date in. Kept to what can be read unambiguously. */
    private static final Pattern NUMERIC = Pattern.compile("\\b(\\d{1,2})[.\\-/](\\d{1,2})[.\\-/](\\d{2,4})\\b");
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

    /** How much text around a date is kept as its explanation. */
    private static final int SNIPPET_BEFORE = 90;
    private static final int SNIPPET_AFTER = 90;

    /** How many pages are scanned for dates in one document. */
    private static final int MAX_PAGES = 50;

    private final JdbcTemplate jdbc;
    private final CalendarEventService calendar;

    public DetectedDateService(JdbcTemplate jdbc, CalendarEventService calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    /** One date, with where it was found and what its wording says it is. */
    public record Detected(String original, String isoDate, Integer page, String snippet, String origin,
                           DateClassifier.Kind kind, double confidence, String evidence) {

        /** The date as it is served. */
        public Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("text", original);
            row.put("date", isoDate);
            row.put("page", page);
            // The sentence, because a date without it cannot be checked and a wrong date in a calendar is
            // worse than no date.
            row.put("snippet", snippet);
            row.put("origin", origin);
            // What the wording says it is, so that a person can see why one date was picked and another was
            // not, instead of wondering.
            row.put("kind", kind.name());
            row.put("confidence", confidence);
            row.put("evidence", evidence);
            return row;
        }
    }

    /**
     * Dates found in a document, classified, soonest first and without duplicates.
     *
     * <p>Deduplicated by the normalised date: the same deadline written twice on one page is one entry, not
     * two.</p>
     */
    @Transactional(readOnly = true)
    public List<Detected> detect(UUID documentId) {
        Map<String, Detected> byDate = new LinkedHashMap<>();

        // First the structured extractions: already parsed, and they carry their own page and sentence.
        jdbc.query("""
                select field_value, normalized_value, source_page, source_snippet
                  from document_extractions
                 where document_id = ? and value_type = 'DATE' and field_value is not null
                 order by source_page nulls last, field_key
                """, rs -> {
            String iso = rs.getString("normalized_value");
            String original = rs.getString("field_value");
            Integer page = (Integer) rs.getObject("source_page");
            String snippet = rs.getString("source_snippet");
            String key = iso != null ? iso : original;
            if (key != null) {
                DateClassifier.Classified classified = classifyIn(snippet, original);
                byDate.putIfAbsent(key, new Detected(original, iso, page, snippet, "FIELD",
                        classified.kind(), classified.confidence(), classified.evidence()));
            }
        }, documentId);

        // Then the page text: a photographed letter with no configured fields still has a date on it, and
        // that is the case that produced nothing at all before this existed.
        for (Map<String, Object> page : pages(documentId)) {
            String text = (String) page.get("text_content");
            Integer number = (Integer) page.get("page_number");
            if (text == null || text.isBlank()) {
                continue;
            }
            for (int[] span : candidates(text)) {
                String candidate = text.substring(span[0], span[1]);
                FieldValueParser.Parsed parsed = FieldValueParser.parse(candidate, "DATE");
                if (!parsed.valid() || parsed.normalized() == null) {
                    continue;
                }
                // The words beside the date decide what it is, which is the reason a letter with a payment
                // deadline can fill a calendar without anybody pressing anything.
                DateClassifier.Classified classified =
                        DateClassifier.classify(text, span[0], span[1] - span[0]);
                byDate.putIfAbsent(parsed.normalized(), new Detected(candidate, parsed.normalized(), number,
                        snippetAround(text, span[0], span[1]), "PAGE_TEXT",
                        classified.kind(), classified.confidence(), classified.evidence()));
            }
        }

        List<Detected> result = new ArrayList<>(byDate.values());
        result.sort((a, b) -> {
            String left = a.isoDate() == null ? "9999" : a.isoDate();
            String right = b.isoDate() == null ? "9999" : b.isoDate();
            return left.compareTo(right);
        });
        return result;
    }

    /**
     * Puts the classified dates in the calendar, without asking.
     *
     * <p>Called when a document finishes processing. Idempotent: reprocessing is normal — a person resorts a
     * document, or a new rule arrives — and it must not put the same deadline in the calendar twice. Entries
     * carry the sentence and the document, so a wrong one can be traced and removed.</p>
     *
     * @return how many entries were made
     */
    @Transactional
    public int deriveAppointments(UUID tenantId, UUID documentId, UUID actor) {
        List<Detected> found = detect(documentId);
        int created = 0;
        for (Detected date : found) {
            if (date.isoDate() == null) {
                continue;
            }
            DateClassifier.Classified classified =
                    new DateClassifier.Classified(date.kind(), date.confidence(), date.evidence());
            // A weak reading is not acted on: an automatic entry that is wrong is relied upon.
            if (!DateClassifier.createAutomatically(classified)) {
                continue;
            }
            if (alreadyEntered(documentId, date.isoDate())) {
                continue;
            }
            java.time.LocalDate day;
            try {
                day = java.time.LocalDate.parse(date.isoDate());
            }
            catch (java.time.format.DateTimeParseException ex) {
                continue;
            }
            try {
                calendar.create(tenantId,
                        new CalendarEventService.Draft(title(date), day, null,
                                date.kind() == DateClassifier.Kind.APPOINTMENT
                                        ? CalendarEventService.Kind.APPOINTMENT
                                        : CalendarEventService.Kind.REMINDER,
                                null),
                        actor,
                        // The sentence and the document, so that the entry can be checked against the letter
                        // it came from instead of being something the platform asserts.
                        date.snippet(), documentId);
                created++;
            }
            catch (RuntimeException ex) {
                // One bad date must not stop the others; the failure is logged rather than swallowed.
                log.warn("Termin aus Dokument {} am {} konnte nicht angelegt werden: {}",
                        documentId, date.isoDate(), ex.getMessage());
            }
        }
        if (created > 0) {
            log.info("Aus Dokument {} wurden {} Termine abgeleitet", documentId, created);
        }
        return created;
    }

    /**
     * Whether this document already has an entry for that day.
     *
     * <p>Reprocessing a document is normal, and it must not put the same deadline in the calendar twice.</p>
     */
    private boolean alreadyEntered(UUID documentId, String isoDate) {
        Integer count = jdbc.queryForObject("""
                select count(*) from calendar_events
                 where source_document_id = ? and starts_on = ?::date and deleted_at is null
                """, Integer.class, documentId, isoDate);
        return count != null && count > 0;
    }

    /** What the entry is called: the wording that made it useful, in German. */
    private static String title(Detected date) {
        return switch (date.kind()) {
            case PAYMENT_DEADLINE -> "Zahlung fällig: " + date.original();
            case OBJECTION_DEADLINE -> "Frist zur Antwort: " + date.original();
            case APPOINTMENT -> "Termin: " + date.original();
            case VALIDITY_END -> "Gültig bis: " + date.original();
            case DOCUMENT_DATE, OTHER -> "Datum: " + date.original();
        };
    }

    /** Classifies a date inside a stored sentence, or falls back to the sentence itself. */
    private static DateClassifier.Classified classifyIn(String snippet, String original) {
        if (snippet == null || snippet.isBlank()) {
            return new DateClassifier.Classified(DateClassifier.Kind.OTHER, 0, null);
        }
        int at = original == null ? -1 : snippet.indexOf(original);
        if (at < 0) {
            return DateClassifier.classify(snippet, 0, 0);
        }
        return DateClassifier.classify(snippet, at, original.length());
    }

    /** The pages of the document, in order, as far as they exist. */
    private List<Map<String, Object>> pages(UUID documentId) {
        // document_pages is scoped through its document by the policy, so the read runs in the caller's
        // tenant context and the policy is what limits it.
        return jdbc.queryForList("""
                select page_number, text_content
                  from document_pages
                 where document_id = ?
                 order by page_number
                 limit ?
                """, documentId, MAX_PAGES);
    }

    /** Date-shaped spans in the text, with their offsets, without claiming they are all dates. */
    private static List<int[]> candidates(String text) {
        List<int[]> found = new ArrayList<>();
        Matcher numeric = NUMERIC.matcher(text);
        while (numeric.find()) {
            found.add(new int[] {numeric.start(), numeric.end()});
        }
        Matcher iso = ISO.matcher(text);
        while (iso.find()) {
            found.add(new int[] {iso.start(), iso.end()});
        }
        return found;
    }

    /** The sentence around a date, trimmed, so that it can be checked against the letter. */
    private static String snippetAround(String text, int start, int end) {
        int from = Math.max(0, start - SNIPPET_BEFORE);
        int to = Math.min(text.length(), end + SNIPPET_AFTER);
        return text.substring(from, to).replaceAll("\\s+", " ").trim();
    }
}
