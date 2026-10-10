package de.buerokratiecopilot.audit.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An entry of the audit trail (spec §21.4).
 *
 * <p>The table is append-only at the database level: a trigger rejects {@code UPDATE} and
 * {@code DELETE}, so neither an application bug nor a compromised account can rewrite
 * history. This entity therefore has no setters — it is written once and only read.</p>
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    /** Categories of auditable activity required by spec §21.4. */
    public enum Category {
        AUTHENTICATION, AUTHORIZATION, DOCUMENT, DOCUMENT_ACCESS, CASE, TASK, CONTRACT,
        FINANCE, PAYMENT, MAIL, TEMPLATE, WORKFLOW, APPROVAL, INTEGRATION, DEVICE, AI,
        SETTINGS, EXPORT, DELETION, ADMIN, RETENTION
    }

    public enum Severity {
        INFO, NOTICE, WARNING, CRITICAL
    }

    public enum Result {
        SUCCESS, FAILURE, DENIED
    }

    @Id
    private UUID id;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ActorType actorType = ActorType.USER;

    @Column(name = "actor_label", length = 200)
    private String actorLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Category category;

    @Column(nullable = false, length = 80)
    private String action;

    @Column(name = "entity_type", length = 60)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "entity_label", length = 300)
    private String entityLabel;

    @Column(columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> changes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Result result = Result.SUCCESS;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity = Severity.INFO;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "ip_address")
    private java.net.InetAddress ipAddress;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "correlation_id", length = 80)
    private String correlationId;

    @Column(name = "request_path", length = 400)
    private String requestPath;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    @Column(name = "ai_model_run_id")
    private UUID aiModelRunId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Actor types of the audit trail; mirrors {@code RequestContext.ActorType}. */
    public enum ActorType {
        USER, SYSTEM, API_TOKEN, WORKFLOW, AI, DEVICE
    }

    protected AuditEvent() {
        // for JPA
    }

    public AuditEvent(UUID id, Category category, String action) {
        this.id = id;
        this.category = category;
        this.action = action;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(UUID workspaceId) {
        this.workspaceId = workspaceId;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public void setActorUserId(UUID actorUserId) {
        this.actorUserId = actorUserId;
    }

    public ActorType getActorType() {
        return actorType;
    }

    public void setActorType(ActorType actorType) {
        this.actorType = actorType;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public void setActorLabel(String actorLabel) {
        this.actorLabel = actorLabel;
    }

    public Category getCategory() {
        return category;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public void setEntityId(UUID entityId) {
        this.entityId = entityId;
    }

    public String getEntityLabel() {
        return entityLabel;
    }

    public void setEntityLabel(String entityLabel) {
        this.entityLabel = entityLabel;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getChanges() {
        return changes;
    }

    public void setChanges(Map<String, Object> changes) {
        this.changes = changes;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public java.net.InetAddress getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(java.net.InetAddress ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public void setRequestPath(String requestPath) {
        this.requestPath = requestPath;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public UUID getAiModelRunId() {
        return aiModelRunId;
    }

    public void setAiModelRunId(UUID aiModelRunId) {
        this.aiModelRunId = aiModelRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
