package de.buerokratiecopilot.documents.zuordnung;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.contacts.identifiers.IdentifierExtractor;
import de.buerokratiecopilot.contacts.identifiers.IdentifierKind;
import de.buerokratiecopilot.contacts.identifiers.IdentifierNormalizer;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.zordnung.MandantRouting;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Decides whose bookkeeping a voucher belongs to, and learns from the corrections a person makes (§5.2).
 *
 * <p><strong>The rule this class exists for.</strong> A document is assigned automatically only above the
 * threshold of {@link MandantRouting}, and never when two clients match equally well. Below that line the
 * voucher stays "Ohne Mandant" - visible in the Posteingang, refused by the export (§11.7), and waiting for a
 * person. The alternative is one client's paperwork in another client's books, undiscovered until an audit.</p>
 *
 * <p><strong>What counts as evidence.</strong> A signal is only read where the platform already has it: the
 * sender's e-mail from {@code documents.sender_details} or the mail header, IBAN/VAT id/tax number from the
 * sender details and the extracted text (checked with the existing {@link IdentifierExtractor}), the company
 * name from the sender and the "Lieferant" field, and remembered keywords from this table. Names are compared
 * after German normalisation (ä = ae, punctuation as a separator), never by fuzzy similarity: "Müller GmbH" and
 * "Müller AG" are not the same company.</p>
 *
 * <p><strong>Confidence.</strong> A single signal carries the weight of its type (IBAN/VAT id 0.95, e-mail 0.90,
 * company name 0.80, keyword 0.60). Several signals of one candidate are combined as independent evidence
 * ({@code 1 - Π(1 - weight)}) and capped at 0.99: more evidence makes an assignment more likely, never certain.
 * A learned rule always counts 0.95, which is the number §5.2 names for "the next similar document".</p>
 *
 * <p><strong>Learning.</strong> Every manual assignment writes the sender's e-mail, the supplier's name and an
 * IBAN - where present - as a rule with {@code quelle = 'MANUELL'}. The next document carrying the same signal is
 * assigned automatically with 0.95. Automatic assignments record their strong, non-learned signals as
 * {@code quelle = 'AUTO'} rules, but such a rule never replaces a learned one and never moves a client; only a
 * person may re-point a rule.</p>
 *
 * <p><strong>Never overwrite.</strong> A document that already has a client is left alone. The manual path is
 * the only way to change an assignment, and that path is audited.</p>
 */
@Service
public class MandantRoutingService {

    private static final Logger log = LoggerFactory.getLogger(MandantRoutingService.class);

    /** A learned rule counts this much, exactly the number §5.2 gives for the next similar document. */
    static final double LEARNED_RULE_CONFIDENCE = 0.95;

    /** More evidence may say 99 %, never 100 %: a 100 % claim cannot be reviewed. */
    static final double MAX_CONFIDENCE = 0.99;

    /** Signals below this are not turned into an automatic rule; they are too weak to stand on their own. */
    private static final double AUTO_RULE_MIN_STRENGTH = 0.90;

    private static final int MAX_MUSTER_LENGTH = 500;

    /** The audit action of a deleted routing rule (§21.4). */
    public static final String AUDIT_DELETE_RULE = "MANDANT_ROUTING_RULE_DELETED";

    private final JdbcTemplate jdbc;
    private final DocumentService documents;
    private final AuditService auditService;

    public MandantRoutingService(JdbcTemplate jdbc, DocumentService documents, AuditService auditService) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ result types

    /** What a routing run found, in the words the API and the screen use. */
    public enum Status {
        /** The platform assigned a client because the evidence was strong enough and unambiguous. */
        ASSIGNED,
        /** The document already had a client; nothing was changed. */
        ALREADY_ASSIGNED,
        /** Two clients matched about equally well; a person has to decide. */
        AMBIGUOUS,
        /** One client matched, but not strongly enough for an automatic assignment. */
        BELOW_THRESHOLD,
        /** The document carries none of the signals the platform can read. */
        NO_SIGNALS
    }

