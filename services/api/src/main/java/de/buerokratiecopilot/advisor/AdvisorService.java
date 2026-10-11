package de.buerokratiecopilot.advisor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * An advisor and their clients (spec §17, H-005).
 *
 * <p>The acceptance criterion is that a consultant sees only the clients assigned to them. Four rules
 * make that true, and the second is the one usually left out.</p>
 *
 * <p><strong>A client is visible only once it has accepted.</strong> The row is created by the advisor, but
 * an invitation is not access: a consultation is an arrangement between two independent parties, and the
 * party whose data is being looked at has to be the one that agreed. A row created by the advisor alone
 * would be an advisor granting themselves access to somebody else's books.</p>
 *
 * <p><strong>An ended arrangement grants nothing.</strong> Ending it is not a note in a field; every read
 * checks whether the arrangement is still active, so access stops when it ends rather than when somebody
 * remembers.</p>
 *
 * <p><strong>Two advisors do not see each other's clients.</strong> Each arrangement belongs to one
 * advisor, so a second consultant serving the same client does not inherit the first one's access.</p>
 *
 * <p><strong>A batch refuses as a whole when one client is not assigned.</strong> Processing the assigned
 * ones and skipping the rest quietly would be an operation that ran over a client's books without a right
 * to them — and nobody would notice, because the run reported success.</p>
 */
@Service
public class AdvisorService {

    private static final Logger LOG = LoggerFactory.getLogger(AdvisorService.class);

    private static final List<String> LEVELS = List.of("READ", "COMMENT", "WRITE", "APPROVE", "FULL");
    private static final List<String> SCOPES = List.of("ALL", "CASES", "DOCUMENTS", "FINANCE");

    private final JdbcTemplate jdbc;

