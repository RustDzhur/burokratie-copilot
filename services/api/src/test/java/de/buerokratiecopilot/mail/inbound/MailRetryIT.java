package de.buerokratiecopilot.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** A mailbox that stopped after failures is tried again, later and later. */
@SpringBootTest
@ActiveProfiles("test")
class MailRetryIT {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MailCollectionScheduler scheduler;

    private UUID account(UUID tenant, String status, int failures, String lastSyncAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into mail_accounts (id, tenant_id, display_name, email_address, provider, status, imap_host,
                                           imap_username, credential_encrypted, sync_interval, last_sync_at,
                                           consecutive_failures)
                values (?, ?, 'retry', ?, 'IMAP', ?, 'imap.example.invalid', 'u', 'x', 'PT5M',
                        now() - ?::interval, ?)
                """, id, tenant, id + "@example.de", status, lastSyncAgo, failures);
        return id;
    }

    @Test
    @DisplayName("a mailbox in error is collected again once the waiting time of its failures has passed, not before")
    void errorMailboxIsRetriedWithBackoff() {
        // The shared test database holds mailboxes of other tests; only the two below should be due.
        jdbc.update("update mail_accounts set last_sync_at = now()");
        UUID tenant = UUID.randomUUID();
        jdbc.update("insert into tenants (id, slug, name, account_type) values (?, ?, 'Retry', 'PRIVATE_PERSON')",
                tenant, "retry-" + tenant.toString().substring(0, 8));
        // Three failures: 5 minutes * 2^3 = 40 minutes of waiting.
        UUID early = account(tenant, "ERROR", 3, "10 minutes");
        UUID later = account(tenant, "ERROR", 3, "2 hours");

        var claimed = scheduler.claimDue().stream().map(MailCollectionScheduler.Due::accountId).toList();

        assertThat(claimed).contains(later).doesNotContain(early);
    }
}
