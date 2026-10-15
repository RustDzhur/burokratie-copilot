package de.buerokratiecopilot.demo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import de.buerokratiecopilot.billing.TenantPurgeService;
import de.buerokratiecopilot.documents.DocumentService;
import de.buerokratiecopilot.documents.domain.Document;
import de.buerokratiecopilot.documents.pipeline.DocumentProcessingPipeline;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.identity.auth.SessionService;
import de.buerokratiecopilot.orchestration.CaseOrchestrator;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * The demo cabinet: a visitor picks what they would use the platform for and enters, without a password, a cabinet of their own
 * that is already filled with invented letters, tasks, contracts and invoices.
 *
 * <p>Every visitor gets a workspace of their own (a tenant created for them) so that two visitors never see or spoil each
 * other's cabinet, and so that "back to how it was" is exact: when the visitor leaves, or after a few hours, the whole workspace
 * is deleted. Nothing in it can leave the platform: mail, integrations and payments are closed ({@link DemoGuard}), and the
 * letters are read by the platform's own rules, with no external AI.</p>
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    static final String EMAIL_DOMAIN = "demo.invalid";
    /** How long a demo cabinet lives at most, whether or not the visitor leaves. */
    static final long LIFETIME_HOURS = 3;
    static final int MAX_CABINETS = 60;
    static final int MAX_STARTS_PER_IP_PER_HOUR = 6;

    /** The client companies of the advice-centre demo, each with a few letters of its own. */
    private static final List<Map.Entry<String, List<String>>> ADVISOR_CLIENTS = List.of(
            Map.entry("Müller Bau GmbH", List.of("handwerker-rechnung", "mahnung", "angebot")),
            Map.entry("Schmidt Physiotherapie", List.of("software-rechnung", "ihk", "zahlungserinnerung")),
            Map.entry("Weber Café UG", List.of("strom-rechnung", "vertrag-internet", "preiserhoehung")));

    public record Started(String sessionToken, UUID tenantId, String persona) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final DemoSamples samples;
    private final DemoGuard guard;
    private final DocumentService documents;
    private final DocumentProcessingPipeline pipeline;
    private final CaseOrchestrator orchestrator;
    private final SessionService sessions;
    private final UserRepository users;
    private final TenantPurgeService purge;
    private final Map<String, Deque<Long>> startsByIp = new ConcurrentHashMap<>();

    public DemoService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, DemoSamples samples, DemoGuard guard,
                       DocumentService documents, DocumentProcessingPipeline pipeline, CaseOrchestrator orchestrator,
                       SessionService sessions, UserRepository users, TenantPurgeService purge) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.samples = samples;
        this.guard = guard;
        this.documents = documents;
        this.pipeline = pipeline;
        this.orchestrator = orchestrator;
        this.sessions = sessions;
        this.users = users;
        this.purge = purge;
    }

    /** Opens a demo cabinet for the chosen purpose and signs the visitor into it. */
    public Started start(String personaName, String ip, String userAgent) {
        Persona persona = Persona.parse(personaName);
        limitStarts(ip);
        Integer open = RequestContext.runAs((UUID) null, () -> jdbc.queryForObject(
                "select count(*) from tenants where settings ->> 'demo' = 'true'", Integer.class));
        if (open != null && open >= MAX_CABINETS) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Die Demo ist gerade ausgelastet. Bitte versuchen Sie es in einigen Minuten erneut.");
        }

        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String name = "Erika Mustermann";
        createUser(userId, name);
        createWorkspace(tenantId, persona.workspaceName(), persona.accountType(), persona.workspaceMode(), persona.planCode(), userId);
        try {
            RequestContext.runAs(tenantId, () -> {
                RequestContext.setUserId(userId);
                fill(persona, tenantId, userId);
                return null;
            });
            if (persona == Persona.ADVISOR) {
                for (Map.Entry<String, List<String>> client : ADVISOR_CLIENTS) {
                    UUID clientTenant = UUID.randomUUID();
                    createWorkspace(clientTenant, client.getKey(), "COMPANY", "BUSINESS", persona.planCode(), userId);
                    RequestContext.runAs(clientTenant, () -> {
                        RequestContext.setUserId(userId);
                        for (String id : client.getValue()) {
                            file(samples.byId(id));
                        }
                        extras(Persona.COMPANY, clientTenant, userId, false);
                        return null;
                    });
                }
            }
        }
        catch (RuntimeException ex) {
            // A cabinet that could not be filled is not left behind half-built.
            log.warn("Demo cabinet could not be built: {}", ex.getMessage(), ex);
            exitAll(userId);
            throw ex;
        }

        var user = RequestContext.runAs((UUID) null, () -> users.findById(userId).orElseThrow());
        SessionService.IssuedSession issued = RequestContext.runAs((UUID) null,
                () -> sessions.issue(user, tenantId, true, ip, userAgent));
        log.info("Demo cabinet {} opened for {}", tenantId, persona);
        return new Started(issued.rawToken(), tenantId, persona.name());
    }

    /** What the interface needs to know about a demo cabinet; empty when the workspace is not one. */
    public Map<String, Object> status(UUID tenantId) {
        if (!guard.isDemo(tenantId)) {
            return Map.of("demo", false);
        }
        Instant created = RequestContext.runAs((UUID) null, () -> jdbc.queryForObject(
                "select created_at from tenants where id = ?", java.sql.Timestamp.class, tenantId)).toInstant();
        String persona = RequestContext.runAs((UUID) null, () -> jdbc.queryForObject(
                "select settings ->> 'demoPersona' from tenants where id = ?", String.class, tenantId));
        return Map.of("demo", true, "persona", persona == null ? "" : persona,
                "expiresAt", created.plusSeconds(LIFETIME_HOURS * 3600).toString());
    }

    /** Ends the demo of this person: every workspace and the person themselves are deleted, as if they had never been here. */
    public void exitAll(UUID userId) {
        List<UUID> tenants = RequestContext.runAs((UUID) null, () -> jdbc.queryForList("""
                select m.tenant_id from memberships m join tenants t on t.id = m.tenant_id
                where m.user_id = ? and t.settings ->> 'demo' = 'true'
                """, UUID.class, userId));
        for (UUID tenant : tenants) {
            deleteCabinet(tenant);
        }
        RequestContext.runAs((UUID) null, () -> jdbc.update("""
                delete from users where id = ? and email like ? and not exists (select 1 from memberships where user_id = ?)
                """, userId, "%@" + EMAIL_DOMAIN, userId));
    }

    /** Deletes demo cabinets older than their lifetime; run regularly. */
    public int purgeExpired() {
        List<UUID> expired = RequestContext.runAs((UUID) null, () -> jdbc.queryForList("""
                select id from tenants where settings ->> 'demo' = 'true'
                  and created_at < now() - make_interval(hours => ?)
                """, UUID.class, (int) LIFETIME_HOURS));
        expired.forEach(this::deleteCabinet);
        if (!expired.isEmpty()) {
            RequestContext.runAs((UUID) null, () -> jdbc.update("""
                    delete from users where email like ? and not exists (select 1 from memberships where user_id = users.id)
                    """, "%@" + EMAIL_DOMAIN));
            log.info("Deleted {} expired demo cabinet(s)", expired.size());
        }
        return expired.size();
    }

    private void deleteCabinet(UUID tenantId) {
        try {
            RequestContext.runAs((UUID) null, () -> purge.purge(tenantId));
        }
        catch (RuntimeException ex) {
            log.warn("Demo cabinet {} could not be deleted: {}", tenantId, ex.getMessage());
        }
        finally {
            guard.forget(tenantId);
        }
    }

    // ------------------------------------------------------------------------------------------------- building a cabinet

    private void limitStarts(String ip) {
        Deque<Long> times = startsByIp.computeIfAbsent(ip == null ? "unknown" : ip, key -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 3_600_000) {
                times.pollFirst();
            }
            if (times.size() >= MAX_STARTS_PER_IP_PER_HOUR) {
                throw new ApiException(ErrorCode.RATE_LIMITED, "Zu viele Demo-Starts von dieser Adresse. Bitte später erneut versuchen.");
            }
            times.addLast(now);
        }
    }

    private void createUser(UUID userId, String displayName) {
        String email = "demo-" + userId.toString().substring(0, 12) + "@" + EMAIL_DOMAIN;
        // No password hash: nobody can sign in to this account; the only way in is the session issued by the demo button.
        RequestContext.runAs((UUID) null, () -> jdbc.update("""
                insert into users (id, email, display_name, status, email_verified_at, locale) values (?, ?, ?, 'ACTIVE', now(), 'de')
                """, userId, email, displayName));
    }

    private void createWorkspace(UUID tenantId, String name, String accountType, String mode, String plan, UUID ownerId) {
        String slug = "demo-" + tenantId.toString().substring(0, 12);
        RequestContext.runAs(tenantId, () -> transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into tenants (id, slug, name, account_type, settings)
                    values (?, ?, ?, ?, jsonb_build_object('demo', true, 'demoPersona', ?::text))
                    """, tenantId, slug, name, accountType, plan);
            UUID workspaceId = jdbc.queryForObject("""
                    insert into workspaces (tenant_id, name, mode, is_default) values (?, ?, ?, true) returning id
                    """, UUID.class, tenantId, name, mode);
            UUID membershipId = jdbc.queryForObject("""
                    insert into memberships (tenant_id, user_id, workspace_id, status, is_owner, joined_at)
                    values (?, ?, ?, 'ACTIVE', true, now()) returning id
                    """, UUID.class, tenantId, ownerId, workspaceId);
            jdbc.update("""
                    insert into membership_roles (membership_id, role_id, granted_at)
                    select ?, r.id, now() from roles r where r.code = 'OWNER' and r.tenant_id is null
                    """, membershipId);
            jdbc.update("""
                    insert into subscriptions (tenant_id, plan_code, status, access_until)
                    values (?, ?, 'ACTIVE', now() + make_interval(hours => ?))
                    """, tenantId, plan, (int) LIFETIME_HOURS + 1);
        }));
    }

    private void fill(Persona persona, UUID tenantId, UUID userId) {
        // The persona's name marks the cabinet for the interface (tour and banner).
        jdbc.update("update tenants set settings = jsonb_set(settings, '{demoPersona}', to_jsonb(?::text)) where id = ?",
                persona.name(), tenantId);
        if (persona == Persona.FAMILY) {
            jdbc.update("""
                    insert into parties (tenant_id, kind, display_name, relation, is_primary) values
                      (?, 'PERSON', 'Erika Mustermann', 'SELF', true),
                      (?, 'PERSON', 'Max Mustermann', 'PARTNER', false),
                      (?, 'PERSON', 'Lena Mustermann', 'CHILD', false)
                    """, tenantId, tenantId, tenantId);
        }
        for (DemoSamples.Sample sample : samples.forPersona(persona)) {
            file(sample);
        }
        extras(persona, tenantId, userId, true);
    }

    /** Puts one letter through the platform's own path: upload, reading, sorting, and the work that follows from it. */
    private void file(DemoSamples.Sample sample) {
        String text = DemoSamples.resolve(sample.text(), LocalDate.now());
        var received = documents.receive(new DocumentService.UploadRequest(
                new InMemoryFile(sample.filename(), text.getBytes(StandardCharsets.UTF_8)), null, null, Document.Source.UPLOAD, null));
        UUID id = received.document().getId();
        pipeline.advance(id);
        try {
            orchestrator.orchestrate(id);
        }
        catch (RuntimeException ex) {
            // A letter that waits for a person's check has no task yet: that is how the platform works, not an error.
            log.debug("No work created for demo letter {}: {}", sample.id(), ex.getMessage());
        }
    }

    /** Contacts, contracts, invoices and colleagues that make the cabinet look lived in. */
    private void extras(Persona persona, UUID tenantId, UUID userId, boolean withColleagues) {
        jdbc.update("""
                insert into contacts (tenant_id, contact_type, display_name, organization_name, is_supplier) values
                  (?, 'ORGANIZATION', 'Stadtwerke Musterstadt GmbH', 'Stadtwerke Musterstadt GmbH', true),
                  (?, 'ORGANIZATION', 'Beispiel Telekom GmbH', 'Beispiel Telekom GmbH', true)
                """, tenantId, tenantId);
        jdbc.update("""
                insert into contracts (tenant_id, title, contract_type, status, start_date, end_date, auto_renewal)
                values (?, 'Internetanschluss', 'SERVICE', 'ACTIVE', current_date - 600, current_date + 75, true),
                       (?, 'Stromliefervertrag', 'SERVICE', 'ACTIVE', current_date - 300, current_date + 420, false)
                """, tenantId, tenantId);
        if (persona == Persona.FREELANCER || persona == Persona.COMPANY || persona == Persona.ADVISOR) {
            jdbc.update("""
                    insert into customers (tenant_id, customer_number, name) values (?, 'K-1001', 'Beispiel Handels GmbH'), (?, 'K-1002', 'Muster Consulting AG')
                    """, tenantId, tenantId);
            invoice(tenantId, "OUTGOING", "A-2026-021", "PAID", "PAID", -60, -46, "1190.00", "1190.00");
            invoice(tenantId, "OUTGOING", "A-2026-024", "PAID", "PAID", -35, -21, "2380.00", "2380.00");
            invoice(tenantId, "OUTGOING", "A-2026-027", "APPROVED", "UNPAID", -10, 4, "1785.00", "0");
            invoice(tenantId, "OUTGOING", "A-2026-019", "OVERDUE", "UNPAID", -50, -20, "595.00", "0");
            invoice(tenantId, "INCOMING", "ER-88-2026-33", "APPROVED", "UNPAID", -5, 11, "1428.00", "0");
            invoice(tenantId, "INCOMING", "RE-2026-0412", "RECEIVED", "UNPAID", -3, 9, "96.00", "0");
        }
        if (withColleagues && (persona == Persona.COMPANY || persona == Persona.ADVISOR)) {
            colleague(tenantId, "Jonas Weber", "ACCOUNTANT");
            colleague(tenantId, "Mira Schmidt", "EMPLOYEE");
            assignOpenTasks(tenantId);
        }
    }

    private void invoice(UUID tenantId, String direction, String number, String status, String paymentStatus, int issueDays,
                         int dueDays, String gross, String paid) {
        jdbc.update("""
                insert into invoices (tenant_id, direction, invoice_number, status, payment_status, issue_date, due_date,
                                      gross_amount, paid_amount, source)
                values (?, ?, ?, ?, ?, current_date + ?, current_date + ?, ?::numeric, ?::numeric, 'MANUAL')
                """, tenantId, direction, number, status, paymentStatus, issueDays, dueDays, gross, paid);
    }

    private void colleague(UUID tenantId, String name, String role) {
        UUID id = UUID.randomUUID();
        RequestContext.runAs((UUID) null, () -> jdbc.update("""
                insert into users (id, email, display_name, status, email_verified_at) values (?, ?, ?, 'ACTIVE', now())
                """, id, "demo-" + id.toString().substring(0, 12) + "@" + EMAIL_DOMAIN, name));
        UUID membershipId = jdbc.queryForObject("""
                insert into memberships (tenant_id, user_id, status, is_owner, joined_at) values (?, ?, 'ACTIVE', false, now())
                returning id
                """, UUID.class, tenantId, id);
        jdbc.update("""
                insert into membership_roles (membership_id, role_id, granted_at)
                select ?, r.id, now() from roles r where r.code = ? and r.tenant_id is null
                """, membershipId, role);
    }

    private void assignOpenTasks(UUID tenantId) {
        List<UUID> colleagues = jdbc.queryForList("""
                select m.user_id from memberships m where m.tenant_id = ? and not m.is_owner order by m.created_at
                """, UUID.class, tenantId);
        List<UUID> tasks = jdbc.queryForList("""
                select id from tasks where tenant_id = ? and deleted_at is null and status not in ('DONE', 'CANCELLED')
                order by created_at limit 6
                """, UUID.class, tenantId);
        for (int i = 0; i < tasks.size() && !colleagues.isEmpty(); i++) {
            jdbc.update("insert into task_assignees (task_id, tenant_id, user_id) values (?, ?, ?) on conflict do nothing",
                    tasks.get(i), tenantId, colleagues.get(i % colleagues.size()));
            jdbc.update("update tasks set assignment_pending = false where id = ?", tasks.get(i));
        }
    }

    /** A file held in memory, so that a sample letter goes through the same upload path as a real file. */
    private record InMemoryFile(String name, byte[] bytes) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public String getContentType() {
            return "text/plain";
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException {
            java.nio.file.Files.write(dest.toPath(), bytes);
        }
    }
}
