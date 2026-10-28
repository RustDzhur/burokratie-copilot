package de.buerokratiecopilot.family;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/**
 * Personal and shared documents in a household (spec §3.1, H-003 acceptance).
 *
 * <p>The acceptance criterion is that a family member does not automatically get access to another
 * member's personal documents. Two things have to be got right for that to mean anything.</p>
 *
 * <p><strong>A document is shared unless somebody says otherwise.</strong> The platform must not turn an
 * ordinary family file into a private one because a person forgot to mark it: marking something personal
 * is a decision somebody makes about their own document, and the safe default for a household is that
 * what is in the house is the household's. The rule that matters is the other one — that once marked, it
 * stays private.</p>
 *
 * <p><strong>Another member's personal document is reported as not found.</strong> Not as forbidden. A
 * refusal that says "this exists but is not yours" tells a family member what the others keep, and in a
 * household that is itself information. The answer is the same one a document that does not exist gets,
 * which is the only answer that gives nothing away.</p>
 *
 * <p>A share is an explicit decision by the member whose document it is, and it names them. A rule with no
 * way to make an exception is a rule people work around — by printing the document or keeping it
 * elsewhere — and the exception is worth having if it is attributable.</p>
 */
@Service
public class FamilyAccessService {

    private static final Logger LOG = LoggerFactory.getLogger(FamilyAccessService.class);

    private final JdbcTemplate jdbc;

    public FamilyAccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Why a member may or may not read a document. */
    public enum Basis {
        /** The document is not personal, so the household may read it. */
        SHARED,
        /** The document is personal and belongs to this member. */
        OWN,
        /** Somebody shared a personal document with this member. */
        SHARED_EXPLICITLY,
        /** The document is personal to somebody else. */
        NOT_PERMITTED
    }

