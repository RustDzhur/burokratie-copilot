package de.buerokratiecopilot.accounts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.accounts.ModuleCatalogue.Module;
import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Decides which modules a tenant may use (spec §3.1, H-001 acceptance).
 *
 * <p>The acceptance criterion is that an unavailable module cannot be opened, and the word that matters
 * is <em>cannot</em>: not "is hidden in the navigation", not "shows an error page". The check is here, on
 * the backend, and the navigation reflects it rather than enforcing it.</p>
 *
 * <p>The account type supplies the starting point and the tenant's own configuration can change it. That
 * order matters: a private person who starts keeping company books does not need a different account, and
 * a company that switches off the finance module should not have it switched back on because the type
 * says so.</p>
 *
 * <p>Switching the account type changes what is available and <strong>deletes nothing</strong>. A module
 * that becomes unavailable is not a reason to destroy documents somebody filed under it; the records stay
 * and become reachable again if the module is enabled. A platform that deleted on a settings change would
 * be a platform nobody dares configure.</p>
 */
@Service
public class AccountModeService {

    private static final Logger log = LoggerFactory.getLogger(AccountModeService.class);

    private final JdbcTemplate jdbc;

    public AccountModeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether a tenant may use a module. */
    @Transactional(readOnly = true)
    public boolean allows(UUID tenantId, String moduleKey) {
        Module module = ModuleCatalogue.byKey(moduleKey);
        if (module == null) {
            // A module the platform does not have is not available to anybody, whatever is configured.
            return false;
        }

        List<Boolean> configured = jdbc.query("""
                select is_enabled from feature_flags where tenant_id = ? and flag_key = ?
                """, (rs, row) -> rs.getBoolean("is_enabled"), tenantId,
                ModuleCatalogue.keyOf(module));
        if (!configured.isEmpty()) {
            return Boolean.TRUE.equals(configured.getFirst());
        }

        return ModuleCatalogue.defaultsFor(accountTypeOf(tenantId)).contains(module);
    }

    /**
     * Refuses when a module is not available.
     *
     * <p>Called at the entry point of a module's API. The message names the module and the reason, because
     * a user who cannot open something deserves to know whether it is not part of their account or was
     * switched off for them.</p>
     */
    @Transactional(readOnly = true)
    public void requireAccess(UUID tenantId, String moduleKey) {
        Module module = ModuleCatalogue.byKey(moduleKey);
        if (module == null) {
            throw ApiException.notFound("module");
        }
        if (!allows(tenantId, moduleKey)) {
            throw ApiException.forbidden("Das Modul '" + module.label() + "' ist für dieses Konto nicht "
                    + "verfügbar. Der Kontotyp und die Modulkonfiguration des Kontos bestimmen, welche "
                    + "Module offenstehen.");
        }
    }

    /** The modules a tenant may use, which is what a navigation is built from. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> availableModules(UUID tenantId) {
        List<Map<String, Object>> available = new ArrayList<>();
        ModuleCatalogue.allModules().forEach((key, label) -> {
            if (allows(tenantId, key)) {
                Map<String, Object> module = new LinkedHashMap<>();
                module.put("key", key);
                module.put("label", label);
                module.put("source", isConfigured(tenantId, key) ? "CONFIGURED" : "ACCOUNT_TYPE");
                available.add(module);
            }
        });
        return available;
    }

    /** Whether the tenant's own configuration decided this, rather than the account type. */
    @Transactional(readOnly = true)
    public boolean isConfigured(UUID tenantId, String moduleKey) {
        Integer count = jdbc.queryForObject("""
                select count(*) from feature_flags where tenant_id = ? and flag_key = ?
                """, Integer.class, tenantId, moduleKey);
        return count != null && count > 0;
    }

    /**
     * Switches a module on or off for a tenant.
     *
     * <p>Configuration, not law: the platform records the decision and applies it, and does not decide
     * what the account needs.</p>
     */
    @Transactional
    public void configure(UUID tenantId, String moduleKey, boolean enabled, UUID changedBy) {
        if (ModuleCatalogue.byKey(moduleKey) == null) {
            throw ApiException.notFound("module");
        }
        jdbc.update("""
                insert into feature_flags (id, tenant_id, flag_key, description, is_enabled,
                                           rollout_percent, conditions, updated_by)
                values (gen_random_uuid(), ?, ?, ?, ?, 100, '{}'::jsonb, ?)
                on conflict (coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid),
                             flag_key)
                do update set is_enabled = excluded.is_enabled, updated_by = excluded.updated_by,
                              updated_at = now()
                """, tenantId, moduleKey, "Modulverfügbarkeit", enabled, changedBy);
        log.info("Module {} {} for tenant {}", moduleKey, enabled ? "enabled" : "disabled", tenantId);
    }

    /** The account type of a tenant. */
    @Transactional(readOnly = true)
    public String accountTypeOf(UUID tenantId) {
        List<String> types = jdbc.query("select account_type from tenants where id = ?",
                (rs, row) -> rs.getString("account_type"), tenantId);
        if (types.isEmpty()) {
            throw ApiException.notFound("tenant");
        }
        return types.getFirst();
    }

    /** The modules of an account type before any tenant-specific change, for a comparison. */
    public Set<Module> defaultsOf(String accountType) {
        return ModuleCatalogue.defaultsFor(accountType);
    }
}
