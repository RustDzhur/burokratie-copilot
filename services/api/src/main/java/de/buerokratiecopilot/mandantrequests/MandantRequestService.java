package de.buerokratiecopilot.mandantrequests;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.notifications.domain.Notification;
import de.buerokratiecopilot.orchestration.OwnerNotifier;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;
import de.buerokratiecopilot.shared.mail.EmailMessage;
import de.buerokratiecopilot.shared.mail.EmailSender;

/**
 * Asking a client for the vouchers that are still missing (Kanzlei edition, §5.7).
 *
 * <p>Three statements shape this service, and each comes from a failure the office actually has:</p>
 *
 * <p><strong>The letter is written once and then kept.</strong> The body that is stored is the body that
 * was sent. A template edited next month must not rewrite what a client was asked for in February; the
 * request history on the client card is evidence, not a view.</p>
 *
 * <p><strong>A letter that cannot leave the house is not a success.</strong> When no mail provider is
 * configured the request stays a draft and the caller gets a 409 that names the missing piece in plain
 * German. The tempting alternative — updating the status first and logging that sending failed — would
 * show a sent request that nobody received, and the office would wait for an answer that cannot come.</p>
 *
 * <p><strong>Seven days without an answer is a state, not a notification.</strong> The reminder moves the
 * request to {@code OVERDUE} and tells the person who wrote it. The notification's dedup key is derived
 * from the request, so a watchdog that runs every minute still reminds exactly once.</p>
 *
 * <p>RLS is the second guard behind every tenant predicate. The reminder sweep runs as a maintenance
 * task: it enters each account's tenant context explicitly before reading, because the request policy is
 * strict and a system-context query would correctly see nothing.</p>
 */
@Service
public class MandantRequestService {

    private static final Logger log = LoggerFactory.getLogger(MandantRequestService.class);

    /** A request without an answer for this many days becomes OVERDUE. */
    public static final int REMINDER_AFTER_DAYS = 7;

    /** The letter a person sees before sending; every field is editable in the dialog. */
    public record Letter(String subject, String body, String mandantName, String mandantEmail) {
    }

    /** What a person typed in the dialog, or what the card generated for them. */
    public record CreateCommand(UUID mandantId, UUID documentId, List<String> missing,
                                String subject, String body, String note) {
    }

    /** One request as the screens need it. */
    public record MandantRequestView(UUID id, UUID mandantId, String mandantName, String mandantEmail,
                                     UUID documentId, String documentFilename, String subject, String body,
                                     String status, Instant createdAt, Instant sentAt, Instant lastReminderAt,
                                     int reminderCount, Instant answeredAt, Instant closedAt, String note,
                                     UUID createdBy) {
    }

    private static final String SELECT = """
            select r.id, r.mandant_id, c.display_name as mandant_name, c.email::text as mandant_email,
                   r.document_id, d.original_filename as document_filename, r.subject, r.body, r.status,
                   r.created_at, r.sent_at, r.last_reminder_at, r.reminder_count, r.answered_at,
                   r.closed_at, r.note, r.created_by
              from mandant_requests r
              join contacts c on c.id = r.mandant_id and c.tenant_id = r.tenant_id
              left join documents d on d.id = r.document_id and d.tenant_id = r.tenant_id
                                    and d.deleted_at is null
            """;

    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final List<String> STATUSES = List.of("DRAFT", "SENT", "ANSWERED", "OVERDUE", "CANCELLED");

    private final JdbcTemplate jdbc;
    private final EmailSender emailSender;
    private final AuditService audit;
    private final OwnerNotifier notifier;

