package de.buerokratiecopilot.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import de.buerokratiecopilot.identity.domain.Workspace;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    List<Workspace> findByTenantId(UUID tenantId);

    Optional<Workspace> findByTenantIdAndDefaultWorkspaceTrue(UUID tenantId);
}
