package de.buerokratiecopilot.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import de.buerokratiecopilot.ingest.IngestService;
import de.buerokratiecopilot.license.LicenseService;
import de.buerokratiecopilot.shared.config.BurokratieProperties;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * First-run provisioning and token administration for an API-only runtime installation.
 *
 * <p>The installer must not insert account rows with {@code psql}: passwords, audit rows,
 * memberships and token hashes are the application's business, and a hand-written row can quietly
 * violate one of their rules. This service uses the same tables and the same
 * {@link IngestService} that the full product uses, inside one transaction, behind a secret that
 * only the installer knows.</p>
 *
 * <p>The bootstrap is deliberately one-shot: if a tenant exists the call answers 409. A lost
 * first token is not a reason to create a second owner — the operator uses
 * {@code POST /api/v1/runtime/tokens} with the same admin secret, which is auditable and does not
 * duplicate the account.</p>
 */
@Service
public class RuntimeAdminService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeAdminService.class);

    /** The scopes the installer grants to the first bridge token. */
    public static final List<String> FIRST_TOKEN_SCOPES =
            List.of("agents.view", "agents.run", "documents:write");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final long BOOTSTRAP_LOCK = 781_236_541L;

    private final JdbcTemplate jdbc;
    private final IngestService tokens;
    private final RuntimeProperties properties;
    private final BurokratieProperties appProperties;
    private final LicenseService license;
    private final Environment environment;
    private final TransactionTemplate transactions;

    public RuntimeAdminService(JdbcTemplate jdbc, IngestService tokens,
                               RuntimeProperties properties, BurokratieProperties appProperties,
                               LicenseService license, Environment environment,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.properties = properties;
        this.appProperties = appProperties;
        this.license = license;
        this.environment = environment;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record BootstrapResult(UUID tenantId, UUID workspaceId, UUID ownerUserId,
                                  String ownerEmail, UUID tokenId, String token, String prefix,
                                  List<String> scopes, int rateLimitPerMinute, String apiBase) {
    }

    public record AdminTokenResult(UUID tokenId, String token, String prefix,
                                   List<String> scopes, int rateLimitPerMinute) {
    }

    /**
     * Authenticates the operator secret from {@code X-Runtime-Admin-Token}.
     *
     * <p>Both sides are hashed before the constant-time comparison. Comparing the raw strings
     * would leak the token length, and comparing them with {@code equals} would leak the position
     * of the first difference.</p>
     */
    public void requireAdmin(String presented) {
        String configured = properties.getAdminToken();
        if (configured == null || configured.isBlank()) {
            // The endpoint exists but has no key: hide it rather than answer "forbidden", which
            // would tell a scanner that the installation has an unconfigured admin surface.
            throw new ApiException(ErrorCode.NOT_FOUND, "Runtime administration is not configured");
        }
        if (presented == null || presented.isBlank()
                || !MessageDigest.isEqual(sha256(configured), sha256(presented.strip()))) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Runtime admin token is invalid");
        }
    }

    /** Creates tenant, default workspace, owner and the first API token in one transaction. */
    public BootstrapResult bootstrap(String tenantName, String ownerEmail, String ownerName) {
        String email = normalizeEmail(ownerEmail);
        String tenant = tenantName == null || tenantName.isBlank()
                ? "Firmspace Robots Runtime" : tenantName.strip();
        String displayName = ownerName == null || ownerName.isBlank() ? email : ownerName.strip();

        BootstrapResult result = transactions.execute(status -> {
            // Two installers running at once must not both see "zero tenants" and both create
            // one. The advisory lock is transaction-scoped, so it disappears if anything fails.
            jdbc.execute("select pg_advisory_xact_lock(" + BOOTSTRAP_LOCK + ")");
            Integer existing = jdbc.queryForObject(
                    "select count(*) from tenants where deleted_at is null", Integer.class);
            if (existing != null && existing > 0) {
                throw new ApiException(ErrorCode.CONFLICT,
                        "This runtime installation is already bootstrapped; issue another token "
                                + "with POST /api/v1/runtime/tokens");
            }

            UUID tenantId = UUID.randomUUID();
            UUID workspaceId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            String slug = "runtime-" + tenantId.toString().replace("-", "").substring(0, 12);

            jdbc.update("""
                    insert into tenants (id, slug, name, account_type, status, default_locale,
                                         default_time_zone)
                    values (?, ?, ?, 'COMPANY', 'ACTIVE', 'de', 'Europe/Berlin')
                    """, tenantId, slug, tenant);

            jdbc.update("""
                    insert into users (id, email, password_hash, status, display_name, locale,
                                       time_zone, email_verified_at)
                    values (?, ?, null, 'ACTIVE', ?, 'de', 'Europe/Berlin', now())
                    """, userId, email, displayName);

            jdbc.update("""
                    insert into workspaces (id, tenant_id, name, mode, is_default)
                    values (?, ?, ?, 'BUSINESS', true)
                    """, workspaceId, tenantId, "Runtime");

            UUID membershipId = UUID.randomUUID();
            jdbc.update("""
                    insert into memberships (id, tenant_id, user_id, workspace_id, status, is_owner,
                                             joined_at)
                    values (?, ?, ?, ?, 'ACTIVE', true, now())
                    """, membershipId, tenantId, userId, workspaceId);

            UUID ownerRole = jdbc.queryForObject("""
                    select id from roles where tenant_id is null and code = 'OWNER'
                    """, UUID.class);
            jdbc.update("""
                    insert into membership_roles (membership_id, role_id, granted_by)
                    values (?, ?, ?)
                    """, membershipId, ownerRole, userId);

            jdbc.update("""
                    insert into audit_events (tenant_id, actor_user_id, actor_type, category, action,
                                              entity_type, entity_id, entity_label, description,
                                              result, severity)
                    values (?, ?, 'SYSTEM', 'ADMIN', 'RUNTIME_BOOTSTRAPPED', 'tenants', ?, ?,
                            'API-only runtime installation provisioned by the installer',
                            'SUCCESS', 'NOTICE')
                    """, tenantId, userId, tenantId, tenant);

            // The token belongs to the owner and the default workspace. IngestService stores
            // only its hash and returns the plaintext exactly once.
            IngestService.IssuedToken issued = RequestContext.runAs(tenantId,
                    () -> tokens.issueToken(tenantId, workspaceId, "runtime-bootstrap",
                            FIRST_TOKEN_SCOPES, 120, null, userId));

            log.info("Runtime bootstrapped: tenant {}, workspace {}, owner {}", tenantId,
                    workspaceId, userId);
            return new BootstrapResult(tenantId, workspaceId, userId, email, issued.tokenId(),
                    issued.token(), issued.prefix(), issued.scopes(),
                    issued.rateLimitPerMinute(), apiBase());
        });
        if (result == null) {
            throw new IllegalStateException("The runtime bootstrap transaction returned no result");
        }
        return result;
    }

    /** The single tenant of a runtime installation (the admin API supports exactly one). */
    public UUID soleTenant() {
        List<UUID> tenants = jdbc.queryForList("""
                select id from tenants where deleted_at is null order by created_at limit 2
                """, UUID.class);
        if (tenants.isEmpty()) {
            throw ApiException.conflict(
                    "This runtime installation is not bootstrapped yet; call /api/v1/runtime/bootstrap");
        }
        if (tenants.size() > 1) {
            throw ApiException.conflict(
                    "The runtime admin API supports a single-tenant installation; use the full product API");
        }
        return tenants.getFirst();
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        Integer tenantCount = jdbc.queryForObject(
                "select count(*) from tenants where deleted_at is null", Integer.class);
        int count = tenantCount == null ? 0 : tenantCount;
        body.put("bootstrapped", count > 0);
        body.put("tenantCount", count);
        if (count == 1) {
            UUID tenantId = jdbc.queryForObject("""
                    select id from tenants where deleted_at is null order by created_at limit 1
                    """, UUID.class);
            body.put("tenantId", tenantId);
            body.put("workspaceId", defaultWorkspace(tenantId));
            body.put("ownerEmail", ownerEmail(tenantId));
            body.put("activeTokens", jdbc.queryForObject("""
                    select count(*) from api_tokens where tenant_id = ? and is_active
                    """, Integer.class, tenantId));
        }
        body.put("license", license.status());
        body.put("version", version());
        body.put("adminTokenConfigured", properties.getAdminToken() != null
                && !properties.getAdminToken().isBlank());
        return body;
    }

    /**
     * The application version, shown on the admin page so an operator can quote it in a ticket.
     *
     * <p>It comes from {@code info.app.version}, which Maven fills from the project version when
     * resource filtering runs. A literal placeholder means the process was started from an IDE
     * without filtering; "dev" is more honest than showing {@code @project.version@}.</p>
     */
    private String version() {
        String version = environment.getProperty("info.app.version", "dev");
        if (version == null || version.isBlank() || version.startsWith("@")
                || version.contains("${")) {
            return "dev";
        }
        return version;
    }

    public List<IngestService.TokenSummary> listTokens() {
        UUID tenantId = soleTenant();
        return RequestContext.runAs(tenantId, () -> tokens.listTokens(tenantId));
    }

    public AdminTokenResult issueToken(String name, List<String> scopes) {
        UUID tenantId = soleTenant();
        UUID workspaceId = defaultWorkspace(tenantId);
        UUID owner = ownerUser(tenantId);
        String tokenName = name == null || name.isBlank() ? "runtime-admin" : name.strip();
        List<String> requested = scopes == null || scopes.isEmpty()
                ? FIRST_TOKEN_SCOPES : scopes;
        IngestService.IssuedToken issued = RequestContext.runAs(tenantId,
                () -> tokens.issueToken(tenantId, workspaceId, tokenName, requested, 120,
                        null, owner));
        return new AdminTokenResult(issued.tokenId(), issued.token(), issued.prefix(),
                issued.scopes(), issued.rateLimitPerMinute());
    }

    public void revokeToken(UUID tokenId) {
        UUID tenantId = soleTenant();
        UUID owner = ownerUser(tenantId);
        RequestContext.runAs(tenantId, () -> {
            tokens.revokeToken(tenantId, tokenId, owner);
            return null;
        });
    }

    public String apiBase() {
        String configured = appProperties.getSecurity().getPublicBaseUrl();
        if (configured == null || configured.isBlank()) {
            return "http://localhost:" + environment.getProperty("server.port", "8080");
        }
        return configured.replaceAll("/+$", "");
    }

    private UUID defaultWorkspace(UUID tenantId) {
        List<UUID> ids = jdbc.queryForList("""
                select id from workspaces
                where tenant_id = ? and deleted_at is null
                order by is_default desc, created_at limit 1
                """, UUID.class, tenantId);
        if (ids.isEmpty()) {
            throw ApiException.conflict("The runtime tenant has no workspace");
        }
        return ids.getFirst();
    }

    private String ownerEmail(UUID tenantId) {
        List<String> emails = jdbc.queryForList("""
                select u.email::text from memberships m join users u on u.id = m.user_id
                where m.tenant_id = ? and m.is_owner and m.status = 'ACTIVE'
                order by m.created_at limit 1
                """, String.class, tenantId);
        return emails.isEmpty() ? null : emails.getFirst();
    }

    private UUID ownerUser(UUID tenantId) {
        List<UUID> ids = jdbc.queryForList("""
                select user_id from memberships
                where tenant_id = ? and is_owner and status = 'ACTIVE'
                order by created_at limit 1
                """, UUID.class, tenantId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank() || !EMAIL.matcher(email.strip()).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "A valid owner e-mail address is required");
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