    public MandantRequestService(JdbcTemplate jdbc, EmailSender emailSender, AuditService audit,
                                 OwnerNotifier notifier) {
        this.jdbc = jdbc;
        this.emailSender = emailSender;
        this.audit = audit;
        this.notifier = notifier;
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<MandantRequestView> list(UUID tenantId, UUID mandantId, UUID documentId, String status) {
        String normalizedStatus = status == null || status.isBlank() ? "" : status.strip().toUpperCase(Locale.ROOT);
        if (!normalizedStatus.isEmpty() && !STATUSES.contains(normalizedStatus)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Status: " + status);
        }
        return jdbc.query(SELECT + """
                 where r.tenant_id = ?
                   and (cast(? as uuid) is null or r.mandant_id = cast(? as uuid))
                   and (cast(? as uuid) is null or r.document_id = cast(? as uuid))
                   and (? = '' or r.status = ?)
                 order by r.created_at desc
                """, (rs, row) -> map(rs), tenantId, mandantId, mandantId, documentId, documentId,
                normalizedStatus, normalizedStatus);
    }

    @Transactional(readOnly = true)
    public MandantRequestView get(UUID tenantId, UUID id) {
        List<MandantRequestView> rows = jdbc.query(SELECT + " where r.tenant_id = ? and r.id = ?",
                (rs, row) -> map(rs), tenantId, id);
        if (rows.isEmpty()) {
            // A request of another account and a request that does not exist are the same answer: the caller
            // may not learn which identifiers exist elsewhere.
            throw ApiException.notFound("Anfrage");
        }
        return rows.getFirst();
    }

    // ------------------------------------------------------------------ writing

    /**
     * Writes the German letter from the client and the voucher, without storing anything.
     *
     * <p>The preview exists so the dialog is prefilled by the same code that writes the stored letter: two
     * generators would drift and the person would send a text the backend did not produce.</p>
     */
    @Transactional(readOnly = true)
    public Letter preview(UUID tenantId, CreateCommand command) {
        Contact contact = requireContact(tenantId, command.mandantId());
        DocumentFacts document = command.documentId() == null ? null
                : requireDocument(tenantId, command.documentId());
        return compose(tenantId, contact, document, command.missing());
    }

    @Transactional
    public MandantRequestView create(UUID tenantId, CreateCommand command, UUID createdBy) {
        Contact contact = requireContact(tenantId, command.mandantId());
        DocumentFacts document = command.documentId() == null ? null
                : requireDocument(tenantId, command.documentId());

        Letter generated = compose(tenantId, contact, document, command.missing());
        String subject = overrideOrDefault(command.subject(), generated.subject());
        String body = overrideOrDefault(command.body(), generated.body());

        UUID id = jdbc.queryForObject("""
                insert into mandant_requests (id, tenant_id, mandant_id, document_id, created_by, subject, body,
                                              status, note)
                values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                returning id
                """, UUID.class, tenantId, command.mandantId(), command.documentId(), createdBy, subject, body,
                blankToNull(command.note()));

        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.TEMPLATE, "MANDANT_REQUEST_CREATED")
                .tenant(tenantId)
                .entity("mandant_requests", id, subject)
                .description("Anfrage an Mandant angelegt")
                .build());

