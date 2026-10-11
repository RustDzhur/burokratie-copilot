package de.buerokratiecopilot.identity.onboarding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.accounts.ModuleCatalogue;
import de.buerokratiecopilot.identity.domain.User;
import de.buerokratiecopilot.identity.UserRepository;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Setting up an account for the first time (spec §3, A-018).
 *
 * <p>Two paths, and they are for different people.</p>
 *
 * <p><strong>Self-service onboarding</strong> is what a new customer does: they pick an account type, a
 * language and a time zone, and get a tenant, a workspace, an organisation and themselves as its owner.
 * The account type they choose determines the module defaults they start with, which they can change
 * afterwards — the platform does not decide what a business needs, it offers a starting point.</p>
 *
 * <p><strong>The administrator bootstrap</strong> is what an operator does on a fresh installation: it
 * creates the same structure and a one-time token that lets the first administrator set a password. It is
 * <em>idempotent by slug</em>: running it twice returns the account that exists rather than creating a
 * second one, because the second run is what happens when the first one's output was lost, not a request
 * for two installations.</p>
 *
 * <p>The token is a credential like every other here: shown once, stored hashed, single use, and with an
 * end date. An installation that nobody finishes setting up must not leave a permanent way in.</p>
 */
@Service
public class OnboardingService {

    private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

