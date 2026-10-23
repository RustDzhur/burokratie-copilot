package de.buerokratiecopilot.deletion;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Deletes what a person no longer wants in a list. The rows are marked as deleted, not erased, so the history stays
 * complete and an administrator can restore a mistake; only a person's own notifications are removed for good.
 *
 * <p>What is deliberately not here: invoices. German commercial and tax law (GoBD, §147 AO) requires
 * them to be kept, and a button that destroys them would be a way to break that duty.</p>
 */
@Service
public class DeletionService {

    /** What can be deleted, and the right a person needs for it. */
    public enum Kind {
        DOCUMENT("documents.delete"),
        TASK("tasks.edit"),
        CASE("cases.edit"),
        CONTACT("contacts.edit"),
        TEMPLATE("templates.edit"),
        MAIL("mail.view"),
        CALENDAR_EVENT("tasks.edit"),
        NOTIFICATION("notifications.view"),
        AUTOMATION("workflows.edit");

        private final String authority;

        Kind(String authority) {
            this.authority = authority;
        }

        public String authority() {
            return authority;
        }
    }

    private final JdbcTemplate jdbc;
    private final DocumentService documents;
    private final AuditService audit;

    public DeletionService(JdbcTemplate jdbc, DocumentService documents, AuditService audit) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.audit = audit;
    }

    private UUID tenant() {
        return RequestContext.tenantId().orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No tenant in context"));
    }

    /** Deletes one item; throws "not found" when it does not exist for this account or is already gone. */
    @Transactional
    public void delete(Kind kind, UUID id) {
        UUID tenantId = tenant();
        int changed;
        switch (kind) {
            case DOCUMENT -> {
                documents.delete(id, "list");
                return;
            }
            case TASK -> changed = jdbc.update(
                    "update tasks set deleted_at = now() where id = ? and tenant_id = ? and deleted_at is null",
                    id, tenantId);
            case CASE -> {
                changed = jdbc.update(
                        "update cases set deleted_at = now() where id = ? and tenant_id = ? and deleted_at is null",
                        id, tenantId);
                if (changed > 0) {
                    // The open work of a deleted case would be left without a case: it goes with it.
                    jdbc.update("""
                            update tasks set deleted_at = now()
                            where case_id = ? and tenant_id = ? and deleted_at is null
                              and status not in ('DONE', 'CANCELLED')
                            """, id, tenantId);
                }
            }
            case CONTACT -> changed = jdbc.update(
                    "update contacts set deleted_at = now() where id = ? and tenant_id = ? and deleted_at is null",
                    id, tenantId);
            case TEMPLATE -> changed = jdbc.update(
                    "update document_templates set is_active = false where id = ? and tenant_id = ? and is_active",
                    id, tenantId);
            case MAIL -> {
                changed = jdbc.update(
                        "update mail_messages set deleted_at = now() where id = ? and tenant_id = ? and deleted_at is null",
                        id, tenantId);
                if (changed == 0) {
                    changed = jdbc.update("""
                            update outbound_messages set deleted_at = now()
                            where id = ? and tenant_id = ? and deleted_at is null
                              and status in ('DRAFT', 'PENDING_APPROVAL')
                            """, id, tenantId);
                }
            }
            case AUTOMATION -> changed = jdbc.update("""
                    update workflow_definitions
                    set deleted_at = now(), is_active = false,
                        code = left(code, 60) || '-del-' || substr(id::text, 1, 8)
                    where id = ? and tenant_id = ? and deleted_at is null
                    """, id, tenantId);
            case CALENDAR_EVENT -> changed = jdbc.update(
                    "update calendar_events set deleted_at = now() where id = ? and tenant_id = ? and deleted_at is null",
                    id, tenantId);
            case NOTIFICATION -> changed = jdbc.update(
                    "delete from notifications where id = ? and tenant_id = ? and user_id = ?", id, tenantId,
                    RequestContext.userId().orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "No user")));
            default -> throw new ApiException(ErrorCode.INVALID_REQUEST, "Unknown kind");
        }
        if (changed == 0) {
            throw ApiException.notFound(kind.name());
        }
        audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.DOCUMENT, kind.name() + "_DELETED")
                .tenant(tenantId)
                .entity(kind.name().toLowerCase(java.util.Locale.ROOT), id, null)
                .description(kind + " deleted from a list")
                .build());
    }
}