    /** The answer to a question about access. */
    public record Decision(boolean readable, Basis basis, UUID ownerUserId) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("readable", readable);
            map.put("basis", basis.name());
            map.put("ownerUserId", ownerUserId == null ? null : ownerUserId.toString());
            return map;
        }
    }

    /**
     * Whether a member may read a document.
     *
     * <p>The document is looked up in the tenant's context first, so a document of another household is
     * not found rather than refused — the same rule the platform applies everywhere.</p>
     */
    @Transactional(readOnly = true)
    public Decision decide(UUID tenantId, UUID userId, UUID documentId) {
        List<UUID> document = jdbc.query("""
                select id from documents where id = ? and tenant_id = ? and deleted_at is null
                """, (rs, row) -> rs.getObject("id", UUID.class), documentId, tenantId);
        if (document.isEmpty()) {
            return new Decision(false, Basis.NOT_PERMITTED, null);
        }
        if (!isActiveMember(tenantId, userId)) {
            return new Decision(false, Basis.NOT_PERMITTED, null);
        }

        List<UUID> owners = jdbc.query("""
                select member_user_id from personal_documents
                where document_id = ? and tenant_id = ?
                """, (rs, row) -> rs.getObject("member_user_id", UUID.class), documentId, tenantId);
        if (owners.isEmpty()) {
            // Nobody marked it personal, so it belongs to the household.
            return new Decision(true, Basis.SHARED, null);
        }
        UUID owner = owners.getFirst();
        if (owner.equals(userId)) {
            return new Decision(true, Basis.OWN, owner);
        }
        if (hasShare(tenantId, documentId, userId)) {
            return new Decision(true, Basis.SHARED_EXPLICITLY, owner);
        }
        return new Decision(false, Basis.NOT_PERMITTED, owner);
    }

    /**
     * Refuses when a member may not read a document.
     *
     * <p>Reports it as not found, deliberately: a refusal that distinguishes "exists but is not yours"
     * from "does not exist" tells a family member what the others keep.</p>
     */
    @Transactional(readOnly = true)
    public void requireRead(UUID tenantId, UUID userId, UUID documentId) {
        if (!decide(tenantId, userId, documentId).readable()) {
            throw ApiException.notFound("document");
        }
    }

    /**
     * Marks a document as personal to a member.
     *
     * @param ownerUserId the member it belongs to; only that member, or somebody acting for the household,
     *                    can mark it
     */
    @Transactional
    public void markPersonal(UUID tenantId, UUID documentId, UUID ownerUserId, UUID markedBy,
                             String note) {
        if (ownerUserId == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Ein persönliches Dokument gehört zu einem Mitglied");
        }
        if (!isActiveMember(tenantId, ownerUserId)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Das angegebene Mitglied gehört nicht zu diesem Haushalt");
        }
        requireDocument(tenantId, documentId);

        jdbc.update("""
                insert into personal_documents (id, tenant_id, document_id, member_user_id, note,
                                                created_by)
                values (gen_random_uuid(), ?, ?, ?, ?, ?)
                on conflict (document_id)
                do update set member_user_id = excluded.member_user_id, note = excluded.note,
                              created_by = excluded.created_by
                """, tenantId, documentId, ownerUserId, note, markedBy);
        LOG.info("Document {} marked personal to {} by {}", documentId, ownerUserId, markedBy);
    }

    /** Makes a document shared with the household again. */
    @Transactional
    public void markShared(UUID tenantId, UUID documentId, UUID changedBy) {
        requireDocument(tenantId, documentId);
        int removed = jdbc.update("""
                delete from personal_documents where document_id = ? and tenant_id = ?
                """, documentId, tenantId);
        if (removed == 0) {
            throw ApiException.conflict("Dieses Dokument ist nicht als persönlich gekennzeichnet");
        }
        LOG.info("Document {} made shared again by {}", documentId, changedBy);
    }

    /**
     * Shares a personal document with another member.
     *
     * <p>Only the member it belongs to may do this; a share granted by somebody else would not be the
     * owner's decision about their own document.</p>
     */
    @Transactional
    public void share(UUID tenantId, UUID documentId, UUID ownerUserId, UUID sharedWith,
                      Instant expiresAt) {
        List<UUID> owners = jdbc.query("""
                select member_user_id from personal_documents
                where document_id = ? and tenant_id = ?
                """, (rs, row) -> rs.getObject("member_user_id", UUID.class), documentId, tenantId);
        if (owners.isEmpty()) {
            throw ApiException.conflict("Dieses Dokument ist nicht persönlich; es ist bereits für den "
                    + "Haushalt sichtbar");
        }
        if (!owners.getFirst().equals(ownerUserId)) {
            throw ApiException.forbidden("Nur wer das Dokument als persönlich gekennzeichnet hat, kann "
                    + "es freigeben");
        }
        if (!isActiveMember(tenantId, sharedWith)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "Das angegebene Mitglied gehört nicht zu diesem Haushalt");
        }

        jdbc.update("""
                insert into document_shares (id, tenant_id, document_id, shared_with_user_id, granted_by,
                                             expires_at)
                values (gen_random_uuid(), ?, ?, ?, ?, ?)
                on conflict (document_id, shared_with_user_id)
                do update set granted_by = excluded.granted_by, granted_at = now(),
                              expires_at = excluded.expires_at, revoked_at = null
                """, tenantId, documentId, sharedWith, ownerUserId, expiresAt == null ? null
                        : java.sql.Timestamp.from(expiresAt));
        LOG.info("Personal document {} shared with {} by {}", documentId, sharedWith, ownerUserId);
    }

    /** Withdraws a share. */
    @Transactional
    public void revokeShare(UUID tenantId, UUID documentId, UUID sharedWith) {
        int updated = jdbc.update("""
                update document_shares set revoked_at = now()
                where document_id = ? and tenant_id = ? and shared_with_user_id = ? and revoked_at is null
                """, documentId, tenantId, sharedWith);
        if (updated == 0) {
            throw ApiException.notFound("share");
        }
    }

    private boolean hasShare(UUID tenantId, UUID documentId, UUID userId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from document_shares
                where document_id = ? and tenant_id = ? and shared_with_user_id = ?
                  and revoked_at is null
                  and (expires_at is null or expires_at > now())
                """, Integer.class, documentId, tenantId, userId);
        return count != null && count > 0;
    }

    /** Whether a person is an active member of the household. */
    @Transactional(readOnly = true)
    public boolean isActiveMember(UUID tenantId, UUID userId) {
        if (userId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from memberships
                where tenant_id = ? and user_id = ? and status = 'ACTIVE'
                  and (access_valid_until is null or access_valid_until > now())
                """, Integer.class, tenantId, userId);
        return count != null && count > 0;
    }

    /**
     * The documents a member may read.
     *
     * <p>The same three cases as {@link #decide}, expressed as a query so a list does not have to fetch
     * everything and filter in memory — which is how a list ends up showing what it should not.</p>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> readableDocumentIds(UUID tenantId, UUID userId, int limit) {
        if (!isActiveMember(tenantId, userId)) {
            return List.of();
        }
        return jdbc.queryForList("""
                select d.id, d.original_filename, d.created_at,
                       case when p.member_user_id = ? then 'OWN'
                            when s.id is not null then 'SHARED_EXPLICITLY'
                            else 'SHARED' end as basis
                from documents d
                left join personal_documents p on p.document_id = d.id
                left join document_shares s on s.document_id = d.id and s.shared_with_user_id = ?
                     and s.revoked_at is null and (s.expires_at is null or s.expires_at > now())
                where d.tenant_id = ? and d.deleted_at is null
                  and (p.id is null or p.member_user_id = ? or s.id is not null)
                order by d.created_at desc
                limit ?
                """, userId, userId, tenantId, userId, Math.max(1, Math.min(limit, 200)));
    }

    /** The members of a household, for a picker that names people rather than identifiers. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(UUID tenantId) {
        return jdbc.queryForList("""
                select m.user_id, u.display_name, m.status, m.is_owner
                from memberships m join users u on u.id = m.user_id
                where m.tenant_id = ? and m.status = 'ACTIVE' order by m.joined_at
                """, tenantId);
    }

    /** The personal documents of a member, which only that member sees. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> personalDocuments(UUID tenantId, UUID userId) {
        return jdbc.queryForList("""
                select d.id, d.original_filename, p.created_at from personal_documents p
                join documents d on d.id = p.document_id
                where p.tenant_id = ? and p.member_user_id = ? and d.deleted_at is null
                order by p.created_at desc
                """, tenantId, userId);
    }

    /** Whether a document is personal at all, without saying to whom. */
    @Transactional(readOnly = true)
    public boolean isPersonal(UUID tenantId, UUID documentId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from personal_documents where document_id = ? and tenant_id = ?
                """, Integer.class, documentId, tenantId);
        return count != null && count > 0;
    }

    private void requireDocument(UUID tenantId, UUID documentId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from documents where id = ? and tenant_id = ? and deleted_at is null
                """, Integer.class, documentId, tenantId);
        if (count == null || count == 0) {
            throw ApiException.notFound("document");
        }
    }

    /** The bases a decision can have, so a caller can document them. */
    public static List<Basis> bases() {
        return new ArrayList<>(List.of(Basis.values()));
    }
}
