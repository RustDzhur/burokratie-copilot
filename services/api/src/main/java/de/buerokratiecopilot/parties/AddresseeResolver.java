package de.buerokratiecopilot.parties;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Finds out whom a letter is addressed to, so that everything the platform does with it is for the right person.
 *
 * <p>A letter names its addressee at the top (the address window) and often again further down. A name in the
 * first lines counts for much more than the same name in the body, a birth date or an identifier of the party
 * adds to it, and the party with the clearest lead wins. When two parties are equally likely nobody is chosen: a
 * wrong addressee would send a child's school letter to a parent's tax case, an unknown one only asks a person.
 * The one exception is a cabinet with a single party, to whom every letter is addressed.</p>
 *
 * <p>A choice a person made is never overwritten.</p>
 */
@Component
public class AddresseeResolver {

    private static final Logger log = LoggerFactory.getLogger(AddresseeResolver.class);
    private static final int HEADER_CHARS = 700;
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final JdbcTemplate jdbc;

    public AddresseeResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What the matcher knows about one party. */
    public record Candidate(UUID id, String name, List<String> aliases, LocalDate birthDate, List<String> identifiers,
                            String address) {
    }

    /** Sets the addressee of one document when it has none and the text says clearly who it is. */
    public void resolve(UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select tenant_id, mandant_id, coalesce(extracted_text, '') as text, original_filename
                from documents where id = ? and deleted_at is null
                """, documentId);
        // §5.2: a document that already has a client keeps it. The platform does not overwrite a decision, and
        // an automatic match is not a decision until a person has seen it.
        if (rows.isEmpty() || rows.get(0).get("mandant_id") != null) {
            return;
        }
        UUID tenantId = (UUID) rows.get(0).get("tenant_id");
        List<Candidate> candidates = candidates(tenantId);
        UUID chosen = choose(candidates, String.valueOf(rows.get(0).get("text")));
        if (chosen != null) {
            // The candidate is already a contact, so the answer goes straight into the field the screen shows
            // and the export checks. Writing anything else here would be the silent divergence §5.2 forbids.
            jdbc.update("update documents set mandant_id = ? where id = ? and mandant_id is null",
                    chosen, documentId);
        }
    }

    /** After a party was added or changed: documents that had no addressee get another look. */
    public void resolveUnassigned(UUID tenantId) {
        List<Candidate> candidates = candidates(tenantId);
        if (candidates.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, coalesce(extracted_text, '') as text from documents
                where tenant_id = ? and mandant_id is null and deleted_at is null
                order by received_at desc limit 500
                """, tenantId);
        int set = 0;
        for (Map<String, Object> row : rows) {
            UUID chosen = choose(candidates, String.valueOf(row.get("text")));
            if (chosen != null) {
                set += jdbc.update("update documents set mandant_id = ? where id = ? and mandant_id is null", chosen,
                        row.get("id"));
            }
        }
        if (set > 0) {
            log.info("Addressee found for {} earlier documents of tenant {}", set, tenantId);
        }
    }

    private List<Candidate> candidates(UUID tenantId) {
        // §4.3: the client of a voucher is a contact. The matcher used to read parties, which no longer exists
        // once the module is deleted, so it reads the table the office actually maintains.
        return jdbc.query("""
                select id, display_name, null::text as aliases, null::date as birth_date,
                       null::text as identifiers,
                       concat_ws(' ', nullif(organization_name, ''), nullif(first_name, ''),
                                 nullif(last_name, '')) as address
                  from contacts
                 where tenant_id = ? and deleted_at is null
                """, (rs, i) -> new Candidate((UUID) rs.getObject(1), rs.getString(2), lines(rs.getString(3)),
                rs.getObject(4, LocalDate.class), lines(rs.getString(5)), rs.getString(6)), tenantId);
    }

    private static List<String> lines(String value) {
        List<String> out = new ArrayList<>();
        if (value != null) {
            for (String part : value.split("[\\n;,]")) {
                if (!part.isBlank()) {
                    out.add(part.strip());
                }
            }
        }
        return out;
    }

    /** The addressee, or null when the text does not say clearly. Public for the tests. */
    public static UUID choose(List<Candidate> candidates, String text) {
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return candidates.get(0).id();
        }
        String normalized = normalize(text);
        String header = normalized.substring(0, Math.min(normalized.length(), HEADER_CHARS));
        UUID best = null;
        int bestScore = 0;
        int second = 0;
        for (Candidate candidate : candidates) {
            int score = score(candidate, normalized, header, text);
            if (score > bestScore) {
                second = bestScore;
                bestScore = score;
                best = candidate.id();
            }
            else if (score > second) {
                second = score;
            }
        }
        return bestScore > 0 && bestScore > second ? best : null;
    }

    private static int score(Candidate candidate, String body, String header, String rawText) {
        int score = 0;
        List<String> names = new ArrayList<>();
        names.add(candidate.name());
        names.addAll(candidate.aliases());
        for (String name : names) {
            String n = normalize(name);
            if (n.isBlank()) {
                continue;
            }
            if (mentions(header, n)) {
                score += 6;
            }
            else if (mentions(body, n)) {
                score += 2;
            }
        }
        if (candidate.birthDate() != null && rawText.contains(candidate.birthDate().format(GERMAN_DATE))) {
            score += 3;
        }
        for (String identifier : candidate.identifiers()) {
            String id = normalize(identifier).replace(" ", "");
            if (id.length() >= 5 && body.replace(" ", "").contains(id)) {
                score += 5;
            }
        }
        return score;
    }

    /** The full name, the name the other way round ("Mustermann, Max"), or first and last name both present. */
    private static boolean mentions(String haystack, String name) {
        if (haystack.contains(name)) {
            return true;
        }
        String[] tokens = name.split(" ");
        if (tokens.length < 2) {
            return false;
        }
        String first = tokens[0];
        String last = tokens[tokens.length - 1];
        if (haystack.contains(last + " " + first)) {
            return true;
        }
        // first and last name near each other (a middle name or a title in between)
        int at = haystack.indexOf(first);
        while (at >= 0) {
            int from = Math.max(0, at - 40);
            int to = Math.min(haystack.length(), at + first.length() + 40);
            if (haystack.substring(from, to).contains(last)) {
                return true;
            }
            at = haystack.indexOf(first, at + 1);
        }
        return false;
    }

    /** Lower case, umlauts folded the German way (ä = ae), punctuation turned into single spaces. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("ß", "ss");
        String plain = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
