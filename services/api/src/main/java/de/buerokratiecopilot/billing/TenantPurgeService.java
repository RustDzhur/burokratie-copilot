package de.buerokratiecopilot.billing;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import de.buerokratiecopilot.shared.storage.BlobStore;

/**
 * Deletes a tenant and everything that belongs to it: the rows (through the cascading foreign keys), the audit
 * trail and the stored files, and the people who belonged to nothing else.
 *
 * <p>Not undone by anything: there is no soft delete here. It is called only after the customer confirmed it, or
 * when the retention period after the end of access has run out. Invoices the platform issued are kept on
 * purpose (accounting records have their own statutory retention) and are not tied to the tenant by a foreign
 * key. Must run in the system context (no tenant), because the tables it empties are guarded by row level
 * security.</p>
 */
@Service
public class TenantPurgeService {

    private static final Logger log = LoggerFactory.getLogger(TenantPurgeService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BlobStore blobStore;

    public TenantPurgeService(JdbcTemplate jdbc, TransactionTemplate transaction, BlobStore blobStore) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.blobStore = blobStore;
    }

    public record Result(int files, int people) {
    }

    public Result purge(UUID tenantId) {
        List<String> keys = jdbc.queryForList("select storage_key from document_blobs where tenant_id = ?",
                String.class, tenantId);
        List<UUID> members = jdbc.queryForList("select user_id from memberships where tenant_id = ?", UUID.class,
                tenantId);

        Integer people = transaction.execute(status -> {
            // Transaction-local: the append-only triggers let this one tenant's logs go, and nothing else.
            jdbc.queryForObject("select set_config('app.tenant_purge', ?, true)", String.class, tenantId.toString());
            jdbc.update("delete from audit_events where tenant_id = ?", tenantId);
            jdbc.update("delete from document_access_logs where tenant_id = ?", tenantId);
            jdbc.update("delete from access_change_log where tenant_id = ?", tenantId);
            jdbc.update("delete from tenants where id = ?", tenantId);
            int removed = 0;
            for (UUID member : members) {
                removed += jdbc.update("""
                        delete from users where id = ?
                          and not exists (select 1 from memberships where user_id = ?)
                        """, member, member);
            }
            return removed;
        });

        // After the commit: a file that cannot be removed is logged, it must not undo the deletion of the data.
        int deleted = 0;
        for (String key : keys) {
            try {
                blobStore.delete(key);
                deleted++;
            }
            catch (RuntimeException ex) {
                log.warn("Stored file {} of the purged tenant {} could not be removed", key, tenantId, ex);
            }
        }
        log.info("Tenant {} purged: {} files, {} people", tenantId, deleted, people);
        return new Result(deleted, people == null ? 0 : people);
    }
}
