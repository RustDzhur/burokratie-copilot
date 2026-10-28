package de.buerokratiecopilot.documents;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.documents.domain.DocumentTypeRule;

public interface DocumentTypeRuleRepository extends JpaRepository<DocumentTypeRule, UUID> {

    List<DocumentTypeRule> findByDocumentTypeIdAndActiveTrue(UUID documentTypeId);

    /**
     * Rules that apply to a tenant: its own rules plus the system defaults.
     *
     * <p>A tenant rule is not merged with a default of the same pattern — the tenant's own rule wins,
     * so a customer can override a classification without having to disable the shipped one.</p>
     */
    @Query("""
            select r from DocumentTypeRule r
            where r.active = true and (r.tenantId is null or r.tenantId = :tenantId)
            """)
    List<DocumentTypeRule> findEffectiveRules(@Param("tenantId") UUID tenantId);

    void deleteByDocumentTypeId(UUID documentTypeId);
}
