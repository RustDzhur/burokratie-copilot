package de.buerokratiecopilot.agents.it;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.agents.core.Agent;
import de.buerokratiecopilot.agents.core.AgentContext;
import de.buerokratiecopilot.agents.core.AgentResult;

/**
 * {@code it-access-review} — Zugriffsprüfung.
 *
 * <p>Reads the effective memberships and their roles and reports accounts that have not logged in
 * for a long time, have no role, lack MFA, or whose temporary access has expired. The review is an
 * <em>evidence list</em>: the agent revokes nothing. A wrong revocation locks a person out, and that
 * decision needs a human with the actual context.</p>
 */
@Component
public class AccessReviewAgent implements Agent {

    static final int DEFAULT_STALE_DAYS = 90;
    private static final int MAX_PRODUCED = 100;
    private static final List<String> PRIVILEGED_ROLES = List.of("OWNER", "ADMIN");

    /** One membership with the facts the review needs. */
    public record ReviewRow(UUID userId, String email, String displayName, Instant lastLogin,
                            String userStatus, String membershipStatus, Instant accessValidUntil,
                            String roles, boolean mfa) {
    }

    /** One finding: what is wrong, how severe, and about whom. */
    public record Finding(String code, String severity, String subject, String message) {
    }

    private final JdbcTemplate jdbc;

    public AccessReviewAgent(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String id() {
        return "it-access-review";
    }

    @Override
    public AgentResult run(AgentContext context) {
        int staleDays = parseDays(context.input("staleDays"));
        boolean includeInvited = "true".equalsIgnoreCase(context.input("includeInvitedUsers"));
        boolean requireMfa = "true".equalsIgnoreCase(context.input("requireMfa"));
        String statuses = includeInvited ? "('ACTIVE', 'INVITED')" : "('ACTIVE')";
        String sql = """
                select u.id, u.email, u.display_name, u.last_login_at, u.status as user_status,
                       m.status as membership_status, m.access_valid_until,
                       coalesce(string_agg(distinct r.code, ','), '') as roles,
                       exists(select 1 from mfa_methods mm
                              where mm.user_id = u.id and mm.confirmed_at is not null
                                and mm.disabled_at is null) as mfa
                from memberships m
                join users u on u.id = m.user_id and u.deleted_at is null
                left join membership_roles mr on mr.membership_id = m.id
                     and (mr.expires_at is null or mr.expires_at > now())
                left join roles r on r.id = mr.role_id
                where m.tenant_id = ? and m.status in %s
                group by u.id, u.email, u.display_name, u.status, u.last_login_at, m.status,
                         m.access_valid_until
                order by u.last_login_at nulls first, u.email
                """.formatted(statuses);
        List<ReviewRow> rows = jdbc.query(sql, (rs, row) -> new ReviewRow(
                rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getString("display_name"),
                rs.getTimestamp("last_login_at") == null ? null
                        : rs.getTimestamp("last_login_at").toInstant(),
                rs.getString("user_status"), rs.getString("membership_status"),
                rs.getTimestamp("access_valid_until") == null ? null
                        : rs.getTimestamp("access_valid_until").toInstant(),
                rs.getString("roles"), rs.getBoolean("mfa")), context.tenantId());

        if (rows.isEmpty()) {
            return AgentResult.skipped("Es gibt keine aktiven Mitgliedschaften, die geprüft werden "
                    + "könnten.");
        }

        List<Finding> findings = new ArrayList<>();
        for (ReviewRow row : rows) {
            findings.addAll(evaluate(row, context.now(), staleDays, requireMfa));
        }
        for (Finding finding : findings.stream().limit(MAX_PRODUCED).toList()) {
            context.addProduced("access-finding", finding.code() + ":" + finding.subject(),
                    "[" + finding.severity() + "] " + finding.subject() + " — "
                            + finding.message());
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("usersReviewed", String.valueOf(rows.size()));
        meta.put("findings", String.valueOf(findings.size()));
        meta.put("staleDays", String.valueOf(staleDays));
        meta.put("includeInvitedUsers", String.valueOf(includeInvited));
        meta.put("requireMfa", String.valueOf(requireMfa));
        long high = findings.stream().filter(f -> "HIGH".equals(f.severity())).count();
        String summary = findings.isEmpty()
                ? "Zugriffsprüfung: " + rows.size() + " Mitgliedschaft(en) geprüft, keine "
                        + "Auffälligkeiten."
                : "Zugriffsprüfung: " + rows.size() + " Mitgliedschaft(en) geprüft, "
                        + findings.size() + " Auffälligkeit(en), davon " + high + " mit hoher "
                        + "Priorität.";
        return AgentResult.done(summary, context.produced(), meta);
    }

    /**
     * The rules of the review, as a pure function. Each rule names its evidence; an account can have
     * more than one finding, because "no role" and "never logged in" are different problems.
     * Visible for tests.
     */
    public static List<Finding> evaluate(ReviewRow row, Instant now, int staleDays,
                                         boolean requireMfa) {
        List<Finding> findings = new ArrayList<>();
        String subject = row.email() == null ? String.valueOf(row.userId()) : row.email();
        boolean invited = "INVITED".equalsIgnoreCase(row.membershipStatus());

        if ("PENDING_VERIFICATION".equalsIgnoreCase(row.userStatus())) {
            findings.add(new Finding("ACCOUNT_UNVERIFIED", "MEDIUM", subject,
                    "Konto ist noch nicht verifiziert, hat aber Zugang"));
        }
        if (invited) {
            findings.add(new Finding("INVITED_NOT_ACTIVE", "LOW", subject,
                    "Einladung wurde noch nicht angenommen"));
        }
        if (row.accessValidUntil() != null && row.accessValidUntil().isBefore(now)) {
            findings.add(new Finding("EXPIRED_ACCESS", "HIGH", subject,
                    "Befristeter Zugang ist seit " + row.accessValidUntil()
                            + " abgelaufen, die Mitgliedschaft steht aber auf aktiv"));
        }
        if (row.roles() == null || row.roles().isBlank()) {
            findings.add(new Finding("NO_ROLE", "HIGH", subject,
                    "Mitgliedschaft ohne Rolle; Rechte sind nicht nachvollziehbar"));
        }
        if (row.lastLogin() == null) {
            findings.add(new Finding("NEVER_LOGGED_IN", "MEDIUM", subject,
                    "Konto hat sich noch nie angemeldet"));
        } else {
            Instant threshold = now.minus(staleDays, ChronoUnit.DAYS);
            if (row.lastLogin().isBefore(threshold)) {
                boolean privileged = row.roles() != null && PRIVILEGED_ROLES.stream()
                        .anyMatch(role -> row.roles().toUpperCase(Locale.ROOT).contains(role));
                findings.add(new Finding("STALE_LOGIN", privileged ? "HIGH" : "MEDIUM", subject,
                        "Letzter Login am " + row.lastLogin() + ", älter als " + staleDays
                                + " Tage" + (privileged ? " (privilegierte Rolle)" : "")));
            }
        }
        if (requireMfa && !row.mfa() && !invited) {
            findings.add(new Finding("MISSING_MFA", "MEDIUM", subject,
                    "Kein bestätigtes MFA-Verfahren hinterlegt"));
        }
        return List.copyOf(findings);
    }

    static int parseDays(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_STALE_DAYS;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return value < 1 || value > 3650 ? DEFAULT_STALE_DAYS : value;
        } catch (NumberFormatException e) {
            return DEFAULT_STALE_DAYS;
        }
    }
}
