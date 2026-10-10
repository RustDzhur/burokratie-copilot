package de.buerokratiecopilot.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.shared.util.IpAddresses;

/**
 * Writes the audit trail (spec §21.4).
 *
 * <p>Audit entries are written in the <em>same transaction</em> as the business change: either
 * both happen or neither. Failed security relevant attempts (denied access, failed login) are
 * recorded too, which is why the service never swallows exceptions from callers and instead
 * offers a dedicated {@code recordDenied}/{@code recordFailure} API.</p>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Records an event. Joins an existing transaction when there is one so that the entry
     * cannot survive a rolled-back business action.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent record(AuditEntry entry) {
        AuditEvent event = new AuditEvent(UUID.randomUUID(), entry.category(), entry.action());
        event.setActorType(entry.actorType() != null ? entry.actorType() : mapActor(RequestContext.actorType()));
        event.setActorUserId(entry.actorUserId() != null ? entry.actorUserId() : RequestContext.userId().orElse(null));
        event.setActorLabel(entry.actorLabel());
        // The tenant of the event is the tenant of the request; an explicit value is only
        // accepted when the caller intentionally audits another scope (system maintenance).
        event.setTenantId(entry.tenantId() != null ? entry.tenantId() : RequestContext.tenantId().orElse(null));
        event.setWorkspaceId(entry.workspaceId());
        event.setEntityType(entry.entityType());
        event.setEntityId(entry.entityId());
        event.setEntityLabel(entry.entityLabel());
        event.setDescription(LogSanitizer.sanitizeText(entry.description()));
        event.setChanges(LogSanitizer.sanitize(entry.changes()));
        event.setResult(entry.result() != null ? entry.result() : AuditEvent.Result.SUCCESS);
        event.setSeverity(entry.severity() != null ? entry.severity() : AuditEvent.Severity.INFO);
        event.setIpAddress(IpAddresses.parse(entry.ipAddress()));
        event.setUserAgent(LogSanitizer.sanitizeText(entry.userAgent()));
        event.setCorrelationId(entry.correlationId() != null
                ? entry.correlationId()
                : RequestContext.correlationId().orElse(null));
        event.setRequestPath(entry.requestPath());
        event.setHttpMethod(entry.httpMethod());
        event.setAiModelRunId(entry.aiModelRunId());

        AuditEvent saved = repository.save(event);
        log.debug("Audit: {} {} by {} ({})", entry.category(), entry.action(), event.getActorType(),
                event.getResult());
        return saved;
    }

    /** Convenience for the common successful case. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordSuccess(AuditEvent.Category category, String action, String description) {
        record(AuditEntry.builder(category, action).description(description).build());
    }

    /** A denied attempt: the user tried something they are not allowed to do (§21.4). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordDenied(AuditEvent.Category category, String action, String description) {
        record(AuditEntry.builder(category, action)
                .description(description)
                .result(AuditEvent.Result.DENIED)
                .severity(AuditEvent.Severity.WARNING)
                .build());
    }

    /** A failed operation that matters for security or finance. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordFailure(AuditEvent.Category category, String action, String description) {
        record(AuditEntry.builder(category, action)
                .description(description)
                .result(AuditEvent.Result.FAILURE)
                .severity(AuditEvent.Severity.WARNING)
                .build());
    }

    private static AuditEvent.ActorType mapActor(RequestContext.ActorType actorType) {
        return switch (actorType) {
            case USER -> AuditEvent.ActorType.USER;
            case API_TOKEN -> AuditEvent.ActorType.API_TOKEN;
            case WORKFLOW -> AuditEvent.ActorType.WORKFLOW;
            case AI -> AuditEvent.ActorType.AI;
            case DEVICE -> AuditEvent.ActorType.DEVICE;
            case SYSTEM -> AuditEvent.ActorType.SYSTEM;
        };
    }

    /**
     * Immutable description of an audit entry. A builder keeps call sites readable and makes it
     * hard to forget the mandatory category and action.
     */
    public record AuditEntry(
            AuditEvent.Category category,
            String action,
            AuditEvent.ActorType actorType,
            UUID actorUserId,
            String actorLabel,
            UUID tenantId,
            UUID workspaceId,
            String entityType,
            UUID entityId,
            String entityLabel,
            String description,
            Map<String, Object> changes,
            AuditEvent.Result result,
            AuditEvent.Severity severity,
            String ipAddress,
            String userAgent,
            String correlationId,
            String requestPath,
            String httpMethod,
            UUID aiModelRunId) {

        public static Builder builder(AuditEvent.Category category, String action) {
            return new Builder(category, action);
        }

        public static final class Builder {

            private final AuditEvent.Category category;
            private final String action;
            private AuditEvent.ActorType actorType;
            private UUID actorUserId;
            private String actorLabel;
            private UUID tenantId;
            private UUID workspaceId;
            private String entityType;
            private UUID entityId;
            private String entityLabel;
            private String description;
            private Map<String, Object> changes = Map.of();
            private AuditEvent.Result result = AuditEvent.Result.SUCCESS;
            private AuditEvent.Severity severity = AuditEvent.Severity.INFO;
            private String ipAddress;
            private String userAgent;
            private String correlationId;
            private String requestPath;
            private String httpMethod;
            private UUID aiModelRunId;

            private Builder(AuditEvent.Category category, String action) {
                this.category = category;
                this.action = action;
            }

            public Builder actor(AuditEvent.ActorType actorType, UUID actorUserId, String actorLabel) {
                this.actorType = actorType;
                this.actorUserId = actorUserId;
                this.actorLabel = actorLabel;
                return this;
            }

            public Builder tenant(UUID tenantId) {
                this.tenantId = tenantId;
                return this;
            }

            public Builder workspace(UUID workspaceId) {
                this.workspaceId = workspaceId;
                return this;
            }

            public Builder entity(String entityType, UUID entityId, String entityLabel) {
                this.entityType = entityType;
                this.entityId = entityId;
                this.entityLabel = entityLabel;
                return this;
            }

            public Builder description(String description) {
                this.description = description;
                return this;
            }

            public Builder changes(Map<String, Object> changes) {
                this.changes = changes == null ? Map.of() : changes;
                return this;
            }

            public Builder result(AuditEvent.Result result) {
                this.result = result;
                return this;
            }

            public Builder severity(AuditEvent.Severity severity) {
                this.severity = severity;
                return this;
            }

            public Builder request(String ipAddress, String userAgent, String correlationId,
                                   String requestPath, String httpMethod) {
                this.ipAddress = ipAddress;
                this.userAgent = userAgent;
                this.correlationId = correlationId;
                this.requestPath = requestPath;
                this.httpMethod = httpMethod;
                return this;
            }

            public Builder aiModelRun(UUID aiModelRunId) {
                this.aiModelRunId = aiModelRunId;
                return this;
            }

            public AuditEntry build() {
                return new AuditEntry(category, action, actorType, actorUserId, actorLabel, tenantId,
                        workspaceId, entityType, entityId, entityLabel, description, changes, result,
                        severity, ipAddress, userAgent, correlationId, requestPath, httpMethod, aiModelRunId);
            }
        }
    }

    /** Timestamp helper for callers that need to correlate an entry with a change. */
    public static Instant now() {
        return Instant.now();
    }
}
