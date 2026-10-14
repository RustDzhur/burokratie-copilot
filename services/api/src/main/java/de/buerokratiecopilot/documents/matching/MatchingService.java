package de.buerokratiecopilot.documents.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.documents.DocumentMatchRepository;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.domain.DocumentMatch;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.documents.domain.DocumentVersion;
import de.buerokratiecopilot.shared.settings.SettingsService;

/**
 * Proposes links between a document and the objects it belongs to (spec §9.5).
 *
 * <p>Every proposal names its evidence: which string matched, in which object, and where in the
 * document it was found. The specification requires the user to see the basis of a link, and a score
 * without a reason cannot be judged — a person can disagree with "the reference 2026-114 appears on
 * page 1" but not with "0.95".</p>
 *
 * <p>Nothing is linked silently. A proposal below the automatic threshold waits for a person. Above
 * it, a match to a case is confirmed and the case link is created with {@code link_origin='RULE'},
 * its confidence and the same explanation — so the automatic decision is as auditable as a manual
 * one. Contacts and contracts are never linked automatically: for those the platform has no place to
 * record a link that a user could undo, and creating one without that would be a change nobody can
 * reverse.</p>
 */
@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);

    /** Below this a candidate is not even worth showing. */
    private static final double FLOOR = 0.60;

    /** Default threshold above which a case link is created automatically. */
    private static final double DEFAULT_AUTO_LINK_THRESHOLD = 0.95;

    private final DocumentMatchRepository matches;
    private final JdbcTemplate jdbc;
    private final SettingsService settings;

    public MatchingService(DocumentMatchRepository matches, JdbcTemplate jdbc, SettingsService settings) {
        this.matches = matches;
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** Outcome of the matching stage. */
    public record Outcome(List<Proposal> proposals, int autoLinked, double threshold) {

        public Map<String, Object> diagnostics() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("proposals", proposals.size());
            details.put("autoLinked", autoLinked);
            details.put("autoLinkThreshold", threshold);
            details.put("types", String.join(", ",
                    proposals.stream().map(proposal -> proposal.type().name()).distinct().toList()));
            details.put("best", proposals.isEmpty() ? "" : proposals.getFirst().rationale());
            return details;
        }
    }

    /** One candidate, with the score that was reached and why. */
    public record Proposal(DocumentMatch.MatchType type, UUID targetId, double score, String rationale,
                           Map<String, Object> evidence) {
    }

    /** A candidate string found in the document. */
    private record Needle(String value, String kind, Integer page, String snippet) {
    }

    /** One page of the document as stored, so a match can be attributed to the right page. */
    private record PageText(int number, String text) {
    }

    @Transactional
    public Outcome match(Document document, DocumentVersion version, UUID senderContactId) {
        double threshold = settings.getDouble("matching.auto_link_threshold", DEFAULT_AUTO_LINK_THRESHOLD);
        List<PageText> pages = collectPages(document, version);

        List<Proposal> proposals = new ArrayList<>();
        proposals.addAll(matchCases(document, pages));
        proposals.addAll(matchContracts(document, pages));
        proposals.addAll(matchContacts(document, pages, senderContactId));
        proposals.addAll(matchContactsByIdentifier(document, pages));
        proposals = keepBestPerTarget(proposals);

        // The unique index keeps one row per document and target, so re-running the stage after a fix
        // updates the score instead of asking the user to decide about the same pair twice.
        List<Proposal> kept = proposals.stream().filter(proposal -> proposal.score() >= FLOOR).toList();
        int autoLinked = 0;

        for (Proposal proposal : kept) {
            DocumentMatch entity = matches
                    .findByDocumentIdAndMatchTypeAndTargetId(document.getId(), proposal.type(),
                            proposal.targetId())
                    .orElseGet(() -> new DocumentMatch(UUID.randomUUID(), document.getTenantId(),
                            document.getId(), version.getId(), proposal.type(), proposal.targetId(),
                            BigDecimal.valueOf(proposal.score())));

            entity.setScore(BigDecimal.valueOf(proposal.score()).setScale(4, RoundingMode.HALF_UP));
            entity.setRationale(Map.of("de", proposal.rationale(), "en", proposal.rationale()));
            entity.setEvidence(proposal.evidence());

            if (entity.isSuggested() && proposal.score() >= threshold
                    && proposal.type() == DocumentMatch.MatchType.CASE) {
                entity.confirmByRule(entity.getScore(), Map.of("de", proposal.rationale()));
                matches.saveAndFlush(entity);
                autoLinked += linkCase(document, proposal) ? 1 : 0;
                continue;
            }
            // Flushing here surfaces a database error at the statement that caused it, instead of at
            // commit time where it would be attributed to whatever ran last.
            matches.saveAndFlush(entity);
        }

        log.info("Matching produced {} proposal(s) for document {}, {} linked automatically",
                kept.size(), document.getId(), autoLinked);
        return new Outcome(kept, autoLinked, threshold);
    }

    /**
     * The pages of the document, in order, plus the file name as a final pseudo page.
     *
     * <p>Matches are looked for page by page rather than in one concatenated blob: a value must be
     * attributed to the page a reader would find it on, and concatenating pages makes that
     * impossible to recover afterwards.</p>
     */
    private List<PageText> collectPages(Document document, DocumentVersion version) {
        List<PageText> pages = new ArrayList<>();
        jdbc.query("select page_number, text_content from document_pages where version_id = ? order by page_number",
                rs -> {
                    pages.add(new PageText(rs.getInt("page_number"), rs.getString("text_content")));
                }, version.getId());
        if (document.getOriginalFilename() != null) {
            // Page number 0 means "from the file name": a reference in the name is real evidence, but
            // claiming it was on page 1 would be wrong.
            pages.add(new PageText(0, document.getOriginalFilename()));
        }
        return pages;
    }

    /** Cases are matched on their own reference, which is the most reliable identifier there is. */
    private List<Proposal> matchCases(Document document, List<PageText> pages) {
        List<Proposal> proposals = new ArrayList<>();
        jdbc.query("""
                select id, reference, external_reference, title
                from cases
                where tenant_id = ? and deleted_at is null
                """, rs -> {
            UUID caseId = rs.getObject("id", UUID.class);
            String reference = rs.getString("reference");
            String external = rs.getString("external_reference");
            String title = rs.getString("title");

            Optional<Needle> hit = findNeedleIn(pages, reference, "Aktenzeichen");
            String kind = "Aktenzeichen des Vorgangs";
            if (hit.isEmpty() && external != null && !external.isBlank()) {
                hit = findNeedleIn(pages, external, "Referenz");
                kind = "Externe Referenz des Vorgangs";
            }
            if (hit.isPresent()) {
                // An exact reference is about as certain as evidence gets here.
                Needle needle = hit.get();
                proposals.add(new Proposal(DocumentMatch.MatchType.CASE, caseId, 0.95,
                        "Der Vorgang wird über seine Referenz \"%s\" erkannt, die auf Seite %d im Dokument steht"
                                .formatted(needle.value(), needle.page()),
                        evidence(kind, needle, title)));
            }
        }, document.getTenantId());
        return proposals;
    }

    /** Contracts are matched on their contract number. */
    private List<Proposal> matchContracts(Document document, List<PageText> pages) {
        List<Proposal> proposals = new ArrayList<>();
        jdbc.query("""
                select id, contract_number, title
                from contracts
                where tenant_id = ? and deleted_at is null
                """, rs -> {
            UUID contractId = rs.getObject("id", UUID.class);
            String number = rs.getString("contract_number");
            Optional<Needle> hit = findNeedleIn(pages, number, "Vertragsnummer");
            if (hit.isPresent()) {
                Needle needle = hit.get();
                proposals.add(new Proposal(DocumentMatch.MatchType.CONTRACT, contractId, 0.95,
                        "Der Vertrag wird über seine Nummer \"%s\" erkannt, die auf Seite %d im Dokument steht"
                                .formatted(needle.value(), needle.page()),
                        evidence("Vertragsnummer", needle, rs.getString("title"))));
            }
        }, document.getTenantId());
        return proposals;
    }

    /**
     * Contacts are matched on identifiers, never on a name alone.
     *
     * <p>A company name is not unique enough to link an administrative document to a person: two
     * different contacts may share it, and a wrong link attaches correspondence to the wrong party.
     * An e-mail address or a VAT id is specific.</p>
     */
    private List<Proposal> matchContacts(Document document, List<PageText> pages, UUID senderContactId) {
        List<Proposal> proposals = new ArrayList<>();
        jdbc.query("""
                select id, display_name, organization_name, email, vat_id, tax_number
                from contacts
                where tenant_id = ? and deleted_at is null
                """, rs -> {
            UUID contactId = rs.getObject("id", UUID.class);
            String name = Optional.ofNullable(rs.getString("organization_name"))
                    .filter(value -> !value.isBlank())
                    .orElse(rs.getString("display_name"));

            Optional<Needle> email = findNeedleIn(pages, rs.getString("email"), "E-Mail");
            if (email.isPresent()) {
                Needle needle = email.get();
                proposals.add(new Proposal(DocumentMatch.MatchType.CONTACT, contactId, 0.90,
                        "Die E-Mail-Adresse \"%s\" des Kontakts steht auf Seite %d im Dokument"
                                .formatted(needle.value(), needle.page()),
                        evidence("E-Mail-Adresse", needle, name)));
                return;
            }
            Optional<Needle> vat = findNeedleIn(pages, rs.getString("vat_id"), "USt-IdNr");
            if (vat.isEmpty()) {
                vat = findNeedleIn(pages, rs.getString("tax_number"), "Steuernummer");
            }
            if (vat.isPresent()) {
                Needle needle = vat.get();
                proposals.add(new Proposal(DocumentMatch.MatchType.CONTACT, contactId, 0.90,
                        "Die Steuernummer \"%s\" des Kontakts steht auf Seite %d im Dokument"
                                .formatted(needle.value(), needle.page()),
                        evidence("Steuernummer", needle, name)));
                return;
            }
            // The sender of the mail the document arrived with is weak evidence on its own, but it is
            // still worth proposing - as a proposal, never as a link.
            if (senderContactId != null && senderContactId.equals(contactId)) {
                proposals.add(new Proposal(DocumentMatch.MatchType.CONTACT, contactId, 0.65,
                        "Das Dokument kam per E-Mail von diesem Kontakt",
                        Map.of("kind", "Absender der E-Mail", "contact", name == null ? "" : name)));
            }
        }, document.getTenantId());
        return proposals;
    }

    /**
     * Contacts found by the identifiers written in the letter: IBAN (check digits verified), USt-IdNr., Steuernummer,
     * e-mail, in any spelling (blocks of four, dots, slashes). The identifiers of the contacts are compared after
     * normalisation; a name never counts.
     */
    private List<Proposal> matchContactsByIdentifier(Document document, List<PageText> pages) {
        List<Proposal> proposals = new ArrayList<>();
        for (PageText page : pages) {
            for (var found : de.buerokratiecopilot.contacts.identifiers.IdentifierExtractor.extract(page.text())) {
                List<Map<String, Object>> owners = jdbc.queryForList("""
                        select c.id, coalesce(nullif(c.organization_name, ''), c.display_name) as name
                        from contact_identifiers i join contacts c on c.id = i.contact_id
                        where i.tenant_id = ? and i.kind = ? and i.normalized_value = ? and c.deleted_at is null
                        """, document.getTenantId(), found.kind().name(), found.normalized());
                for (Map<String, Object> owner : owners) {
                    String label = switch (found.kind()) {
                        case IBAN -> "IBAN";
                        case VAT_ID -> "USt-IdNr.";
                        case TAX_NUMBER -> "Steuernummer";
                        case EMAIL -> "E-Mail-Adresse";
                        default -> found.kind().name();
                    };
                    double score = switch (found.kind()) {
                        case IBAN -> 0.95;
                        case VAT_ID -> 0.92;
                        default -> 0.90;
                    };
                    Needle needle = new Needle(found.raw(), label, page.number(), snippetAt(page.text(), found.position()));
                    proposals.add(new Proposal(DocumentMatch.MatchType.CONTACT, (UUID) owner.get("id"), score,
                            "%s \"%s\" des Kontakts steht auf Seite %d im Dokument".formatted(label, found.raw(), page.number()),
                            evidence(label, needle, String.valueOf(owner.get("name")))));
                }
            }
        }
        return proposals;
    }

    /** One proposal per target: the strongest. */
    private static List<Proposal> keepBestPerTarget(List<Proposal> proposals) {
        Map<String, Proposal> best = new java.util.LinkedHashMap<>();
        for (Proposal proposal : proposals) {
            best.merge(proposal.type() + ":" + proposal.targetId(), proposal,
                    (a, b) -> b.score() > a.score() ? b : a);
        }
        return new ArrayList<>(best.values());
    }

    /**
     * Finds a needle on any page, as a whole token.
     *
     * <p>Requiring token boundaries matters: the reference {@code 2026-1} must not match inside
     * {@code 2026-114}, which would link a document to the wrong case.</p>
     */
    private Optional<Needle> findNeedleIn(List<PageText> pages, String needle, String kind) {
        if (needle == null || needle.strip().length() < 3) {
            return Optional.empty();
        }
        String target = needle.strip().toLowerCase(Locale.GERMAN);

        for (PageText page : pages) {
            String text = page.text();
            if (text == null || text.isBlank()) {
                continue;
            }
            String lowered = text.toLowerCase(Locale.GERMAN);
            int from = 0;
            while (true) {
                int index = lowered.indexOf(target, from);
                if (index < 0) {
                    break;
                }
                int after = index + target.length();
                boolean before = index == 0 || !Character.isLetterOrDigit(lowered.charAt(index - 1));
                boolean afterOk = after >= lowered.length()
                        || !Character.isLetterOrDigit(lowered.charAt(after));
                if (before && afterOk) {
                    return Optional.of(new Needle(needle.strip(), kind, page.number(),
                            snippetAt(text, index)));
                }
                from = index + 1;
            }
        }
        return Optional.empty();
    }

    private static String snippetAt(String text, int index) {
        int start = Math.max(0, text.lastIndexOf('\n', index) + 1);
        int end = text.indexOf('\n', index);
        if (end < 0) {
            end = text.length();
        }
        String line = text.substring(start, end).strip();
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }

    private static Map<String, Object> evidence(String kind, Needle needle, String targetName) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", kind);
        details.put("matchedText", needle.value());
        // Page 0 means the file name rather than a page of the document.
        details.put("page", needle.page() == 0 ? "Dateiname" : needle.page());
        details.put("snippet", needle.snippet());
        details.put("target", targetName == null ? "" : targetName);
        return details;
    }

    /**
     * Creates the case link for a confirmed automatic match.
     *
     * <p>The explanation is written into the link so the automatic decision is visible exactly where a
     * person sees the link — not only in a log nobody reads.</p>
     */
    private boolean linkCase(Document document, Proposal proposal) {
        Integer existing = jdbc.queryForObject("""
                select count(*) from case_documents where case_id = ? and document_id = ?
                """, Integer.class, proposal.targetId(), document.getId());
        if (existing != null && existing > 0) {
            return false;
        }
        jdbc.update("""
                insert into case_documents (id, tenant_id, case_id, document_id, link_origin, confidence,
                                            note, confirmed_at)
                values (gen_random_uuid(), ?, ?, ?, 'RULE', ?, ?, now())
                """, document.getTenantId(), proposal.targetId(), document.getId(),
                BigDecimal.valueOf(proposal.score()).setScale(4, RoundingMode.HALF_UP),
                proposal.rationale());
        return true;
    }

    /** Files a person's decision about a proposal (spec §9.5). */
    @Transactional
    public DocumentMatch decide(UUID matchId, DocumentMatch.Status decision, UUID userId) {
        // The project's own not-found answer. An IllegalArgumentException for a missing row is
        // indistinguishable from a programming error and reached the caller as a 500, which tells somebody
        // that the platform is broken rather than that the identifier they used does not exist.
        DocumentMatch match = matches.findById(matchId).orElseThrow(
                () -> ApiException.notFound("Zuordnung " + matchId));
        match.decide(decision, userId);
        if (decision == DocumentMatch.Status.CONFIRMED
                && match.getMatchType() == DocumentMatch.MatchType.CASE) {
            linkCaseFor(match, userId);
        }
        return matches.save(match);
    }

    private void linkCaseFor(DocumentMatch match, UUID userId) {
        Integer existing = jdbc.queryForObject("""
                select count(*) from case_documents where case_id = ? and document_id = ?
                """, Integer.class, match.getTargetId(), match.getDocumentId());
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update("""
                insert into case_documents (id, tenant_id, case_id, document_id, link_origin, confidence,
                                            note, linked_by, confirmed_at)
                values (gen_random_uuid(), ?, ?, ?, 'MANUAL', ?, ?, ?, now())
                """, match.getTenantId(), match.getTargetId(), match.getDocumentId(), match.getScore(),
                String.valueOf(match.getRationale().getOrDefault("de", "")), userId);
    }

    @Transactional(readOnly = true)
    public List<DocumentMatch> proposalsOf(UUID documentId) {
        return matches.findByDocumentIdOrderByScoreDesc(documentId);
    }

    @Transactional(readOnly = true)
    public List<DocumentMatch> pending() {
        return matches.findByStatusOrderByScoreDesc(DocumentMatch.Status.SUGGESTED);
    }
}
