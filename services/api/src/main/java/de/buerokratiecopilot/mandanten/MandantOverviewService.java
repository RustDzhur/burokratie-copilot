package de.buerokratiecopilot.mandanten;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The clients of one Kanzlei and how each of them stands (Kanzlei edition, §5.5).
 *
 * <p>The screen answers one question a bookkeeper asks every morning: <em>where is something wrong</em>. It
 * is not a client list with a document count; a list alone makes a person open every row to find the one that
 * hurts. So every client gets a health judgement derived from facts the office already stores, and the whole
 * list is one statement - a counter per client would be one query per row, which is the N+1 that turns a
 * hundred clients into a hundred round trips and an inconsistent screen.</p>
 *
 * <p><strong>The rule is the specification's, not a guess.</strong> {@code RISK} means somebody is already
 * exposed: a voucher failed, or a deadline has passed. {@code ACTION} means work is waiting: something is
 * unclear, or vouchers still waiting to be booked ({@code NEU} or {@code OFFEN}) have not moved for more than
 * {@link #STALE_AFTER_DAYS} days.
 * Everything else is {@code OK}. RISK wins over ACTION on purpose - the abandoned deadline is the one that
 * costs money.</p>
 *
 * <p><strong>"Open" and "overdue" are different numbers.</strong> {@code deadlinesOpen} counts the active
 * deadlines whose due date is today or later, exactly as the screen's column promises. An overdue deadline
 * does not disappear from the client; it moves the client to {@code RISK}. Counting both in one number would
 * let "3 Fristen" hide that one of them is already late.</p>
 *
 * <p><strong>The office's view, not one person's queue.</strong> Deadlines elsewhere (the calendar, the task
 * list) are narrowed to what the reader is responsible for. This screen is the Kanzlei's overview of its own
 * clients, and it is granted by {@code contacts.view}: a bookkeeper who may see the client may see that the
 * client is in trouble. Tenant isolation is unchanged and enforced twice - by the tenant predicate below and
 * by row level security.</p>
 *
 * <p>The aggregation reads the schema directly instead of loading documents and deadlines into memory: the
 * numbers are a database's job, and doing it here would make the screen slower with every voucher the
 * Kanzlei books.</p>
 */
@Service
public class MandantOverviewService {

    /** How long a voucher may stand in {@code OFFEN} without any movement before the client needs attention. */
    public static final int STALE_AFTER_DAYS = 14;

    /** At most this many of the newest vouchers are shown on the client card. */
    public static final int RECENT_DOCUMENTS = 10;

    /** At most this many open deadlines are listed on the client card. */
    public static final int OPEN_DEADLINES = 50;

    /** How a client stands, from the bookkeeper's point of view. */
    public enum Health {
        /** Nothing is waiting and nothing is late. */
        OK,
        /** Work is waiting: something must be checked, or unbooked vouchers have not moved for two weeks. */
        ACTION,
        /** Somebody is exposed: a voucher failed, or a deadline has passed. */
        RISK
    }

    /** One row of the client list. */
    public record MandantSummary(UUID id, String displayName, String contactType, String email, String city,
                                 int documentsTotal, int offen, int zuPruefen, int exportiert, int fehler,
                                 int deadlinesOpen, LocalDate lastActivityAt, Health health) {
    }

    /** A voucher on the client card: what it is, where it stands, and when it counts. */
    public record DocumentEntry(UUID id, String filename, String belegStatus, LocalDate date) {
    }

    /** A deadline that is still ahead. Past deadlines are not listed - they made the client RISK. */
    public record DeadlineEntry(UUID id, String deadlineType, LocalDate dueDate, boolean requiresVerification,
                                UUID documentId) {
    }

    /** The client card: the list row plus what a person needs to act on it. */
    public record MandantDetail(UUID id, String displayName, String contactType, String email, String city,
                                int documentsTotal, int offen, int zuPruefen, int exportiert, int fehler,
                                int deadlinesOpen, LocalDate lastActivityAt, Health health,
                                List<DocumentEntry> lastDocuments, List<DeadlineEntry> openDeadlines) {
    }

    /**
     * The counters, gathered once per client.
     *
     * <p>{@code coalesce(...) >= current_date} is the due-date rule of §5.4: a deadline is open while its day
     * has not passed. A deadline may carry a timestamp instead of a day, so the Berlin day is derived exactly
     * like the calendar does it - two screens disagreeing about which day a deadline falls on is the kind of
     * defect that loses a client.</p>
     */
    private static final String SUMMARIES = """
            with belege as (
                select d.mandant_id,
                       count(*) as documents_total,
                       -- "Offen" is the work still to be booked: a new voucher nobody has looked at yet
                       -- counts as much as an accepted one waiting for its booking data (§5.5).
                       count(*) filter (where d.beleg_status in ('NEU', 'OFFEN')) as offen,
                       count(*) filter (where d.beleg_status = 'ZU_PRUEFEN') as zu_pruefen,
                       count(*) filter (where d.beleg_status = 'EXPORTIERT') as exportiert,
                       count(*) filter (where d.beleg_status = 'FEHLER') as fehler,
                       -- The date the voucher carries; a voucher whose date was not extracted is dated by its
                       -- arrival, so a client never looks idle because the OCR found no date.
                       max(coalesce(d.document_date, d.created_at::date)) as last_activity_at,
                       max(d.updated_at) filter (where d.beleg_status in ('NEU', 'OFFEN'))
                           as last_offen_movement
                  from documents d
                 where d.tenant_id = ? and d.deleted_at is null and d.mandant_id is not null
                 group by d.mandant_id
            ),
            fristen as (
                select d.mandant_id,
                       count(*) filter (where coalesce(dd.due_date,
                                                      (dd.due_at at time zone 'Europe/Berlin')::date) >= current_date)
                           as offen,
                       count(*) filter (where coalesce(dd.due_date,
                                                      (dd.due_at at time zone 'Europe/Berlin')::date) < current_date)
                           as ueberfaellig
                  from task_deadlines dd
                  join documents d on d.id = dd.source_document_id
                                  and d.tenant_id = dd.tenant_id
                                  and d.deleted_at is null
                 where dd.tenant_id = ? and dd.is_active and d.mandant_id is not null
                 group by d.mandant_id
            )
            select c.id, c.display_name, c.email::text as email,
                   case when c.is_authority then 'AUTHORITY'
                        when c.contact_type = 'ORGANIZATION' then 'ORGANISATION'
                        else 'PERSON' end as contact_type,
                   (select a.city from contact_addresses a where a.contact_id = c.id
                     order by a.created_at limit 1) as city,
                   coalesce(b.documents_total, 0) as documents_total,
                   coalesce(b.offen, 0) as offen,
                   coalesce(b.zu_pruefen, 0) as zu_pruefen,
                   coalesce(b.exportiert, 0) as exportiert,
                   coalesce(b.fehler, 0) as fehler,
                   coalesce(f.offen, 0) as deadlines_open,
                   coalesce(f.ueberfaellig, 0) as deadlines_overdue,
                   b.last_activity_at,
                   b.last_offen_movement
              from contacts c
              left join belege b on b.mandant_id = c.id
              left join fristen f on f.mandant_id = c.id
             where c.tenant_id = ? and c.deleted_at is null
            """;

    private static final String RECENT_DOCUMENTS_SQL = """
            select d.id, d.original_filename, d.beleg_status,
                   coalesce(d.document_date, d.created_at::date) as date
              from documents d
             where d.tenant_id = ? and d.mandant_id = ? and d.deleted_at is null
             order by coalesce(d.document_date, d.created_at::date) desc, d.created_at desc
             limit ?
            """;

    private static final String OPEN_DEADLINES_SQL = """
            select dd.id, dd.deadline_type, dd.requires_verification, dd.source_document_id,
                   coalesce(dd.due_date, (dd.due_at at time zone 'Europe/Berlin')::date) as due_date
              from task_deadlines dd
              join documents d on d.id = dd.source_document_id
                              and d.tenant_id = dd.tenant_id
                              and d.deleted_at is null
             where dd.tenant_id = ? and dd.is_active and d.mandant_id = ?
               and coalesce(dd.due_date, (dd.due_at at time zone 'Europe/Berlin')::date) >= current_date
             order by coalesce(dd.due_date, (dd.due_at at time zone 'Europe/Berlin')::date),
                      dd.deadline_type
             limit ?
            """;

    private final JdbcTemplate jdbc;

    public MandantOverviewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The clients of the account, by name.
     *
     * @param query        a piece of the client's name, or {@code null}/blank for all
     * @param onlyProblems {@code true} keeps exactly the clients whose health is not {@code OK}
     */
    @Transactional(readOnly = true)
    public List<MandantSummary> list(UUID tenantId, String query, boolean onlyProblems) {
        String term = query == null ? "" : query.strip();
        List<MandantSummary> rows = jdbc.query(SUMMARIES + """
                  and (? = '' or lower(c.display_name) like '%' || lower(?) || '%')
                 order by c.display_name
                """, (rs, index) -> map(rs), tenantId, tenantId, tenantId, term, term);

        List<MandantSummary> answer = new ArrayList<>(rows.size());
        for (MandantSummary row : rows) {
            // The problem filter runs after the rule, not beside it: SQL would have to repeat the rule, and a
            // rule written twice is a rule that disagrees with itself on the first change.
            if (onlyProblems && row.health() == Health.OK) {
                continue;
            }
            answer.add(row);
        }
        return answer;
    }

    /** One client with the vouchers and the deadlines a person needs to act on. */
    @Transactional(readOnly = true)
    public MandantDetail detail(UUID tenantId, UUID id) {
        List<MandantSummary> rows = jdbc.query(SUMMARIES + " and c.id = ?", (rs, index) -> map(rs),
                tenantId, tenantId, tenantId, id);
        if (rows.isEmpty()) {
            // A client of another account and a client that does not exist are the same answer: the caller may
            // not learn which identifiers exist elsewhere.
            throw new ApiException(ErrorCode.NOT_FOUND, "Diesen Mandanten gibt es nicht");
        }
        MandantSummary summary = rows.getFirst();

        List<DocumentEntry> documents = jdbc.query(RECENT_DOCUMENTS_SQL, (rs, index) -> new DocumentEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("original_filename"),
                        rs.getString("beleg_status"),
                        rs.getObject("date", LocalDate.class)),
                tenantId, id, RECENT_DOCUMENTS);

        List<DeadlineEntry> deadlines = jdbc.query(OPEN_DEADLINES_SQL, (rs, index) -> new DeadlineEntry(
                        rs.getObject("id", UUID.class),
                        rs.getString("deadline_type"),
                        rs.getObject("due_date", LocalDate.class),
                        rs.getBoolean("requires_verification"),
                        rs.getObject("source_document_id", UUID.class)),
                tenantId, id, OPEN_DEADLINES);

        return new MandantDetail(summary.id(), summary.displayName(), summary.contactType(), summary.email(),
                summary.city(), summary.documentsTotal(), summary.offen(), summary.zuPruefen(),
                summary.exportiert(), summary.fehler(), summary.deadlinesOpen(), summary.lastActivityAt(),
                summary.health(), documents, deadlines);
    }

    /**
     * The health rule, in one place.
     *
     * <p>RISK is checked first because it is the one that already costs money; a client can be both late on a
     * deadline and waiting for a check, and the screen must not report the milder of the two.</p>
     */
    static Health health(int fehler, int zuPruefen, int offen, int overdueDeadlines, Instant lastOffenMovement,
                         Instant now) {
        if (fehler > 0 || overdueDeadlines > 0) {
            return Health.RISK;
        }
        boolean stale = offen > 0
                && (lastOffenMovement == null
                    || lastOffenMovement.isBefore(now.minus(Duration.ofDays(STALE_AFTER_DAYS))));
        return zuPruefen > 0 || stale ? Health.ACTION : Health.OK;
    }

    private MandantSummary map(java.sql.ResultSet rs) throws java.sql.SQLException {
        int fehler = rs.getInt("fehler");
        int zuPruefen = rs.getInt("zu_pruefen");
        int offen = rs.getInt("offen");
        int overdue = rs.getInt("deadlines_overdue");
        java.sql.Timestamp movement = rs.getTimestamp("last_offen_movement");
        Instant lastOffenMovement = movement == null ? null : movement.toInstant();

        return new MandantSummary(
                rs.getObject("id", UUID.class),
                rs.getString("display_name"),
                rs.getString("contact_type"),
                rs.getString("email"),
                rs.getString("city"),
                rs.getInt("documents_total"),
                offen,
                zuPruefen,
                rs.getInt("exportiert"),
                fehler,
                rs.getInt("deadlines_open"),
                rs.getObject("last_activity_at", LocalDate.class),
                health(fehler, zuPruefen, offen, overdue, lastOffenMovement, Instant.now()));
    }
}