    /** How long a bootstrap token stays valid; an unfinished installation is not a way in forever. */
    private static final long BOOTSTRAP_TTL_HOURS = 48;

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public OnboardingService(JdbcTemplate jdbc, UserRepository users, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    /** What a caller provides. */
    public record Request(String slug, String accountName, String accountType, String adminEmail,
                          String adminDisplayName, String language, String timeZone, String countryCode,
                          String organizationName) {
    }

    /** What was created. */
    public record Account(UUID tenantId, UUID workspaceId, UUID organizationId, UUID adminUserId,
                          String accountType, String workspaceMode, boolean alreadyExisted) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("tenantId", tenantId.toString());
            map.put("workspaceId", workspaceId.toString());
            map.put("organizationId", organizationId.toString());
            map.put("adminUserId", adminUserId.toString());
            map.put("accountType", accountType);
            map.put("workspaceMode", workspaceMode);
            map.put("alreadyExisted", alreadyExisted);
            return map;
        }
    }

    /** The administrator bootstrap, with the token that exists only in this answer. */
    public record Bootstrap(Account account, String token, Instant expiresAt, boolean alreadyExisted) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>(account.asMap());
            map.put("token", token);
            map.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
            map.put("note", token == null ? null
                    : "Dieser Token wird nur einmal angezeigt; gespeichert wird nur sein Hash.");
            return map;
        }
    }

    /**
     * Onboards an account.
     *
     * <p>Creating the account and its administrator are one act: an account whose owner was not created
     * is an account nobody can administer, and creating it in two steps invites the state where the first
     * succeeded and the second did not.</p>
     */
    @Transactional
    public Account onboard(Request request) {
        String accountType = normalizeAccountType(request.accountType());
        String slug = requireSlug(request.slug());

        UUID existing = findTenantBySlug(slug);
        if (existing != null) {
            // The same slug means the same account; onboarding twice is a retry, not a second installation.
            return describe(existing, true);
        }

        UUID tenantId = createTenant(slug, request.accountName(), accountType);
        UUID organizationId = createOrganization(tenantId, request.organizationName() == null
                ? request.accountName() : request.organizationName(), request.countryCode());
        UUID workspaceId = createWorkspace(tenantId, organizationId,
                request.accountName() == null ? slug : request.accountName(), accountType);
        UUID adminUserId = createAdmin(request.adminEmail(), request.adminDisplayName(),
                request.language(), request.timeZone());
        createOwnerMembership(tenantId, workspaceId, adminUserId);

        audit(tenantId, adminUserId, "ACCOUNT_ONBOARDED",
                "{\"accountType\":\"" + accountType + "\",\"slug\":\"" + slug + "\"}");
        log.info("Account {} onboarded ({}), workspace {} for admin {}", tenantId, accountType, workspaceId,
                adminUserId);

        return new Account(tenantId, workspaceId, organizationId, adminUserId, accountType,
                workspaceModeFor(accountType), false);
    }

    /**
     * Bootstraps the first administrator of an installation.
     *
     * <p>Idempotent by slug: a second run hands back the account that exists, together with a fresh token,
     * rather than creating a second installation. That is what an operator needs when the first run's
     * output was lost — not a duplicate.</p>
     */
    @Transactional
    public Bootstrap bootstrapAdmin(Request request) {
        Account account = onboard(request);

        UUID existingUser = account.adminUserId();
        Instant expiresAt = Instant.now().plusSeconds(BOOTSTRAP_TTL_HOURS * 3600);
        String token = newToken();

        jdbc.update("""
                insert into one_time_tokens (id, user_id, purpose, token_hash, expires_at)
                values (gen_random_uuid(), ?, 'ADMIN_BOOTSTRAP', ?, ?)
                """, existingUser, hash(token), java.sql.Timestamp.from(expiresAt));

        log.info("Bootstrap token issued for admin {} of tenant {}", existingUser, account.tenantId());
        return new Bootstrap(account, token, expiresAt, account.alreadyExisted());
    }

    /**
     * Finishes a bootstrap: sets the first password and activates the administrator.
     *
     * <p>The token is consumed by the same statement that checks it, so two simultaneous attempts cannot
     * both use it — the reason a one-time token is one-time is that the second attempt is the attack.</p>
     */
    @Transactional
    public UUID completeBootstrap(String token, String password, String displayName) {
        if (password == null || password.length() < 12) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Das Passwort muss mindestens 12 Zeichen haben");
        }
        UUID userId = consumeBootstrapToken(token);
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("user"));

        user.setPasswordHash(passwordEncoder.encode(password));
        user.setStatus(User.Status.ACTIVE);
        user.setMustChangePassword(false);
        user.setPasswordChangedAt(Instant.now());
        if (displayName != null && !displayName.isBlank()) {
            user.setDisplayName(displayName.strip());
        }
        user.markEmailVerified(Instant.now());
        users.save(user);

        List<UUID> tenantIds = jdbc.query("""
                select tenant_id from memberships where user_id = ?
                """, (rs, row) -> rs.getObject("tenant_id", UUID.class), userId);
        tenantIds.forEach(tenantId -> audit(tenantId, userId, "ADMIN_BOOTSTRAP_COMPLETED", "{}"));

        log.info("Bootstrap completed for administrator {}", userId);
        return userId;
    }

    /** Whether an installation has an administrator who has finished setting up. */
    @Transactional(readOnly = true)
    public boolean hasFinishedAdministrator() {
        Integer count = jdbc.queryForObject("""
                select count(*) from memberships m join users u on u.id = m.user_id
                where m.is_owner = true and u.status = 'ACTIVE' and u.password_hash is not null
                """, Integer.class);
        return count != null && count > 0;
    }

    /** The account behind a slug, for an operator asking whether it exists. */
    @Transactional(readOnly = true)
    public Account describeBySlug(String slug) {
        UUID tenantId = findTenantBySlug(requireSlug(slug));
        if (tenantId == null) {
            throw ApiException.notFound("account");
        }
        return describe(tenantId, true);
    }

    // ------------------------------------------------------------------ internals

    private Account describe(UUID tenantId, boolean existing) {
        String accountType = jdbc.queryForObject("select account_type from tenants where id = ?",
                String.class, tenantId);
        List<UUID> workspaces = jdbc.query("""
                select id from workspaces where tenant_id = ? and is_default and deleted_at is null
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId);
        List<UUID> organizations = jdbc.query("""
                select id from organizations where tenant_id = ? and deleted_at is null
                order by created_at limit 1
                """, (rs, row) -> rs.getObject("id", UUID.class), tenantId);
        List<UUID> owners = jdbc.query("""
                select user_id from memberships where tenant_id = ? and is_owner = true
                order by joined_at limit 1
                """, (rs, row) -> rs.getObject("user_id", UUID.class), tenantId);

        return new Account(tenantId, workspaces.isEmpty() ? null : workspaces.getFirst(),
                organizations.isEmpty() ? null : organizations.getFirst(),
                owners.isEmpty() ? null : owners.getFirst(), accountType,
                workspaceModeFor(accountType), existing);
    }

    private UUID createTenant(String slug, String name, String accountType) {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type, status)
                values (gen_random_uuid(), ?, ?, ?, 'ACTIVE')
                returning id
                """, UUID.class, slug, name == null || name.isBlank() ? slug : name.strip(), accountType);
    }

    private UUID createOrganization(UUID tenantId, String name, String countryCode) {
        return jdbc.queryForObject("""
                insert into organizations (id, tenant_id, name, country_code, is_own_entity)
                values (gen_random_uuid(), ?, ?, ?, true)
                returning id
                """, UUID.class, tenantId, name.strip(),
                countryCode == null || countryCode.isBlank() ? "DE"
                        : countryCode.toUpperCase(java.util.Locale.ROOT));
    }

    private UUID createWorkspace(UUID tenantId, UUID organizationId, String name, String accountType) {
        return jdbc.queryForObject("""
                insert into workspaces (id, tenant_id, organization_id, name, mode, is_default, settings)
                values (gen_random_uuid(), ?, ?, ?, ?, true, '{}'::jsonb)
                returning id
                """, UUID.class, tenantId, organizationId, name.strip(), workspaceModeFor(accountType));
    }

    private UUID createAdmin(String email, String displayName, String language, String timeZone) {
        if (email == null || email.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Administrator braucht eine Adresse");
        }
        String normalized = email.strip().toLowerCase(java.util.Locale.ROOT);
        Integer existing = jdbc.queryForObject("select count(*) from users where lower(email) = ?",
                Integer.class, normalized);
        if (existing != null && existing > 0) {
            // The address already belongs to somebody; the caller has to choose another one rather than
            // silently taking over an account.
            throw ApiException.duplicate("Diese Adresse gehört bereits zu einem Benutzerkonto");
        }

        String shownName = displayName == null || displayName.isBlank() ? normalized : displayName.strip();
        User user = new User(UUID.randomUUID(), normalized, shownName);
        user.setLocale(language == null || language.isBlank() ? "de" : language.strip());
        user.setTimeZone(timeZone == null || timeZone.isBlank() ? "Europe/Berlin" : timeZone.strip());
        // No password yet: the account exists, and nobody can sign in with it until a password is set
        // through the one-time token.
        user.setStatus(User.Status.PENDING_VERIFICATION);
        user.setMustChangePassword(true);
        // Flushed, not merely saved: the membership below is inserted with plain JDBC, which runs
        // before Hibernate would have written the user, and the foreign key is checked immediately.
        return users.saveAndFlush(user).getId();
    }

    private void createOwnerMembership(UUID tenantId, UUID workspaceId, UUID userId) {
        jdbc.update("""
                insert into memberships (id, tenant_id, workspace_id, user_id, status, is_owner, joined_at)
                values (gen_random_uuid(), ?, ?, ?, 'ACTIVE', true, now())
                """, tenantId, workspaceId, userId);
    }

    private UUID consumeBootstrapToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Kein Token übergeben");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, user_id, consumed_at, expires_at from one_time_tokens
                where token_hash = ? and purpose = 'ADMIN_BOOTSTRAP'
                """, hash(token));
        if (rows.isEmpty()) {
            throw ApiException.notFound("bootstrap token");
        }
        Map<String, Object> row = rows.getFirst();
        if (row.get("consumed_at") != null) {
            throw ApiException.conflict("Dieser Token wurde bereits verwendet");
        }
        if (((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now())) {
            throw ApiException.conflict("Dieser Token ist abgelaufen");
        }

        // Consumed by the same statement that checks it, so two simultaneous attempts cannot both win.
        int consumed = jdbc.update("""
                update one_time_tokens set consumed_at = now()
                where id = ? and consumed_at is null
                """, row.get("id"));
        if (consumed == 0) {
            throw ApiException.conflict("Dieser Token wurde bereits verwendet");
        }
        return (UUID) row.get("user_id");
    }

    /**
     * The workspace mode that follows from an account type.
     *
     * <p>A default and not a rule: the workspace records it, and an account may have workspaces of other
     * modes later. It exists so that a new account starts with something sensible rather than with a
     * question.</p>
     */
    static String workspaceModeFor(String accountType) {
        return switch (accountType) {
            case "PRIVATE_PERSON" -> "PRIVATE";
            case "FAMILY_HOUSEHOLD" -> "FAMILY";
            case "EXTERNAL_REPRESENTATIVE" -> "ADVISOR";
            case "HIGH_SECURITY_ORGANIZATION" -> "PUBLIC_SECTOR";
            default -> "BUSINESS";
        };
    }

    private String normalizeAccountType(String accountType) {
        if (accountType == null || accountType.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Kontotyp ist erforderlich");
        }
        String normalized = accountType.toUpperCase(java.util.Locale.ROOT);
        if (!ModuleCatalogue.isKnownAccountType(normalized)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Kontotyp: " + accountType);
        }
        return normalized;
    }

    private String requireSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Kurzname ist erforderlich");
        }
        String normalized = slug.strip().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("^[a-z0-9]([a-z0-9-]{1,78}[a-z0-9])$")) {
            // The schema has the same rule; saying it here means the caller gets a sentence rather than a
            // constraint violation.
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Der Kurzname muss mit einem Buchstaben oder einer Ziffer beginnen und enden und darf "
                            + "Bindestriche enthalten");
        }
        return normalized;
    }

    private UUID findTenantBySlug(String slug) {
        List<UUID> found = jdbc.query("""
                select id from tenants where slug = ?
                """, (rs, row) -> rs.getObject("id", UUID.class), slug);
        return found.isEmpty() ? null : found.getFirst();
    }

    private void audit(UUID tenantId, UUID actorUserId, String action, String changes) {
        jdbc.update("""
                insert into audit_events (id, tenant_id, actor_user_id, actor_type, category, action,
                                          entity_type, entity_id, description, changes, result, severity)
                values (gen_random_uuid(), ?, ?, 'USER', 'ADMIN', ?, 'tenant', ?, ?, ?::jsonb, 'SUCCESS',
                        'NOTICE')
                """, tenantId, actorUserId, action, tenantId, action, changes);
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return "bcboot_" + HexFormat.of().formatHex(bytes);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 ist nicht verfügbar", ex);
        }
    }
}
