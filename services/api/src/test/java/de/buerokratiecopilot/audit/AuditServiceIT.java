package de.buerokratiecopilot.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import de.buerokratiecopilot.audit.domain.AuditEvent;
import de.buerokratiecopilot.identity.domain.Tenant;
import de.buerokratiecopilot.shared.context.RequestContext;
import de.buerokratiecopilot.support.TestDataFactory;

/**
 * Verifies the audit trail against the real database: entries are written, secrets are
 * masked, the tenant is taken from the request context and history cannot be rewritten.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditServiceIT {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TestDataFactory fixtures;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    @Transactional
    @DisplayName("an audit entry is written with the tenant and request metadata of the caller")
    void recordsTenantAndRequestMetadata() {
        Tenant tenant = fixtures.createCompany();
        UUID tenantId = tenant.getId();
        // A fixed address would collide with the previous run: the database is reused between runs.
        UUID userId = fixtures.createUser(
                "audit-owner-" + UUID.randomUUID().toString().substring(0, 8) + "@example.de").getId();

        AuditEvent saved = RequestContext.runAs(tenantId, () -> {
            RequestContext.setUserId(userId);
            return auditService.record(AuditService.AuditEntry.builder(AuditEvent.Category.DOCUMENT, "DOCUMENT_UPLOADED")
                    .description("Uploaded invoice")
                    .entity("documents", UUID.randomUUID(), "rechnung.pdf")
                    .request("192.0.2.10", "Mozilla/5.0", "corr-1", "/api/v1/documents", "POST")
                    .build());
        });

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getActorUserId()).isEqualTo(userId);
        assertThat(saved.getCreatedAt()).isNotNull();

        AuditEvent reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCategory()).isEqualTo(AuditEvent.Category.DOCUMENT);
        assertThat(reloaded.getAction()).isEqualTo("DOCUMENT_UPLOADED");
        assertThat(reloaded.getEntityLabel()).isEqualTo("rechnung.pdf");
        assertThat(reloaded.getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    @Transactional
    @DisplayName("passwords, tokens and bank details never reach the audit trail in clear text")
    void masksSensitiveValues() {
        UUID tenantId = fixtures.createCompany().getId();

        AuditEvent saved = RequestContext.runAs(tenantId, () -> auditService.record(
                AuditService.AuditEntry.builder(AuditEvent.Category.ADMIN, "USER_UPDATED")
                        .changes(Map.of(
                                "email", "max.mustermann@example.de",
                                "password", "SuperSecret123!",
                                "apiKey", "sk-live-abcdef",
                                "note", "contact via max.mustermann@example.de, IBAN DE89370400440532013000"))
                        .description("Password reset requested for max.mustermann@example.de")
                        .build()));

        AuditEvent reloaded = repository.findById(saved.getId()).orElseThrow();
        Map<String, Object> changes = reloaded.getChanges();

        assertThat(changes).containsEntry("password", "***");
        assertThat(changes).containsEntry("apiKey", "***");
        assertThat(String.valueOf(changes.get("note"))).doesNotContain("DE89370400440532013000");
        assertThat(String.valueOf(changes.get("note"))).doesNotContain("max.mustermann@example.de");
        assertThat(reloaded.getDescription()).doesNotContain("max.mustermann@example.de");
        assertThat(reloaded.getDescription()).contains("m***@example.de");
    }

    @Test
    @DisplayName("audit history cannot be updated, not even by the application")
    void historyCannotBeUpdated() {
        UUID id = recordProbeEntry();

        // Each attempt runs in its own transaction: PostgreSQL aborts the whole transaction
        // after the trigger raises, so the two assertions must not share one.
        assertThatThrownBy(() -> inNewTransaction(() -> entityManager
                .createNativeQuery("UPDATE audit_events SET description = 'tampered' WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate()))
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("audit history cannot be deleted, not even by the application")
    void historyCannotBeDeleted() {
        UUID id = recordProbeEntry();

        assertThatThrownBy(() -> inNewTransaction(() -> entityManager
                .createNativeQuery("DELETE FROM audit_events WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate()))
                .hasMessageContaining("append-only");

        assertThat(repository.findById(id)).as("the entry still exists").isPresent();
    }

    private UUID recordProbeEntry() {
        UUID tenantId = fixtures.createCompany().getId();
        return inNewTransaction(() -> RequestContext.runAs(tenantId, () -> auditService
                .record(AuditService.AuditEntry.builder(AuditEvent.Category.ADMIN, "PROBE").build())
                .getId()));
    }

    private <T> T inNewTransaction(java.util.function.Supplier<T> action) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> action.get());
    }

    @Test
    @Transactional
    @DisplayName("denied attempts are recorded with a warning severity")
    void recordsDeniedAttempts() {
        UUID tenantId = fixtures.createCompany().getId();

        RequestContext.runAs(tenantId, () -> {
            auditService.recordDenied(AuditEvent.Category.AUTHORIZATION, "ACCESS_DENIED",
                    "User tried to open a document of another tenant");
            return null;
        });

        List<AuditEvent> events = repository.findByTenantIdOrderByCreatedAtDesc(
                tenantId, org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getResult()).isEqualTo(AuditEvent.Result.DENIED);
        assertThat(events.getFirst().getSeverity()).isEqualTo(AuditEvent.Severity.WARNING);
    }

    @Test
    @DisplayName("the sanitizer masks e-mail addresses and IBANs inside free text")
    void sanitizerIsEffective() {
        assertThat(LogSanitizer.sanitizeText("Mail an max@example.de, IBAN DE89370400440532013000"))
                .isEqualTo("Mail an m***@example.de, IBAN ***IBAN***");
        assertThat(LogSanitizer.sanitizeText("Bearer abc.def.ghi")).isEqualTo("Bearer ***");
        assertThat(LogSanitizer.isSensitiveKey("X-Api-Key")).isTrue();
        assertThat(LogSanitizer.isSensitiveKey("displayName")).isFalse();
        assertThat(LogSanitizer.sanitizeText(null)).isNull();
    }
}
