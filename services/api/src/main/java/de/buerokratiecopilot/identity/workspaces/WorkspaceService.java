package de.buerokratiecopilot.identity.workspaces;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import de.buerokratiecopilot.audit.AuditService;
import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.billing.PlanFeatures;
import de.buerokratiecopilot.identity.auth.SessionService;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Several separate workspaces for one person: an accountant with ten client companies has ten workspaces, each with its own
 * documents, tasks, mailboxes and settings, and switches between them. Nothing is shared between them: a workspace is a
 * tenant, and the database refuses to show a row of one to another.
 *
 * <p>A person sees the workspaces they hold an active membership in, and only those. Creating a client workspace is for the
 * owner of a workspace on the Professional plan, and the new workspace gets the same plan and term, so a client of an
 * accountant has the same features as the accountant's own.</p>
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    /** The Professional tier: the only one that may hold client workspaces. */
    static final int PROFESSIONAL_TIER = 3;
    /** A technical safety cap on client workspaces created by one person; not a price. */
    static final int MAX_CREATED_PER_PERSON = 100;

    public record Workspace(UUID tenantId, String name, String accountType, boolean owner, String planCode, boolean current) {
    }

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final PlanFeatures plans;
    private final AuditService audit;
    private final TransactionTemplate transaction;

    public WorkspaceService(JdbcTemplate jdbc, SessionService sessions, PlanFeatures plans, AuditService audit,
                            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.plans = plans;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The workspaces of this person. The lookup runs without a tenant scope because it has to cross tenants, and for that reason
     * it is limited to the person's own memberships by the query itself.
     */
    public List<Workspace> list(UUID userId, UUID currentTenantId) {
        return RequestContext.runAs((UUID) null, () -> jdbc.query("""
                select t.id, coalesce(w.name, t.name) as name, t.account_type, m.is_owner, s.plan_code
                from memberships m
                join tenants t on t.id = m.tenant_id and t.deleted_at is null and t.status = 'ACTIVE'
                left join workspaces w on w.tenant_id = t.id and w.is_default and w.deleted_at is null
                left join subscriptions s on s.tenant_id = t.id
                where m.user_id = ? and m.status = 'ACTIVE' and m.revoked_at is null
                  and (m.access_valid_from is null or m.access_valid_from <= now())
                  and (m.access_valid_until is null or m.access_valid_until > now())
                order by lower(coalesce(w.name, t.name))
                """, (rs, row) -> new Workspace(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("account_type"), rs.getBoolean("is_owner"), rs.getString("plan_code"),
                rs.getObject("id", UUID.class).equals(currentTenantId)), userId));
    }

    /** Moves the session into another workspace of the same person. */
    public void switchTo(UUID sessionId, UUID userId, UUID targetTenantId) {
        Workspace target = list(userId, null).stream().filter(w -> w.tenantId().equals(targetTenantId)).findFirst()
                // The same answer for "does not exist" and "is not yours": nobody learns which workspaces exist.
                .orElseThrow(() -> ApiException.notFound("Workspace"));
        // The session row moves from one tenant to another, which a scope bound to either tenant would refuse.
        RequestContext.runAs((UUID) null, () -> sessions.switchTenant(sessionId, target.tenantId()));
        RequestContext.runAs(target.tenantId(), () -> audit.recordSuccess(AuditEvent.Category.AUTHENTICATION,
                "WORKSPACE_SWITCHED", "The person switched into this workspace"));
    }

    /** Creates a client workspace, owned by the person, with the plan and term of the workspace they are working in. */
    public Workspace createClient(UUID userId, UUID currentTenantId, String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.length() < 2 || clean.length() > 120) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The name of the workspace needs 2 to 120 characters");
        }
        Boolean owner = jdbc.queryForObject("select exists (select 1 from memberships where tenant_id = ? and user_id = ? "
                + "and status = 'ACTIVE' and is_owner)", Boolean.class, currentTenantId, userId);
        if (!Boolean.TRUE.equals(owner)) {
            throw ApiException.forbidden("Nur der Inhaber des Arbeitsbereichs kann Mandanten anlegen");
        }
        if (plans.tierOf(currentTenantId) < PROFESSIONAL_TIER) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Mandanten anzulegen gehört zum Tarif Professional");
        }
        Integer created = RequestContext.runAs((UUID) null, () -> jdbc.queryForObject("""
                select count(*) from memberships m join tenants t on t.id = m.tenant_id
                where m.user_id = ? and m.is_owner and t.settings ->> 'createdAsClientBy' = ?
                """, Integer.class, userId, userId.toString()));
        if (created != null && created >= MAX_CREATED_PER_PERSON) {
            throw ApiException.conflict("Die Obergrenze für angelegte Mandanten ist erreicht");
        }

        // Read in the scope of the workspace the person works in; the new workspace's scope cannot see it.
        Map<String, Object> plan = jdbc.queryForMap(
                "select plan_code, status, trial_ends_at, paid_until, access_until from subscriptions where tenant_id = ?",
                currentTenantId);
        UUID tenantId = UUID.randomUUID();
        String slug = slugOf(clean);
        // Everything of the new workspace is written in its own scope, in one transaction of its own: a half-created
        // client workspace cannot exist.
        RequestContext.runAs(tenantId, () -> transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into tenants (id, slug, name, account_type, settings)
                    values (?, ?, ?, 'COMPANY', jsonb_build_object('createdAsClientBy', ?::text))
                    """, tenantId, slug, clean, userId.toString());
            UUID workspaceId = jdbc.queryForObject("""
                    insert into workspaces (tenant_id, name, mode, is_default) values (?, ?, 'BUSINESS', true) returning id
                    """, UUID.class, tenantId, clean);
            UUID membershipId = jdbc.queryForObject("""
                    insert into memberships (tenant_id, user_id, workspace_id, status, is_owner, joined_at)
                    values (?, ?, ?, 'ACTIVE', true, now()) returning id
                    """, UUID.class, tenantId, userId, workspaceId);
            jdbc.update("""
                    insert into membership_roles (membership_id, role_id, granted_by, granted_at)
                    select ?, r.id, ?, now() from roles r where r.code = 'OWNER' and r.tenant_id is null
                    """, membershipId, userId);
            // The plan and the term of the accountant's own workspace.
            jdbc.update("""
                    insert into subscriptions (tenant_id, plan_code, status, trial_ends_at, paid_until, access_until)
                    values (?, ?, ?, ?, ?, ?)
                    """, tenantId, plan.get("plan_code"), plan.get("status"), plan.get("trial_ends_at"),
                    plan.get("paid_until"), plan.get("access_until"));
        }));
        RequestContext.runAs(tenantId, () -> audit.recordSuccess(AuditEvent.Category.AUTHENTICATION,
                "CLIENT_WORKSPACE_CREATED", "Client workspace created by one of its owners"));
        log.info("Client workspace {} created by user {} from workspace {}", tenantId, userId, currentTenantId);
        return list(userId, currentTenantId).stream().filter(w -> w.tenantId().equals(tenantId)).findFirst().orElseThrow();
    }

    /** What the interface needs to decide whether to offer "new client": the right and the reason when it is missing. */
    public Map<String, Object> capabilities(UUID userId, UUID currentTenantId) {
        boolean professional = currentTenantId != null && plans.tierOf(currentTenantId) >= PROFESSIONAL_TIER;
        Boolean owner = currentTenantId == null ? false : jdbc.queryForObject(
                "select exists (select 1 from memberships where tenant_id = ? and user_id = ? and status = 'ACTIVE' and is_owner)",
                Boolean.class, currentTenantId, userId);
        return Map.of("canCreateClients", professional && Boolean.TRUE.equals(owner), "professionalPlan", professional);
    }

    static String slugOf(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace("ß", "ss").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (ascii.length() > 50) {
            ascii = ascii.substring(0, 50).replaceAll("-+$", "");
        }
        if (ascii.length() < 2) {
            ascii = "mandant";
        }
        return ascii + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
