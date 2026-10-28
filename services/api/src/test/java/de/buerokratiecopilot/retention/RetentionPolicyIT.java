package de.buerokratiecopilot.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.error.ApiException;

/**
 * Verifies retention policies (spec §23, B-016 acceptance).
 *
 * <p>The criterion is that deletion after the retention period runs only when there are no
 * restrictions. The sharpest of those restrictions is not the legal hold but verification: the platform
 * does not know how long a document must be kept, so a policy that no person has confirmed deletes
 * nothing, however long its period is.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RetentionPolicyIT {

    @Autowired
    private RetentionService retention;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Aufbewahrung Test', 'COMPANY') returning id
                """, UUID.class, "ret-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Sachbearbeiter', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "rt-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    private UUID document(UUID tenantId, LocalDate documentDate) {
        String hash = "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        return jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status,
                                       document_date, received_at, legal_hold)
                values (gen_random_uuid(), ?, 'rechnung.pdf', 'rechnung.pdf', 'application/pdf', 100, ?,
                        'UPLOAD', 'READY', 'NOT_REQUIRED', ?::date, now(), false)
                returning id
                """, UUID.class, tenantId, hash, documentDate);
    }

    private UUID policy(UUID tenantId, Integer months) {
        return retention.definePolicy(new RetentionService.PolicyRequest(tenantId, "RE-10",
                "Rechnungen 10 Jahre", null, months, "DOCUMENT_DATE",
                "§ 147 AO", LocalDate.of(2020, 1, 1), person()));
    }

    private boolean isDeleted(UUID tenantId, UUID documentId) {
        return jdbc.queryForObject("select deleted_at is not null from documents where id = ? and tenant_id = ?",
                Boolean.class, documentId, tenantId);
    }

    @Test
    @DisplayName("a policy without a legal basis is refused")
    void legalBasisIsRequired() {
        UUID tenantId = tenant();

        // The platform is not entitled to invent a retention period, so it demands the one thing it
        // cannot produce itself.
        assertThatThrownBy(() -> retention.definePolicy(new RetentionService.PolicyRequest(tenantId,
                "X", "Ohne Grundlage", null, 120, "DOCUMENT_DATE", "  ", null, person())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Rechtsgrundlage");
    }

    @Test
    @DisplayName("an unverified policy deletes nothing, however long its period is")
    void unverifiedPolicyDoesNotDelete() {
        UUID tenantId = tenant();
        UUID policyId = policy(tenantId, 1);
        UUID documentId = document(tenantId, LocalDate.of(2020, 1, 1));
        UUID jobId = retention.schedule(tenantId, documentId, policyId, "DELETE", LocalDate.now());

        RetentionService.Outcome outcome = retention.execute(tenantId, jobId, person());

        assertThat(outcome.status()).isEqualTo("BLOCKED");
        assertThat(outcome.reason()).contains("bestätigt");
        assertThat(isDeleted(tenantId, documentId))
                .as("nobody confirmed the period, so nothing may be deleted on its strength")
                .isFalse();
    }

    @Test
    @DisplayName("a verified policy deletes a document whose period has expired")
    void verifiedPolicyDeletes() {
        UUID tenantId = tenant();
        UUID policyId = policy(tenantId, 1);
        UUID documentId = document(tenantId, LocalDate.of(2020, 1, 1));
        retention.verifyPolicy(tenantId, policyId, person());
        UUID jobId = retention.schedule(tenantId, documentId, policyId, "DELETE", LocalDate.now());

        RetentionService.Outcome outcome = retention.execute(tenantId, jobId, person());

        assertThat(outcome.status()).isEqualTo("COMPLETED");
        assertThat(isDeleted(tenantId, documentId)).isTrue();
    }

    @Test
    @DisplayName("verification needs a person")
    void verificationNeedsAPerson() {
        UUID tenantId = tenant();
        UUID policyId = policy(tenantId, 12);

        assertThatThrownBy(() -> retention.verifyPolicy(tenantId, policyId, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Person");
        assertThat(retention.mayDelete(tenantId, policyId)).isFalse();
    }

    @Test
    @DisplayName("deletion is soft, so the archive can still show what it held")
    void deletionIsSoft() {
        UUID tenantId = tenant();
        UUID policyId = policy(tenantId, 1);
        UUID documentId = document(tenantId, LocalDate.of(2020, 1, 1));
        retention.verifyPolicy(tenantId, policyId, person());
        UUID jobId = retention.schedule(tenantId, documentId, policyId, "DELETE", LocalDate.now());

        retention.execute(tenantId, jobId, person());

        // The row is still there; an archive that cannot show what it used to hold is not an archive,
        // and a deletion that turns out to have been wrong has to be answerable.
        assertThat(jdbc.queryForObject("select count(*) from documents where id = ?", Integer.class,
                documentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a deletion without a policy is refused")
    void deletionWithoutPolicyIsRefused() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId, LocalDate.of(2020, 1, 1));
        UUID jobId = retention.schedule(tenantId, documentId, null, "DELETE", LocalDate.now());

        RetentionService.Outcome outcome = retention.execute(tenantId, jobId, person());

        assertThat(outcome.status()).isEqualTo("BLOCKED");
        assertThat(outcome.reason()).contains("Aufbewahrungsregel");
        assertThat(isDeleted(tenantId, documentId)).isFalse();
    }

    @Test
    @DisplayName("the list of documents due for deletion leaves out held and unverified ones")
    void dueForDeletionExcludesRestrictions() {
        UUID tenantId = tenant();
        UUID policyId = policy(tenantId, 1);
        retention.verifyPolicy(tenantId, policyId, person());

        UUID expired = document(tenantId, LocalDate.of(2020, 1, 1));
        UUID recent = document(tenantId, LocalDate.now());
        UUID held = document(tenantId, LocalDate.of(2020, 1, 1));
        retention.placeLegalHold(tenantId, held, person(), "Steuerprüfung");
        jdbc.update("update documents set retention_policy_id = ? where tenant_id = ?", policyId, tenantId);

        var due = retention.dueForDeletion(tenantId);

        assertThat(due).extracting(row -> row.get("id"))
                .contains(expired)
                .doesNotContain(recent, held);
    }

    @Test
    @DisplayName("archiving does not delete")
    void archivingDoesNotDelete() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId, LocalDate.of(2020, 1, 1));
        UUID jobId = retention.schedule(tenantId, documentId, null, "ARCHIVE", LocalDate.now());

        RetentionService.Outcome outcome = retention.execute(tenantId, jobId, person());

        assertThat(outcome.status()).isEqualTo("COMPLETED");
        assertThat(isDeleted(tenantId, documentId)).isFalse();
        assertThat(jdbc.queryForObject("select archived_at is not null from documents where id = ?",
                Boolean.class, documentId)).isTrue();
    }

    @Test
    @DisplayName("an unknown action is refused")
    void unknownActionIsRefused() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId, LocalDate.now());

        assertThatThrownBy(() -> retention.schedule(tenantId, documentId, null, "SHRED",
                LocalDate.now()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a job that is not scheduled any more is not run twice")
    void jobIsNotRunTwice() {
        UUID tenantId = tenant();
        UUID policyId = policy(tenantId, 1);
        UUID documentId = document(tenantId, LocalDate.of(2020, 1, 1));
        retention.verifyPolicy(tenantId, policyId, person());
        UUID jobId = retention.schedule(tenantId, documentId, policyId, "DELETE", LocalDate.now());
        retention.execute(tenantId, jobId, person());

        assertThatThrownBy(() -> retention.execute(tenantId, jobId, person()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a period that is not positive is refused")
    void periodMustBePositive() {
        UUID tenantId = tenant();

        assertThatThrownBy(() -> policy(tenantId, 0))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("positiv");
    }

    @Test
    @DisplayName("a policy of another tenant is not found")
    void otherTenantSeesNothing() {
        UUID owner = tenant();
        UUID stranger = tenant();
        UUID policyId = policy(owner, 12);

        assertThatThrownBy(() -> retention.verifyPolicy(stranger, policyId, person()))
                .isInstanceOf(ApiException.class);
    }
}