    /** One signal as the result shows it: what kind, what it said, how much it counted. */
    public record SignalView(String type, String value, double strength) {

        Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("value", value);
            row.put("strength", strength);
            return row;
        }
    }

    /** One client that matched, with the confidence reached after combining its signals. */
    public record CandidateView(String mandantId, String mandantName, double confidence) {

        Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mandantId", mandantId);
            row.put("mandantName", mandantName);
            row.put("confidence", confidence);
            return row;
        }
    }

    /** The outcome of {@link #route(UUID)}, shaped for the API. */
    public record RoutingResult(Status status, UUID documentId, UUID mandantId, String mandantName,
                                Double confidence, List<SignalView> signals, List<CandidateView> candidates) {

        public Map<String, Object> asMap() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("documentId", documentId.toString());
            body.put("status", status.name());
            body.put("mandantId", mandantId == null ? null : mandantId.toString());
            body.put("mandantName", mandantName);
            body.put("confidence", confidence);
            body.put("signals", signals.stream().map(SignalView::asMap).toList());
            body.put("candidates", candidates.stream().map(CandidateView::asMap).toList());
            return body;
        }
    }

    /** A remembered rule as the settings screen lists it. */
    public record Rule(UUID id, MandantSignalType signalTyp, String muster, UUID mandantId, String mandantName,
                       String quelle, int treffer, Instant letzteNutzung, Instant createdAt) {

        public Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id.toString());
            row.put("signalTyp", signalTyp.name());
            row.put("muster", muster);
            row.put("mandantId", mandantId == null ? null : mandantId.toString());
            row.put("mandantName", mandantName);
            row.put("quelle", quelle);
            row.put("treffer", treffer);
            // Spring writes only values that exist, so a never-used rule arrives without the field; the
            // screen treats absent as "never".
            if (letzteNutzung != null) {
                row.put("letzteNutzung", letzteNutzung.toString());
            }
            row.put("createdAt", createdAt == null ? null : createdAt.toString());
            return row;
        }

        private String label() {
            return signalTyp.name() + " " + muster;
        }
    }

    // ------------------------------------------------------------------ internal model

    /** Everything a routing decision may look at, read once. */
    private record DocumentContext(UUID id, UUID tenantId, UUID mandantId, String sender, String supplier,
                                   String text, String senderEmail, String senderIban, String senderVat) {

        String normalizedText() {
            return normalizeName(text);
        }

        /** The names the document carries about its sender, readable form first. */
        List<String> names() {
            List<String> names = new ArrayList<>();
            if (sender != null && !sender.isBlank()) {
                names.add(sender.strip());
            }
            if (supplier != null && !supplier.isBlank()) {
                names.add(supplier.strip());
            }
            return names;
        }
    }

    /** The fields of one contact that can carry a signal. */
    private record ContactRow(UUID id, String name, List<String> names, String email, String vatId, String taxNumber,
                              Set<String> emails, Set<String> ibans, Set<String> vats, Set<String> taxes) {
    }

    /** One piece of evidence, with the readable value kept for the screen. */
    private record Signal(MandantSignalType type, String muster, String value, double strength, boolean learned) {
    }

    /** A learned rule that matched this document. Kept so the rule's counter can be increased in the same run. */
    private record RuleRow(UUID id, MandantSignalType type, String muster, UUID mandantId, String mandantName) {
    }

    /** One candidate while its evidence is being collected. */
    private static final class Candidate {

        private final UUID mandantId;
        private final String mandantName;
        private final List<Signal> signals = new ArrayList<>();
        private final List<RuleRow> rules = new ArrayList<>();
        private final Map<String, Signal> strongest = new LinkedHashMap<>();

        Candidate(UUID mandantId, String mandantName) {
            this.mandantId = mandantId;
            this.mandantName = mandantName;
        }

        void add(Signal signal) {
            String key = signal.type() + "|" + signal.muster();
            Signal previous = strongest.get(key);
            if (previous == null || signal.strength() >= previous.strength()) {
                strongest.put(key, signal);
            }
        }

        void addRule(RuleRow rule) {
            rules.add(rule);
        }

        UUID mandantId() {
            return mandantId;
        }

        String mandantName() {
            return mandantName;
        }

        List<Signal> signals() {
            return List.copyOf(strongest.values());
        }

        List<RuleRow> rules() {
            return List.copyOf(rules);
        }

        double confidence() {
            double miss = 1.0;
            for (Signal signal : strongest.values()) {
                miss *= (1.0 - signal.strength());
            }
            double confidence = 1.0 - miss;
            if (confidence > MAX_CONFIDENCE) {
                confidence = MAX_CONFIDENCE;
            }
            return Math.round(confidence * 10_000.0) / 10_000.0;
        }

        List<SignalView> view() {
            return strongest.values().stream()
                    .sorted(Comparator.comparingDouble(Signal::strength).reversed())
                    .map(signal -> new SignalView(signal.type().name(), signal.value(), signal.strength()))
                    .toList();
        }
    }

    // ------------------------------------------------------------------ routing

    /**
     * Assigns the client of one document when the signals allow it, and otherwise says why not.
     *
     * <p>Called by the processing handler for every finished document and by the "Mandant neu bestimmen" button
     * on the document card. The two callers share this method on purpose: a button that computes a different
     * answer than the automatic path is worse than no button.</p>
     */
    @Transactional
    public RoutingResult route(UUID documentId) {
        UUID tenantId = tenant();
        DocumentContext document = loadDocument(documentId, tenantId);
        if (document.mandantId() != null) {
            String name = mandantName(document.tenantId(), document.mandantId());
            return new RoutingResult(Status.ALREADY_ASSIGNED, document.id(), document.mandantId(), name, null,
                    List.of(), List.of());
        }

        List<Signal> signals = extractSignals(document);
        List<RuleRow> rules = loadRules(tenantId);
        List<Candidate> candidates = candidates(document, signals, rules);

        if (candidates.isEmpty()) {
            updateAssignment(document.id(), tenantId, null, null);
            log.debug("No client signal on document {}", documentId);
            return new RoutingResult(Status.NO_SIGNALS, document.id(), null, null, null,
                    signalViews(signals), List.of());
        }

        candidates.sort(Comparator.comparingDouble(Candidate::confidence).reversed()
                .thenComparing(candidate -> candidate.mandantName() == null ? "" : candidate.mandantName()));

        Candidate best = candidates.getFirst();
        Candidate second = candidates.size() > 1 ? candidates.get(1) : null;
        // A single candidate has no rival to be ambiguous with. Calling isAmbiguous with a missing second would
        // always say "ask a person" and would turn every clear assignment into a question mark.
        if (second != null && MandantRouting.isAmbiguous(best.confidence(), second.confidence())) {
            updateAssignment(document.id(), tenantId, null, "UNKLAR");
            log.info("Client of document {} is ambiguous: {} against {}", documentId, best.confidence(),
                    second.confidence());
            return new RoutingResult(Status.AMBIGUOUS, document.id(), null, null, null,
                    candidateSignalViews(candidates), candidateViews(candidates));
        }

        Optional<UUID> chosen = MandantRouting.autoAssign(best.mandantId(), best.confidence());
        if (chosen.isEmpty()) {
            updateAssignment(document.id(), tenantId, null, "UNKLAR");
            log.debug("Client of document {} stays open: confidence {} is below the threshold", documentId,
                    best.confidence());
            return new RoutingResult(Status.BELOW_THRESHOLD, document.id(), null, null, null,
                    candidateSignalViews(candidates), candidateViews(candidates));
        }

        // The assignment goes through the same path as the manual endpoint so that it lands in the audit trail
        // with who, when and which client - an automatic decision must be as reviewable as a person's.
        documents.assignMandant(document.id(), chosen.get(), origin(best));
        updateAssignment(document.id(), tenantId, best.confidence(), "AUTO");
        increaseRuleCounters(tenantId, best.rules());
        rememberAutomaticRules(tenantId, best);
        log.info("Document {} assigned to client {} with confidence {}", documentId, chosen.get(),
                best.confidence());

        return new RoutingResult(Status.ASSIGNED, document.id(), chosen.get(), best.mandantName(),
                best.confidence(), candidateSignalViews(candidates), candidateViews(candidates));
    }

    /**
     * Assigns a client by hand and learns the signals that led to it (§5.2).
     *
     * <p>The assignment itself is {@link DocumentService#assignMandant}, so the audit entry and the
     * "was → became" wording stay in one place. What this service adds is the knowledge: the sender's e-mail,
     * the supplier's name and an IBAN become rules with {@code quelle = 'MANUELL'}, and the next voucher with
     * the same signal is assigned automatically.</p>
     */
    @Transactional
    public Document assignManually(UUID documentId, UUID mandantId) {
        UUID tenantId = tenant();
        Document document = documents.require(documentId);
        if (mandantId != null) {
            Integer exists = jdbc.queryForObject("""
                    select count(*) from contacts
                     where id = ? and tenant_id = ? and deleted_at is null
                    """, Integer.class, mandantId, tenantId);
            if (exists == null || exists == 0) {
                // The foreign key alone would accept a contact of another account: PostgreSQL checks the target
                // table without applying its policy. The second guard is this query.
                throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Mandant.");
            }
        }

        Document saved = documents.assignMandant(documentId, mandantId);
        if (mandantId == null) {
            updateAssignment(documentId, tenantId, null, null);
            return saved;
        }
        updateAssignment(documentId, tenantId, null, "MANUELL");
        learn(documentId, tenantId, mandantId);
        return saved;
    }

    // ------------------------------------------------------------------ rules

    /** Every remembered rule of this account, newest first. */
    @Transactional(readOnly = true)
    public List<Rule> listRules() {
        UUID tenantId = tenant();
        return jdbc.query("""
                select r.id, r.signal_typ, r.muster, r.mandant_id, r.quelle, r.treffer,
                       r.letzte_nutzung, r.created_at,
                       coalesce(nullif(c.organization_name, ''), c.display_name) as mandant_name
                  from mandant_routing_rules r
                  join contacts c on c.id = r.mandant_id and c.tenant_id = r.tenant_id
                 where r.tenant_id = ?
                 order by r.created_at desc, r.id
                """, (rs, row) -> new Rule((UUID) rs.getObject("id"),
                MandantSignalType.valueOf(rs.getString("signal_typ")), rs.getString("muster"),
                (UUID) rs.getObject("mandant_id"), rs.getString("mandant_name"), rs.getString("quelle"),
                rs.getInt("treffer"),
                rs.getTimestamp("letzte_nutzung") == null ? null : rs.getTimestamp("letzte_nutzung").toInstant(),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()),
                tenantId);
    }

    /** Deletes one rule. The answer does not reveal whether a foreign rule exists. */
    @Transactional
    public void deleteRule(UUID ruleId) {
        UUID tenantId = tenant();
        List<Rule> found = jdbc.query("""
                select r.id, r.signal_typ, r.muster, r.mandant_id, r.quelle, r.treffer,
                       r.letzte_nutzung, r.created_at,
                       coalesce(nullif(c.organization_name, ''), c.display_name) as mandant_name
                  from mandant_routing_rules r
                  join contacts c on c.id = r.mandant_id and c.tenant_id = r.tenant_id
                 where r.id = ? and r.tenant_id = ?
                """, (rs, row) -> new Rule((UUID) rs.getObject("id"),
                MandantSignalType.valueOf(rs.getString("signal_typ")), rs.getString("muster"),
                (UUID) rs.getObject("mandant_id"), rs.getString("mandant_name"), rs.getString("quelle"),
                rs.getInt("treffer"),
                rs.getTimestamp("letzte_nutzung") == null ? null : rs.getTimestamp("letzte_nutzung").toInstant(),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()),
                ruleId, tenantId);
        Rule rule = found.isEmpty() ? null : found.getFirst();
        if (rule == null) {
            throw ApiException.notFound("Mandant rule");
        }
        jdbc.update("delete from mandant_routing_rules where id = ? and tenant_id = ?", ruleId, tenantId);
        auditService.record(AuditService.AuditEntry
                .builder(AuditEvent.Category.SETTINGS, AUDIT_DELETE_RULE)
                .tenant(tenantId)
                .entity("mandant_routing_rules", ruleId, rule.muster())
                .description("Mandant-Regel gelöscht: " + rule.label())
                .build());
    }

    // ------------------------------------------------------------------ extraction

    private DocumentContext loadDocument(UUID documentId, UUID tenantId) {
        List<DocumentContext> rows = jdbc.query("""
                select id, tenant_id, mandant_id, coalesce(sender, '') as sender,
                       coalesce(extracted_text, '') as text,
                       sender_details ->> 'email' as sender_email,
                       sender_details ->> 'iban' as sender_iban,
                       sender_details ->> 'vatId' as sender_vat
                  from documents
                 where id = ? and tenant_id = ? and deleted_at is null
                """, (rs, row) -> new DocumentContext((UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"), (UUID) rs.getObject("mandant_id"), rs.getString("sender"),
                null, rs.getString("text"), rs.getString("sender_email"), rs.getString("sender_iban"),
                rs.getString("sender_vat")), documentId, tenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Document");
        }
        DocumentContext base = rows.getFirst();

        // The supplier field is the most reliable company name the extraction produces. It is read separately
        // because the existing extraction table is the source, not a column of the document.
        List<String> suppliers = jdbc.queryForList("""
                select field_value from document_extractions
                 where document_id = ? and field_key = 'Lieferant' and field_value is not null
                 order by created_at desc limit 1
                """, String.class, documentId);
        String supplier = suppliers.isEmpty() ? null : suppliers.getFirst();

        // A document that arrived as a mail attachment carries the sender in the mail header. The document's own
        // sender field is only a name, the address is what can be compared with a contact.
        List<String> mails = jdbc.queryForList("""
                select m.from_address::text
                  from document_sources s
                  join mail_messages m on m.id = s.mail_message_id
                 where s.document_id = ? and s.tenant_id = ? and m.from_address is not null
                 order by s.received_at desc
                 limit 1
                """, String.class, documentId, tenantId);
        String email = base.senderEmail();
        if ((email == null || email.isBlank()) && !mails.isEmpty()) {
            email = mails.getFirst();
        }
        return new DocumentContext(base.id(), base.tenantId(), base.mandantId(), base.sender(), supplier,
                base.text(), email, base.senderIban(), base.senderVat());
    }

    /** All evidence the document itself carries. */
    private List<Signal> extractSignals(DocumentContext document) {
        Map<String, Signal> signals = new LinkedHashMap<>();
        add(signals, emailSignal(document.senderEmail()));
        add(signals, ibanSignal(document.senderIban()));
        add(signals, vatSignal(document.senderVat()));

        for (IdentifierExtractor.Found found : IdentifierExtractor.extract(document.text())) {
            MandantSignalType type = MandantSignalType.forIdentifier(found.kind());
            // An e-mail address printed somewhere in a letter may be the recipient's. Only the address the
            // message actually came from counts as the sender, never one found in the body.
            if (type == null || type == MandantSignalType.ABSENDER_EMAIL) {
                continue;
            }
            add(signals, new Signal(type, found.normalized(), found.raw(), type.strength(), false));
        }

        for (String name : document.names()) {
            String normalized = normalizeName(name);
            if (normalized.length() >= 3) {
                add(signals, new Signal(MandantSignalType.FIRMENNAME, limit(normalized), name.strip(),
                        MandantSignalType.FIRMENNAME.strength(), false));
            }
        }
        return new ArrayList<>(signals.values());
    }

    private static Signal emailSignal(String raw) {
        String normalized = IdentifierNormalizer.normalize(IdentifierKind.EMAIL, raw);
        if (normalized.isBlank() || !normalized.contains("@")) {
            return null;
        }
        return new Signal(MandantSignalType.ABSENDER_EMAIL, limit(normalized), raw.strip(),
                MandantSignalType.ABSENDER_EMAIL.strength(), false);
    }

    private static Signal ibanSignal(String raw) {
        String normalized = IdentifierNormalizer.normalize(IdentifierKind.IBAN, raw);
        if (normalized.isBlank()) {
            return null;
        }
        return new Signal(MandantSignalType.IBAN, limit(normalized), raw.strip(),
                MandantSignalType.IBAN.strength(), false);
    }

    private static Signal vatSignal(String raw) {
        String normalized = IdentifierNormalizer.normalize(IdentifierKind.VAT_ID, raw);
        if (normalized.isBlank()) {
            return null;
        }
        return new Signal(MandantSignalType.USTID, limit(normalized), raw.strip(),
                MandantSignalType.USTID.strength(), false);
    }

    private static void add(Map<String, Signal> signals, Signal signal) {
        if (signal == null || signal.muster() == null || signal.muster().isBlank()) {
            return;
        }
        String key = signal.type() + "|" + signal.muster();
        Signal previous = signals.get(key);
        if (previous == null || signal.strength() >= previous.strength()) {
            signals.put(key, signal);
        }
    }

    // ------------------------------------------------------------------ candidates

    private List<Candidate> candidates(DocumentContext document, List<Signal> signals, List<RuleRow> rules) {
        List<ContactRow> contacts = loadContacts(document.tenantId());
        Map<UUID, Candidate> candidates = new LinkedHashMap<>();
        String text = document.normalizedText();

        Map<String, Set<UUID>> byEmail = index(contacts, ContactRow::emails);
        Map<String, Set<UUID>> byIban = index(contacts, ContactRow::ibans);
        Map<String, Set<UUID>> byVat = index(contacts, ContactRow::vats);
        Map<String, Set<UUID>> byTax = index(contacts, ContactRow::taxes);

        for (Signal signal : signals) {
            Set<UUID> owners = switch (signal.type()) {
                case ABSENDER_EMAIL -> byEmail.get(signal.muster());
                case IBAN -> byIban.get(signal.muster());
                case USTID -> byVat.get(signal.muster());
                case STEUERNUMMER -> byTax.get(signal.muster());
                case FIRMENNAME, SCHLUESSELWORT -> null;
            };
            if (owners == null) {
                continue;
            }
            for (UUID owner : owners) {
                candidate(candidates, contacts, owner).add(signal);
            }
        }

        // Names are compared against the whole text, not only against the sender line: an invoice usually prints
        // the supplier's company name in the header, while the mail's display name may be a person's.
        for (ContactRow contact : contacts) {
            for (String name : contact.names()) {
                String normalized = normalizeName(name);
                if (normalized.length() < 4) {
                    continue;
                }
                boolean inText = containsPhrase(text, normalized);
                boolean isSender = document.names().stream().map(MandantRoutingService::normalizeName)
                        .anyMatch(normalized::equals);
                if (inText || isSender) {
                    candidate(candidates, contacts, contact.id()).add(new Signal(MandantSignalType.FIRMENNAME,
                            limit(normalized), name.strip(), MandantSignalType.FIRMENNAME.strength(), false));
                }
            }
        }

        // Remembered rules: the strong number §5.2 gives the next similar document. A rule that matches replaces
        // the weaker direct signal of the same type and value instead of counting twice.
        for (RuleRow rule : rules) {
            Signal matched = signals.stream()
                    .filter(signal -> signal.type() == rule.type() && signal.muster().equals(rule.muster()))
                    .findFirst().orElse(null);
            if (matched == null && rule.type() == MandantSignalType.FIRMENNAME && containsPhrase(text, rule.muster())) {
                matched = new Signal(rule.type(), rule.muster(), rule.muster(),
                        MandantSignalType.FIRMENNAME.strength(), false);
            }
            if (matched == null && rule.type() == MandantSignalType.SCHLUESSELWORT
                    && containsPhrase(text, rule.muster())) {
                matched = new Signal(rule.type(), rule.muster(), rule.muster(),
                        MandantSignalType.SCHLUESSELWORT.strength(), false);
            }
            if (matched == null) {
                continue;
            }
            Candidate candidate = candidate(candidates, rule.mandantId(), rule.mandantName());
            candidate.add(new Signal(rule.type(), rule.muster(), matched.value(), LEARNED_RULE_CONFIDENCE, true));
            candidate.addRule(rule);
        }

        return new ArrayList<>(candidates.values());
    }

    private static Candidate candidate(Map<UUID, Candidate> candidates, List<ContactRow> contacts, UUID id) {
        ContactRow contact = contacts.stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
        return candidate(candidates, id, contact == null ? null : contact.name());
    }

    private static Candidate candidate(Map<UUID, Candidate> candidates, UUID id, String name) {
        return candidates.computeIfAbsent(id, key -> new Candidate(id, name));
    }

    private static Map<String, Set<UUID>> index(List<ContactRow> contacts,
                                                java.util.function.Function<ContactRow, Set<String>> values) {
        Map<String, Set<UUID>> index = new LinkedHashMap<>();
        for (ContactRow contact : contacts) {
            for (String value : values.apply(contact)) {
                index.computeIfAbsent(value, key -> new LinkedHashSet<>()).add(contact.id());
            }
        }
        return index;
    }

    private List<ContactRow> loadContacts(UUID tenantId) {
        Map<UUID, Set<String>> emails = new LinkedHashMap<>();
        Map<UUID, Set<String>> ibans = new LinkedHashMap<>();
        Map<UUID, Set<String>> vats = new LinkedHashMap<>();
        Map<UUID, Set<String>> taxes = new LinkedHashMap<>();

        jdbc.query("""
                select contact_id, kind, normalized_value
                  from contact_identifiers
                 where tenant_id = ?
                """, rs -> {
            UUID contactId = (UUID) rs.getObject("contact_id");
            String value = rs.getString("normalized_value");
            switch (rs.getString("kind")) {
                case "EMAIL" -> emails.computeIfAbsent(contactId, key -> new LinkedHashSet<>()).add(value);
                case "IBAN" -> ibans.computeIfAbsent(contactId, key -> new LinkedHashSet<>()).add(value);
                case "VAT_ID" -> vats.computeIfAbsent(contactId, key -> new LinkedHashSet<>()).add(value);
                case "TAX_NUMBER" -> taxes.computeIfAbsent(contactId, key -> new LinkedHashSet<>()).add(value);
                default -> {
                    // Domain, phone and external ids do not identify a client for routing.
                }
            }
        }, tenantId);

        return jdbc.query("""
                select id, display_name, organization_name, email::text as email, vat_id, tax_number,
                       first_name, last_name
                  from contacts
                 where tenant_id = ? and deleted_at is null
                """, (rs, row) -> {
            UUID id = (UUID) rs.getObject("id");
            List<String> names = new ArrayList<>();
            addName(names, rs.getString("organization_name"));
            addName(names, rs.getString("display_name"));
            addName(names, join(rs.getString("first_name"), rs.getString("last_name")));
            String name = names.isEmpty() ? rs.getString("display_name") : names.getFirst();

            Set<String> emailValues = new LinkedHashSet<>(emails.getOrDefault(id, Set.of()));
            addNormalized(emailValues, IdentifierKind.EMAIL, rs.getString("email"));
            Set<String> vatValues = new LinkedHashSet<>(vats.getOrDefault(id, Set.of()));
            addNormalized(vatValues, IdentifierKind.VAT_ID, rs.getString("vat_id"));
            Set<String> taxValues = new LinkedHashSet<>(taxes.getOrDefault(id, Set.of()));
            addNormalized(taxValues, IdentifierKind.TAX_NUMBER, rs.getString("tax_number"));

            return new ContactRow(id, name, names, rs.getString("email"), rs.getString("vat_id"),
                    rs.getString("tax_number"), emailValues, ibans.getOrDefault(id, Set.of()), vatValues,
                    taxValues);
        }, tenantId);
    }

    private static String join(String first, String last) {
        if (first == null && last == null) {
            return null;
        }
        return ((first == null ? "" : first.strip()) + " " + (last == null ? "" : last.strip())).strip();
    }

    private static void addName(List<String> names, String value) {
        if (value != null && !value.isBlank() && names.stream().noneMatch(value.strip()::equalsIgnoreCase)) {
            names.add(value.strip());
        }
    }

    private static void addNormalized(Set<String> values, IdentifierKind kind, String raw) {
        String normalized = IdentifierNormalizer.normalize(kind, raw);
        if (!normalized.isBlank()) {
            values.add(normalized);
        }
    }

    // ------------------------------------------------------------------ rules: reading, using, learning

    private List<RuleRow> loadRules(UUID tenantId) {
        return jdbc.query("""
                select r.id, r.signal_typ, r.muster, r.mandant_id,
                       coalesce(nullif(c.organization_name, ''), c.display_name) as mandant_name
                  from mandant_routing_rules r
                  join contacts c on c.id = r.mandant_id and c.tenant_id = r.tenant_id
                 where r.tenant_id = ?
                """, (rs, row) -> new RuleRow((UUID) rs.getObject("id"),
                MandantSignalType.valueOf(rs.getString("signal_typ")), rs.getString("muster"),
                (UUID) rs.getObject("mandant_id"), rs.getString("mandant_name")), tenantId);
    }

    private void increaseRuleCounters(UUID tenantId, List<RuleRow> rules) {
        for (RuleRow rule : rules) {
            jdbc.update("""
                    update mandant_routing_rules
                       set treffer = treffer + 1, letzte_nutzung = now()
                     where id = ? and tenant_id = ?
                    """, rule.id(), tenantId);
        }
    }

    /**
     * Writes the strong signals of an automatic assignment as {@code quelle = 'AUTO'} rules.
     *
     * <p>Only signals that would stand on their own (0.90 and above) are remembered - an automatic decision
     * must not turn a company-name hint into a rule. A conflict means the signal is already known: its counter
     * grows, but neither the client nor a {@code MANUELL} origin is overwritten. Only a person may re-point a
     * rule.</p>
     */
    private void rememberAutomaticRules(UUID tenantId, Candidate candidate) {
        for (Signal signal : candidate.signals()) {
            if (signal.learned() || signal.strength() < AUTO_RULE_MIN_STRENGTH) {
                continue;
            }
            jdbc.update("""
                    insert into mandant_routing_rules (tenant_id, signal_typ, muster, mandant_id, quelle,
                                                       treffer, letzte_nutzung)
                    values (?, ?, ?, ?, 'AUTO', 1, now())
                    on conflict (tenant_id, signal_typ, muster) do update
                       set treffer = mandant_routing_rules.treffer + 1,
                           letzte_nutzung = now(),
                           updated_at = now()
                    """, tenantId, signal.type().name(), signal.muster(), candidate.mandantId());
        }
    }

    /**
     * Remembers a person's decision (§5.2): sender e-mail, supplier name and IBAN become rules.
     *
     * <p>A signal already known is re-pointed at the client the person chose - that is what learning from a
     * correction means. The source becomes {@code MANUELL}, so a later automatic rule can never quietly undo it.
     * A document with no such signal teaches nothing, which is not an error: a letter from an unknown supplier is
     * exactly the case where a person has to decide without help.</p>
     */
    private void learn(UUID documentId, UUID tenantId, UUID mandantId) {
        DocumentContext document = loadDocument(documentId, tenantId);
        List<Signal> learnable = new ArrayList<>();
        Signal email = emailSignal(document.senderEmail());
        if (email != null) {
            learnable.add(email);
        }
        Signal iban = ibanSignal(document.senderIban());
        if (iban == null) {
            iban = IdentifierExtractor.extract(document.text()).stream()
                    .filter(found -> found.kind() == IdentifierKind.IBAN)
                    .findFirst()
                    .map(found -> new Signal(MandantSignalType.IBAN, found.normalized(), found.raw(),
                            MandantSignalType.IBAN.strength(), false))
                    .orElse(null);
        }
        if (iban != null) {
            learnable.add(iban);
        }
        if (!document.names().isEmpty()) {
            String name = document.names().getFirst();
            String normalized = normalizeName(name);
            if (normalized.length() >= 3) {
                learnable.add(new Signal(MandantSignalType.FIRMENNAME, limit(normalized), name.strip(),
                        MandantSignalType.FIRMENNAME.strength(), false));
            }
        }

        for (Signal signal : learnable) {
            jdbc.update("""
                    insert into mandant_routing_rules (tenant_id, signal_typ, muster, mandant_id, quelle,
                                                       treffer, letzte_nutzung)
                    values (?, ?, ?, ?, 'MANUELL', 0, null)
                    on conflict (tenant_id, signal_typ, muster) do update
                       set mandant_id = excluded.mandant_id,
                           quelle = 'MANUELL',
                           updated_at = now()
                    """, tenantId, signal.type().name(), signal.muster(), mandantId);
        }
        log.info("Learned {} client signal(s) for tenant {} from document {}", learnable.size(), tenantId,
                documentId);
    }

    // ------------------------------------------------------------------ writing

    private void updateAssignment(UUID documentId, UUID tenantId, Double confidence, String status) {
        jdbc.update("""
                update documents set mandant_confidence = ?, zuordnungs_status = ?
                 where id = ? and tenant_id = ?
                """, confidence, status, documentId, tenantId);
    }

    private String mandantName(UUID tenantId, UUID mandantId) {
        List<String> names = jdbc.queryForList("""
                select coalesce(nullif(organization_name, ''), display_name)
                  from contacts where id = ? and tenant_id = ? and deleted_at is null
                """, String.class, mandantId, tenantId);
        return names.isEmpty() ? null : names.getFirst();
    }

    private static String origin(Candidate best) {
        List<String> labels = best.signals().stream()
                .filter(signal -> signal.strength() >= AUTO_RULE_MIN_STRENGTH)
                .map(signal -> label(signal.type()))
                .distinct()
                .toList();
        return "AUTO: " + Math.round(best.confidence() * 100) + " %"
                + (labels.isEmpty() ? "" : " (" + String.join(", ", labels) + ")");
    }

    private static String label(MandantSignalType type) {
        return switch (type) {
            case ABSENDER_EMAIL -> "E-Mail";
            case IBAN -> "IBAN";
            case USTID -> "USt-IdNr.";
            case STEUERNUMMER -> "Steuernummer";
            case FIRMENNAME -> "Firmenname";
            case SCHLUESSELWORT -> "Schlagwort";
        };
    }

    private static List<SignalView> signalViews(List<Signal> signals) {
        return signals.stream()
                .sorted(Comparator.comparingDouble(Signal::strength).reversed())
                .map(signal -> new SignalView(signal.type().name(), signal.value(), signal.strength()))
                .toList();
    }

    private static List<SignalView> candidateSignalViews(List<Candidate> candidates) {
        // The candidates carry the evidence; the result lists it merged so the screen can explain the decision.
        Map<String, SignalView> merged = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            for (SignalView view : candidate.view()) {
                merged.putIfAbsent(view.type() + "|" + view.value(), view);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static List<CandidateView> candidateViews(List<Candidate> candidates) {
        return candidates.stream().map(candidate -> new CandidateView(candidate.mandantId().toString(),
                candidate.mandantName(), candidate.confidence())).toList();
    }

    // ------------------------------------------------------------------ small helpers

    private static UUID tenant() {
        return RequestContext.tenantId()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
    }

    /** Lower case, umlauts folded the German way, punctuation as a separator - the same rule as the resolver. */
    static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe")
                .replace("ü", "ue").replace("ß", "ss");
        String plain = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return plain.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    /** True when the phrase appears as words, not inside a longer word ("Müller" must not match "Müllerweg"). */
    private static boolean containsPhrase(String normalizedHaystack, String normalizedNeedle) {
        if (normalizedHaystack == null || normalizedNeedle == null || normalizedNeedle.length() < 3) {
            return false;
        }
        return (" " + normalizedHaystack + " ").contains(" " + normalizedNeedle + " ");
    }

    private static String limit(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= MAX_MUSTER_LENGTH ? stripped : stripped.substring(0, MAX_MUSTER_LENGTH);
    }
}
