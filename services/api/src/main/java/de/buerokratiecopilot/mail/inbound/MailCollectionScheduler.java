package de.buerokratiecopilot.mail.inbound;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Collects mail from the connected mailboxes on their own interval, so the mail screen fills without somebody
 * pressing a button.
 *
 * <p>The list of due mailboxes is read in the system context (no tenant), then every mailbox is collected inside
 * its own tenant. A mailbox is <em>claimed</em> with one atomic update before it is read, so that several
 * instances running this class never read the same mailbox at the same time. A mailbox that failed five times in
 * a row is left alone until somebody looks at it (a collection started by hand resets the counter).</p>
 */
@Component
public class MailCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MailCollectionScheduler.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 5;
    private static final int BATCH = 50;

    private final JdbcTemplate jdbc;
    private final ImapMailIngester ingester;
    private final boolean enabled;

    public MailCollectionScheduler(JdbcTemplate jdbc, ImapMailIngester ingester,
                                   @Value("${burokratie.mail.auto-collect:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.ingester = ingester;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${burokratie.mail.collect-poll-ms:60000}",
            initialDelayString = "${burokratie.mail.collect-initial-delay-ms:90000}")
    public void collectDue() {
        if (!enabled) {
            return;
        }
        try {
            for (Due due : claimDue()) {
                try {
                    RequestContext.runAs(due.tenantId(), () -> ingester.ingest(due.tenantId(), due.accountId(), 50));
                }
                catch (RuntimeException ex) {
                    // recordSync has already stored the reason on the account; one broken mailbox must not stop the rest.
                    log.warn("Mailbox {} could not be collected: {}", due.accountId(), ex.getMessage());
                }
            }
        }
        catch (RuntimeException ex) {
            log.error("Mail collection cycle failed", ex);
        }
    }

    private List<Due> claimDue() {
        return jdbc.query("""
                update mail_accounts set last_sync_at = now()
                where id in (
                    select id from mail_accounts
                    where is_active and status in ('CONNECTED', 'DISCONNECTED')
                      and credential_encrypted is not null
                      and consecutive_failures < ?
                      and (last_sync_at is null
                           or last_sync_at < now() - greatest(sync_interval::interval, interval '1 minute'))
                    order by last_sync_at nulls first
                    limit ?
                    for update skip locked)
                returning tenant_id, id
                """, (rs, row) -> new Due(rs.getObject("tenant_id", UUID.class), rs.getObject("id", UUID.class)),
                MAX_CONSECUTIVE_FAILURES, BATCH);
    }

    private record Due(UUID tenantId, UUID accountId) {
    }
}
