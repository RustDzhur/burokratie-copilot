package de.buerokratiecopilot.identity.auth;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The first-run wizard of the Kanzlei edition (§6.1): three steps, five minutes.
 *
 * <ol>
 *   <li><strong>Wer sind Sie?</strong> - the name of the office, and nothing else.</li>
 *   <li><strong>Ihre Mandanten</strong> - create clients, by hand or from a CSV file.</li>
 *   <li><strong>Erste Dokumente</strong> - drop the first documents in, or look at the demo cabinet.</li>
 * </ol>
 *
 * <p>Only step 1 needs the server: it renames the cabinet. Steps 2 and 3 use the ordinary APIs
 * ({@code /contacts}, {@code /contacts/import}, {@code /documents}), because the wizard is a sequence of
 * the product's real actions and not a second implementation of them. What is left here is the cabinet's
 * name and the fact that the wizard was finished.</p>
 *
 * <p><strong>Finishing is a property of the cabinet, not of a person.</strong> A colleague invited after
 * the office was set up must not be sent through the wizard again, so the answer lives in
 * {@code tenants.setup_completed_at}. The old flow kept one preference row per person, which made a
 * second person repeat what the first one had already done.</p>
 */
@Service
public class AccountSetupService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AccountSetupService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * What the wizard needs to know: the name it may keep, how many clients already exist, whether the
     * cabinet is set up, and whether this person is allowed to change the name.
     */
    public record State(String cabinetName, int mandantCount, boolean completed, boolean canEditCabinet) {
    }

    /** The name of the office, as the wizard and the account settings show it. */
    @Transactional(readOnly = true)
    public State state(UUID tenantId, boolean mayEditCabinet) {
        Integer mandants = jdbc.queryForObject("""
                select count(*) from contacts where tenant_id = ? and deleted_at is null
                """, Integer.class, tenantId);
        return new State(cabinetName(tenantId), mandants == null ? 0 : mandants, isCompleted(tenantId),
                mayEditCabinet);
    }

    /** Whether the cabinet has been through the wizard. */
    @Transactional(readOnly = true)
    public boolean isCompleted(UUID tenantId) {
        Boolean completed = jdbc.queryForObject("""
                select setup_completed_at is not null from tenants where id = ?
                """, Boolean.class, tenantId);
        return Boolean.TRUE.equals(completed);
    }

    /**
     * Step 1: the one mandatory text field of the wizard.
     *
     * <p>The name is the cabinet's name, so it is written to the tenant and to the default workspace -
     * the tenant is what {@code /auth/me} and the letterheads read, and a workspace that kept the old name
     * would show a second truth in the workspace switcher.</p>
     */
    @Transactional
    public State renameCabinet(UUID tenantId, UUID userId, String requestedName) {
        String name = requestedName == null ? "" : requestedName.strip();
        if (name.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Die Kanzlei braucht einen Namen");
        }
        if (name.length() > 200) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Name ist zu lang (höchstens 200 Zeichen)");
        }
        String previous = cabinetName(tenantId);
        jdbc.update("update tenants set name = ?, updated_at = now() where id = ?", name, tenantId);
        jdbc.update("update workspaces set name = ? where tenant_id = ? and is_default", name, tenantId);

        if (!name.equals(previous)) {
            audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.SETTINGS, "CABINET_RENAMED")
                    .tenant(tenantId)
                    .actor(AuditEvent.ActorType.USER, userId, null)
                    .entity("tenants", tenantId, name)
                    .description("Cabinet renamed")
                    .changes(Map.of("from", previous, "to", name))
                    .build());
        }
        return state(tenantId, true);
    }

    /**
     * Step 3 done: the cabinet is set up.
     *
     * <p>Idempotent, because the client calls it when the person leaves the wizard, and a second call (a
     * retried request, a double click) must not move the timestamp or write a second audit entry.</p>
     */
    @Transactional
    public void complete(UUID tenantId, UUID userId) {
        int updated = jdbc.update("""
                update tenants set setup_completed_at = now(), updated_at = now()
                where id = ? and setup_completed_at is null
                """, tenantId);
        if (updated > 0) {
            audit.record(AuditService.AuditEntry.builder(AuditEvent.Category.ADMIN, "CABINET_SETUP_COMPLETED")
                    .tenant(tenantId)
                    .actor(AuditEvent.ActorType.USER, userId, null)
                    .entity("tenants", tenantId, cabinetName(tenantId))
                    .description("First-run wizard finished")
                    .build());
        }
    }

    private String cabinetName(UUID tenantId) {
        return jdbc.queryForObject("select name from tenants where id = ?", String.class, tenantId);
    }
}