    public AdvisorService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An arrangement between an advisor and a client. */
    public record Arrangement(UUID id, UUID advisorTenantId, UUID clientTenantId, String status,
                              String accessLevel, String scope, String label) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id.toString());
            map.put("advisorTenantId", advisorTenantId.toString());
            map.put("clientTenantId", clientTenantId.toString());
            map.put("status", status);
            map.put("accessLevel", accessLevel);
            map.put("scope", scope);
            map.put("label", label);
            return map;
        }
    }

    /** What a batch did. */
    public record BatchResult(int processed, List<String> refused) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("processed", processed);
            map.put("refused", refused);
            return map;
        }
    }

    /** Invites a client. The client still has to accept. */
    @Transactional
    public Arrangement invite(UUID advisorTenantId, UUID clientTenantId, String accessLevel, String scope,
                              String label, UUID invitedBy) {
        if (clientTenantId == null || clientTenantId.equals(advisorTenantId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Ein Berater ist nicht sein eigener Mandant");
        }
        requireAdvisor(advisorTenantId);
        requireTenant(clientTenantId);

        String level = accessLevel == null ? "READ" : accessLevel.toUpperCase(java.util.Locale.ROOT);
        if (!LEVELS.contains(level)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannte Zugriffsstufe: " + accessLevel);
        }
        String normalizedScope = scope == null ? "ALL" : scope.toUpperCase(java.util.Locale.ROOT);
        if (!SCOPES.contains(normalizedScope)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Unbekannter Umfang: " + scope);
        }

        Integer existing = jdbc.queryForObject("""
                select count(*) from advisor_clients where tenant_id = ? and client_tenant_id = ?
                """, Integer.class, advisorTenantId, clientTenantId);
        if (existing != null && existing > 0) {
            throw ApiException.conflict("Für diesen Mandanten besteht bereits eine Vereinbarung");
        }

        UUID id = jdbc.queryForObject("""
                insert into advisor_clients (id, tenant_id, client_tenant_id, status, access_level, scope,
                                             label, invited_by)
                values (gen_random_uuid(), ?, ?, 'INVITED', ?, ?, ?, ?)
                returning id
                """, UUID.class, advisorTenantId, clientTenantId, level, normalizedScope, label, invitedBy);

        LOG.info("Advisor {} invited client {} ({} / {})", advisorTenantId, clientTenantId, level,
                normalizedScope);
        return new Arrangement(id, advisorTenantId, clientTenantId, "INVITED", level, normalizedScope,
                label);
    }

    /** Accepts an invitation, which is what turns it into access. Only the client may accept. */
    @Transactional
    public Arrangement accept(UUID clientTenantId, UUID arrangementId, UUID acceptedBy) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, tenant_id, client_tenant_id, status, access_level, scope, label
                from advisor_clients where id = ? and client_tenant_id = ?
                """, arrangementId, clientTenantId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("arrangement");
        }
        if (!"INVITED".equals(String.valueOf(rows.getFirst().get("status")))) {
            throw ApiException.conflict("Diese Vereinbarung ist nicht mehr offen");
        }

        jdbc.update("""
                update advisor_clients set status = 'ACTIVE', accepted_at = now(), accepted_by = ?,
                       updated_at = now()
                where id = ? and client_tenant_id = ?
                """, acceptedBy, arrangementId, clientTenantId);

        Map<String, Object> row = rows.getFirst();
        LOG.info("Client {} accepted arrangement {}", clientTenantId, arrangementId);
        return new Arrangement(arrangementId, (UUID) row.get("tenant_id"), clientTenantId, "ACTIVE",
                String.valueOf(row.get("access_level")), String.valueOf(row.get("scope")),
                (String) row.get("label"));
    }

    /** Ends an arrangement. Every read checks the status, so access stops at once. */
    @Transactional
    public void end(UUID advisorTenantId, UUID arrangementId, UUID endedBy, String reason) {
        int updated = jdbc.update("""
                update advisor_clients set status = 'ENDED', ended_at = now(), ended_by = ?,
                       updated_at = now()
                where id = ? and tenant_id = ? and status <> 'ENDED'
                """, endedBy, arrangementId, advisorTenantId);
        if (updated == 0) {
            throw ApiException.notFound("arrangement");
        }
        LOG.info("Arrangement {} ended by {} ({})", arrangementId, endedBy, reason);
    }

    /** Whether an advisor may work on a client's data right now. An invitation grants nothing. */
    @Transactional(readOnly = true)
    public boolean mayAccess(UUID advisorTenantId, UUID clientTenantId) {
        if (advisorTenantId == null || clientTenantId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from advisor_clients
                where tenant_id = ? and client_tenant_id = ? and status = 'ACTIVE'
                """, Integer.class, advisorTenantId, clientTenantId);
        return count != null && count > 0;
    }

    /** Refuses when an advisor has no right to a client, reporting it as not found. */
    @Transactional(readOnly = true)
    public void requireAccess(UUID advisorTenantId, UUID clientTenantId) {
        if (!mayAccess(advisorTenantId, clientTenantId)) {
            throw ApiException.notFound("client");
        }
    }

    /** The clients an advisor may work on, and only those. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeClients(UUID advisorTenantId) {
        return jdbc.queryForList("""
                select a.client_tenant_id, t.name as client_name, t.account_type, a.access_level, a.scope,
                       a.label, a.accepted_at
                from advisor_clients a join tenants t on t.id = a.client_tenant_id
                where a.tenant_id = ? and a.status = 'ACTIVE' order by t.name
                """, advisorTenantId);
    }

    /** Everything an advisor is party to, including what the client has not accepted yet. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> arrangements(UUID advisorTenantId) {
        return jdbc.queryForList("""
                select id, client_tenant_id, status, access_level, scope, label, invited_at, accepted_at,
                       ended_at
                from advisor_clients where tenant_id = ? order by invited_at desc
                """, advisorTenantId);
    }

    /** What a client has agreed to, which is the client's own view of the arrangement. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> advisorsOf(UUID clientTenantId) {
        return jdbc.queryForList("""
                select a.tenant_id as advisor_tenant_id, t.name as advisor_name, a.status, a.access_level,
                       a.scope, a.accepted_at
                from advisor_clients a join tenants t on t.id = a.tenant_id
                where a.client_tenant_id = ? order by a.invited_at desc
                """, clientTenantId);
    }

    /**
     * Runs a batch over several clients.
     *
     * <p>Refuses the whole batch when one of them is not assigned: processing the others and skipping the
     * rest quietly would be an operation over a client's books without a right to them, and nobody would
     * notice because the run reported success.</p>
     */
    @Transactional
    public BatchResult runBatch(UUID advisorTenantId, List<UUID> clientTenantIds,
                                Function<UUID, Integer> action) {
        if (clientTenantIds == null || clientTenantIds.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "Der Stapel ist leer");
        }
        List<String> refused = new ArrayList<>();
        for (UUID clientTenantId : clientTenantIds) {
            if (!mayAccess(advisorTenantId, clientTenantId)) {
                refused.add(clientTenantId.toString());
            }
        }
        if (!refused.isEmpty()) {
            throw ApiException.forbidden("Der Stapel enthält " + refused.size() + " Mandant(en), für die "
                    + "keine gültige Vereinbarung besteht. Es wurde nichts verarbeitet.");
        }

        int processed = 0;
        for (UUID clientTenantId : clientTenantIds) {
            if (action != null) {
                Integer result = action.apply(clientTenantId);
                processed += result == null ? 0 : result;
            }
            else {
                processed++;
            }
        }
        LOG.info("Batch over {} client(s) for advisor {}: {} processed", clientTenantIds.size(),
                advisorTenantId, processed);
        return new BatchResult(processed, List.of());
    }

    /** Whether a tenant is an advisor at all. */
    @Transactional(readOnly = true)
    public boolean isAdvisor(UUID tenantId) {
        List<String> types = jdbc.query("""
                select account_type from tenants where id = ?
                """, (rs, row) -> rs.getString("account_type"), tenantId);
        return !types.isEmpty() && "EXTERNAL_REPRESENTATIVE".equals(types.getFirst());
    }

    private void requireAdvisor(UUID tenantId) {
        if (!isAdvisor(tenantId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Mandanten können nur von einem Beraterkonto aus verwaltet werden");
        }
    }

    private void requireTenant(UUID tenantId) {
        Integer count = jdbc.queryForObject("select count(*) from tenants where id = ?", Integer.class,
                tenantId);
        if (count == null || count == 0) {
            throw ApiException.notFound("tenant");
        }
    }
}