        log.info("Mandant request {} created for client {} (voucher {})", id, command.mandantId(),
                command.documentId());
        return get(tenantId, id);
    }

    /** Edits the text of a draft. A request that already left the house is evidence and is not rewritten. */
    @Transactional
    public MandantRequestView update(UUID tenantId, UUID id, String subject, String body, String note) {
        MandantRequestView current = get(tenantId, id);
        requireStatus(current, "DRAFT", "Nur ein Entwurf kann bearbeitet werden.");
        String nextSubject = subject == null ? current.subject() : subject.strip();
        String nextBody = body == null ? current.body() : body.strip();
        if (nextSubject.isEmpty() || nextBody.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Betreff und Text dürfen nicht leer sein");
        }
        jdbc.update("""
                update mandant_requests set subject = ?, body = ?, note = coalesce(?, note)
                 where tenant_id = ? and id = ? and status = 'DRAFT'
                """, nextSubject, nextBody, blankToNull(note), tenantId, id);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.TEMPLATE, "MANDANT_REQUEST_UPDATED")
                .tenant(tenantId)
                .entity("mandant_requests", id, nextSubject)
                .description("Anfragetext bearbeitet")
                .build());
        return get(tenantId, id);
    }

    /**
     * Hands the letter to the mail mechanism.
     *
     * <p>Order matters: the provider is checked, the message is handed over, and only then the status
     * changes. If any of the first two fails, the draft is still a draft.</p>
     */
    @Transactional
    public MandantRequestView send(UUID tenantId, UUID id) {
        MandantRequestView current = get(tenantId, id);
        requireStatus(current, "DRAFT", "Diese Anfrage wurde bereits versendet oder abgeschlossen.");
        if (current.mandantEmail() == null || current.mandantEmail().isBlank()) {
            throw new ApiException(ErrorCode.MANDANT_EMAIL_MISSING,
                    "Für diesen Mandanten ist keine E-Mail-Adresse hinterlegt");
        }
        if (!emailSender.isConfigured()) {
            // 409, not 500: a deployment without mail is a configuration state, and the draft is untouched.
            throw new ApiException(ErrorCode.MAIL_NOT_CONFIGURED,
                    "Der E-Mail-Versand ist noch nicht eingerichtet");
        }

        try {
            emailSender.send(EmailMessage.of(current.mandantEmail(), current.subject(), current.body()));
        }
        catch (RuntimeException ex) {
            log.warn("Mandant request {} could not be handed to the mail provider: {}", id, ex.getMessage());
            throw new ApiException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "Der E-Mail-Versand ist fehlgeschlagen", ex);
        }

        int changed = jdbc.update("""
                update mandant_requests set status = 'SENT', sent_at = now()
                 where tenant_id = ? and id = ? and status = 'DRAFT'
                """, tenantId, id);
        if (changed == 0) {
            throw ApiException.invalidState("Diese Anfrage wurde bereits versendet oder abgeschlossen.");
        }

        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.TEMPLATE, "MANDANT_REQUEST_SENT")
                .tenant(tenantId)
                .entity("mandant_requests", id, current.subject())
                .description("Anfrage an Mandant versendet")
                .build());

        log.info("Mandant request {} sent to {}", id, current.mandantEmail());
        return get(tenantId, id);
    }

    @Transactional
    public MandantRequestView cancel(UUID tenantId, UUID id) {
        MandantRequestView current = get(tenantId, id);
        if (!List.of("DRAFT", "SENT", "OVERDUE").contains(current.status())) {
            throw ApiException.invalidState("Diese Anfrage ist bereits abgeschlossen.");
        }
        jdbc.update("""
                update mandant_requests set status = 'CANCELLED', closed_at = now()
                 where tenant_id = ? and id = ? and status in ('DRAFT', 'SENT', 'OVERDUE')
                """, tenantId, id);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.TEMPLATE, "MANDANT_REQUEST_CANCELLED")
                .tenant(tenantId)
                .entity("mandant_requests", id, current.subject())
                .description("Anfrage zurückgezogen")
                .build());
        return get(tenantId, id);
    }

    /**
     * Records that an answer arrived.
     *
     * <p>Manual on purpose. The automatic path — noticing that an incoming mail from the client answers
     * this request — would have to guess which of several open requests a one-line "anbei" belongs to, and
     * a wrong guess closes a request that is still open. A person can see the mail and decide.</p>
     */
    @Transactional
    public MandantRequestView markAnswered(UUID tenantId, UUID id) {
        MandantRequestView current = get(tenantId, id);
        if ("ANSWERED".equals(current.status())) {
            return current;
        }
        if (!List.of("SENT", "OVERDUE").contains(current.status())) {
            throw ApiException.invalidState("Nur eine versendete Anfrage kann als beantwortet markiert werden.");
        }
        jdbc.update("""
                update mandant_requests set status = 'ANSWERED', answered_at = now(), closed_at = now()
                 where tenant_id = ? and id = ? and status in ('SENT', 'OVERDUE')
                """, tenantId, id);
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.TEMPLATE, "MANDANT_REQUEST_ANSWERED")
                .tenant(tenantId)
                .entity("mandant_requests", id, current.subject())
                .description("Antwort des Mandanten vermerkt")
                .build());
        return get(tenantId, id);
    }

    // ------------------------------------------------------------------ reminder

    /**
     * The seven-day reminder, called by the watchdog.
     *
     * <p>No tenant is known when the scheduler fires, so the accounts are walked explicitly and each one
     * gets its own tenant context. The request policy has no system-context escape hatch on purpose; a
     * sweep that read across accounts without entering them would be reading around the guard.</p>
     *
     * @return how many requests were moved to OVERDUE and announced
     */
    public int remindOverdue() {
        return remindOverdue(null);
    }

    /** One cycle, optionally limited to one tenant (the operator looking at a single customer, or a test). */
    public int remindOverdue(UUID onlyTenant) {
        List<UUID> tenants = onlyTenant != null
                ? List.of(onlyTenant)
                : jdbc.query("select id from tenants where status = 'ACTIVE' and deleted_at is null",
                        (rs, row) -> rs.getObject(1, UUID.class));

        int reminded = 0;
        for (UUID tenantId : tenants) {
            try {
                // The connection takes its tenant at checkout, so the context has to be set before the query.
                reminded += RequestContext.runAs(tenantId, () -> remindTenant(tenantId));
            }
            catch (RuntimeException ex) {
                // One account with a broken row must not stop the sweep for every other account.
                log.warn("Mandant request reminder failed for tenant {}: {}", tenantId, ex.getMessage());
            }
        }
        return reminded;
    }

    private int remindTenant(UUID tenantId) {
        List<DueRequest> due = jdbc.query("""
                select r.id, r.mandant_id, r.created_by, r.subject, c.display_name as mandant_name
                  from mandant_requests r
                  join contacts c on c.id = r.mandant_id and c.tenant_id = r.tenant_id
                 where r.tenant_id = ?
                   and r.status = 'SENT'
                   and r.sent_at is not null
                   and r.sent_at < now() - make_interval(days => ?)
                 order by r.sent_at
                 limit 100
                """, (rs, row) -> new DueRequest(
                        rs.getObject("id", UUID.class),
                        rs.getObject("mandant_id", UUID.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getString("subject"),
                        rs.getString("mandant_name")),
                tenantId, REMINDER_AFTER_DAYS);

        int notified = 0;
        for (DueRequest request : due) {
            String dedupKey = "mandant-request-reminder-" + request.id();
            Boolean alreadyNotified = jdbc.queryForObject("""
                    select exists (select 1 from notifications where dedup_key = ?)
                    """, Boolean.class, dedupKey);
            if (!Boolean.TRUE.equals(alreadyNotified)) {
                List<UUID> recipients = responsiblePeople(tenantId, request.createdBy());
                notifier.tell(tenantId, recipients, Notification.Category.TASK,
                        Notification.Severity.WARNING, "mandant.request_overdue", "Anfrage ohne Antwort",
                        "Der Mandant „" + request.mandantName() + "“ hat auf die Anfrage „"
                                + request.subject() + "“ nach sieben Tagen nicht geantwortet.",
                        "mandant_requests", request.id(), "/mandanten/" + request.mandantId(), dedupKey,
                        Map.of("mandantName", request.mandantName(), "requestSubject", request.subject(),
                                "requestId", request.id().toString()));
                notified++;
            }
            // Even when the notification already existed, the request is moved on once. A crash between the
            // notification and this update must not leave a request in SENT forever.
            jdbc.update("""
                    update mandant_requests
                       set status = 'OVERDUE', last_reminder_at = now(),
                           reminder_count = reminder_count + 1
                     where tenant_id = ? and id = ? and status = 'SENT'
                    """, tenantId, request.id());
        }
        if (!due.isEmpty()) {
            log.info("Mandant request reminder: {} overdue in tenant {}, {} newly notified", due.size(), tenantId,
                    notified);
        }
        return notified;
    }

    /** The person who wrote the request; the owners if that person has left the account. */
    private List<UUID> responsiblePeople(UUID tenantId, UUID createdBy) {
        if (createdBy != null) {
            Integer active = jdbc.queryForObject("""
                    select count(*) from users where id = ? and status = 'ACTIVE' and deleted_at is null
                    """, Integer.class, createdBy);
            if (active != null && active > 0) {
                return List.of(createdBy);
            }
        }
        return jdbc.query("""
                select user_id from memberships
                 where tenant_id = ? and status = 'ACTIVE' and is_owner
                """, (rs, row) -> rs.getObject(1, UUID.class), tenantId);
    }

    record DueRequest(UUID id, UUID mandantId, UUID createdBy, String subject, String mandantName) {
    }

    // ------------------------------------------------------------------ letter

    private record Contact(UUID id, String displayName, String contactType, String firstName, String lastName,
                           String email) {
    }

    private record DocumentFacts(UUID id, String filename, LocalDate date, String number, String amount) {
    }

    private Contact requireContact(UUID tenantId, UUID mandantId) {
        if (mandantId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Eine Anfrage braucht einen Mandanten");
        }
        List<Contact> rows = jdbc.query("""
                select id, display_name, contact_type, first_name, last_name, email::text as email
                  from contacts
                 where tenant_id = ? and id = ? and deleted_at is null
                """, (rs, row) -> new Contact(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name"),
                        rs.getString("contact_type"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("email")),
                tenantId, mandantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Mandant");
        }
        return rows.getFirst();
    }

    private DocumentFacts requireDocument(UUID tenantId, UUID documentId) {
        List<DocumentFacts> rows = jdbc.query("""
                select id, original_filename, document_date, document_number
                  from documents
                 where tenant_id = ? and id = ? and deleted_at is null
                """, (rs, row) -> new DocumentFacts(
                        rs.getObject("id", UUID.class),
                        rs.getString("original_filename"),
                        rs.getObject("document_date", LocalDate.class),
                        rs.getString("document_number"),
                        null),
                tenantId, documentId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Beleg");
        }
        DocumentFacts facts = rows.getFirst();
        // Read after the document row is closed: a nested query while its ResultSet is still open is the kind
        // of coupling that works until a driver or a fetch size changes.
        return new DocumentFacts(facts.id(), facts.filename(), facts.date(), facts.number(),
                extractedAmount(documentId));
    }

    /**
     * The amount a person would read off the voucher.
     *
     * <p>The extracted fields are the source, not {@code structured_data}: the extraction carries the
     * provenance and the type, and the priority order prefers the gross amount the accountant books.</p>
     */
    private String extractedAmount(UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select coalesce(normalized_value, field_value) as value
                  from document_extractions
                 where document_id = ? and value_type = 'AMOUNT'
                 order by case field_key
                              when 'Brutto' then 0
                              when 'Rechnungsbetrag' then 1
                              when 'OffenerBetrag' then 2
                              when 'Betrag' then 3
                              when 'Nettobetrag' then 4
                              else 9
                          end,
                          confidence desc nulls last
                """, documentId);
        for (Map<String, Object> row : rows) {
            Object raw = row.get("value");
            if (raw == null) {
                continue;
            }
            String text = String.valueOf(raw).strip();
            try {
                // The extraction stores a comparable decimal string; a German-formatted value is accepted
                // too, because older rows and manual corrections are not guaranteed to be normalised.
                BigDecimal amount = text.contains(",") && !text.contains(".")
                        ? new BigDecimal(text.replace(".", "").replace(',', '.'))
                        : new BigDecimal(text);
                NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.GERMANY);
                // The currency format puts a non-breaking space before the euro sign. In a plain-text letter a
                // normal space is easier to assert, copy and search for, and reads identically.
                return currency.format(amount).replace('\u00A0', ' ');
            }
            catch (NumberFormatException ignored) {
                // An unreadable amount is left out of the letter rather than guessed at.
            }
        }
        return null;
    }

    private Letter compose(UUID tenantId, Contact contact, DocumentFacts document, List<String> missing) {
        String mandantName = contact.displayName() == null ? "" : contact.displayName().strip();
        String dateText = document == null || document.date() == null ? null
                : document.date().format(GERMAN_DATE);

        String subject = "Anfrage: fehlende Belege – " + mandantName
                + (dateText == null ? "" : " – Rechnung vom " + dateText);

        StringBuilder body = new StringBuilder();
        body.append(greeting(contact)).append("\n\n");
        if (document == null) {
            body.append("für die Buchung Ihrer Belege fehlt uns: ");
        }
        else {
            if (dateText == null) {
                body.append("für die Buchung der Rechnung");
            }
            else {
                body.append("für die Buchung der Rechnung vom ").append(dateText);
            }
            List<String> details = new ArrayList<>();
            if (notBlank(document.number())) {
                details.add("Rechnungsnummer: " + document.number().strip());
            }
            if (notBlank(document.amount())) {
                details.add("Betrag: " + document.amount());
            }
            if (!details.isEmpty()) {
                body.append(" (").append(String.join(", ", details)).append(")");
            }
            body.append(" fehlt uns: ");
        }
        body.append(missingPhrase(missing)).append(".\n\n");
        body.append("Bitte senden Sie uns die fehlenden Unterlagen zu. "
                + "Am einfachsten antworten Sie direkt auf diese E-Mail.\n\n");
        body.append("Vielen Dank und freundliche Grüße\n");
        body.append(tenantName(tenantId));

        return new Letter(subject, body.toString(), mandantName, contact.email());
    }

    /**
     * The salutation from what the contact actually is.
     *
     * <p>An organisation has no gender to guess at, so it gets the neutral form. A person is addressed with
     * the template's "Sehr geehrte/r Frau/Herr …" because the contact table stores no gender, and inventing
     * one from a first name would be wrong for somebody.</p>
     */
    private static String greeting(Contact contact) {
        if (!"PERSON".equals(contact.contactType())) {
            return "Sehr geehrte Damen und Herren,";
        }
        String name = notBlank(contact.lastName()) ? contact.lastName().strip()
                : notBlank(contact.firstName()) ? contact.firstName().strip()
                : contact.displayName() == null ? "" : contact.displayName().strip();
        return "Sehr geehrte/r Frau/Herr " + name + ",";
    }

    /**
     * "fehlt uns: …" as a German list.
     *
     * <p>The dialog sends stable keys; German words and free text are accepted as well, because a caller
     * that already writes the office's language should not have to translate itself into codes.</p>
     */
    private static String missingPhrase(List<String> missing) {
        if (missing == null || missing.isEmpty()) {
            return "die vollständigen Angaben zum Beleg";
        }
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        for (String item : missing) {
            if (item == null || item.isBlank()) {
                continue;
            }
            phrases.add(missingItem(item.strip()));
        }
        if (phrases.isEmpty()) {
            return "die vollständigen Angaben zum Beleg";
        }
        List<String> list = List.copyOf(phrases);
        if (list.size() == 1) {
            return list.getFirst();
        }
        return String.join(", ", list.subList(0, list.size() - 1)) + " und " + list.getLast();
    }

    private static String missingItem(String item) {
        return switch (item.toLowerCase(Locale.GERMAN)) {
            case "amount", "betrag", "summe", "rechnungsbetrag" -> "der Rechnungsbetrag";
            case "date", "datum", "rechnungsdatum", "belegdatum" -> "das Rechnungsdatum";
            case "number", "nummer", "rechnungsnummer", "belegnummer" -> "die Rechnungsnummer";
            case "attachment", "anlage", "anhang", "beleg", "beleg-pdf" -> "der Beleg als Anlage";
            // Free text is kept as written: it is the office's own wording, not a system term.
            default -> item;
        };
    }

    private String tenantName(UUID tenantId) {
        String name = jdbc.query("select name from tenants where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, tenantId);
        return notBlank(name) ? name.strip() : "Ihre Kanzlei";
    }

    // ------------------------------------------------------------------ helpers

    private static void requireStatus(MandantRequestView request, String expected, String message) {
        if (!expected.equals(request.status())) {
            throw ApiException.invalidState(message);
        }
    }

    private static String overrideOrDefault(String override, String generated) {
        if (override == null) {
            return generated;
        }
        String stripped = override.strip();
        if (stripped.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Betreff und Text dürfen nicht leer sein");
        }
        return stripped;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static MandantRequestView map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MandantRequestView(
                rs.getObject("id", UUID.class),
                rs.getObject("mandant_id", UUID.class),
                rs.getString("mandant_name"),
                rs.getString("mandant_email"),
                rs.getObject("document_id", UUID.class),
                rs.getString("document_filename"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("status"),
                instant(rs, "created_at"),
                instant(rs, "sent_at"),
                instant(rs, "last_reminder_at"),
                rs.getInt("reminder_count"),
                instant(rs, "answered_at"),
                instant(rs, "closed_at"),
                rs.getString("note"),
                rs.getObject("created_by", UUID.class));
    }
}
