package de.buerokratiecopilot.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies legal hold (spec §23, B-016 acceptance).
 *
 * <p>The criterion is that a document under legal hold is not deleted. Legal hold exists because a
 * document may be needed as evidence — during a tax audit, a court case or an investigation — and at
 * that moment a retention counter is irrelevant. The test therefore sets up the case where every other
 * condition for deletion is satisfied, so that only the hold stands in the way.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class LegalHoldIT {

    @Autowired
    private RetentionService retention;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant() {
        return jdbc.queryForObject("""
                insert into tenants (id, slug, name, account_type)
                values (gen_random_uuid(), ?, 'Legal Hold Test', 'COMPANY') returning id
                """, UUID.class, "hold-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private UUID person() {
        return jdbc.queryForObject("""
                insert into users (id, email, status, display_name, locale, time_zone,
                                   failed_login_attempts, must_change_password)
                values (gen_random_uuid(), ?, 'ACTIVE', 'Justiziarin', 'de', 'Europe/Berlin', 0, false)
                returning id
                """, UUID.class, "lh-" + UUID.randomUUID().toString().substring(0, 10) + "@example.de");
    }

    private UUID document(UUID tenantId) {
        String hash = "%064x".formatted(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        return jdbc.queryForObject("""
                insert into documents (id, tenant_id, original_filename, internal_name, mime_type,
                                       size_bytes, content_hash, source, processing_status, review_status,
                                       document_date, received_at, legal_hold)
                values (gen_random_uuid(), ?, 'vertrag.pdf', 'vertrag.pdf', 'application/pdf', 100, ?,
                        'UPLOAD', 'READY', 'NOT_REQUIRED', '2020-01-01'::date, now(), false)
                returning id
                """, UUID.class, tenantId, hash);
    }

    /** A verified policy whose period has long expired, so only the hold can stop the deletion. */
    private UUID deletablePolicy(UUID tenantId) {
        UUID policyId = retention.definePolicy(new RetentionService.PolicyRequest(tenantId, "RE-6",
                "Rechnungen 6 Jahre", null, 6, "DOCUMENT_DATE", "§ 147 AO", LocalDate.of(2020, 1, 1),
                person()));
        retention.verifyPolicy(tenantId, policyId, person());
        return policyId;
    }

    @Test
    @DisplayName("a document under legal hold is not deleted")
    void legalHoldBlocksDeletion() {
        UUID tenantId = tenant();
        UUID policyId = deletablePolicy(tenantId);
        UUID documentId = document(tenantId);
        retention.placeLegalHold(tenantId, documentId, person(), "Steuerprüfung 2026");
        UUID jobId = retention.schedule(tenantId, documentId, policyId, "DELETE", LocalDate.now());

        RetentionService.Outcome outcome = retention.execute(tenantId, jobId, person());

        assertThat(outcome.status()).isEqualTo("BLOCKED");
        assertThat(outcome.reason()).contains("Legal Hold");
        assertThat(jdbc.queryForObject("select deleted_at from documents where id = ?",
                java.sql.Timestamp.class, documentId))
                .as("the document is evidence and stays where it is")
                .isNull();
    }

    @Test
    @DisplayName("a blocked job says why, so the refusal is not mistaken for a forgotten task")
    void blockedJobExplainsItself() {
        UUID tenantId = tenant();
        UUID policyId = deletablePolicy(tenantId);
        UUID documentId = document(tenantId);
        retention.placeLegalHold(tenantId, documentId, person(), "Gerichtsverfahren");
        UUID jobId = retention.schedule(tenantId, documentId, policyId, "DELETE", LocalDate.now());

        retention.execute(tenantId, jobId, person());

        var job = jdbc.queryForMap("select status, blocked_reason, details from retention_jobs "
                + "where id = ?", jobId);
        assertThat(job).containsEntry("status", "BLOCKED");
        assertThat(String.valueOf(job.get("blocked_reason"))).contains("Legal Hold");
        assertThat(String.valueOf(job.get("details"))).contains("LEGAL_HOLD");
    }

    @Test
    @DisplayName("lifting the hold lets the deletion run")
    void liftingTheHoldAllowsDeletion() {
        UUID tenantId = tenant();
        UUID policyId = deletablePolicy(tenantId);
        UUID documentId = document(tenantId);
        retention.placeLegalHold(tenantId, documentId, person(), "Prüfung");
        UUID blockedJob = retention.schedule(tenantId, documentId, policyId, "DELETE", LocalDate.now());
        retention.execute(tenantId, blockedJob, person());

        retention.liftLegalHold(tenantId, documentId, person(), "Prüfung abgeschlossen");
        UUID secondJob = retention.schedule(tenantId, documentId, policyId, "DELETE", LocalDate.now());
        RetentionService.Outcome outcome = retention.execute(tenantId, secondJob, person());

        assertThat(outcome.status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select deleted_at is not null from documents where id = ?",
                Boolean.class, documentId)).isTrue();
    }

    @Test
    @DisplayName("the hold state is queryable, because every deletion path has to ask first")
    void holdStateIsQueryable() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId);

        assertThat(retention.isUnderLegalHold(tenantId, documentId)).isFalse();

        retention.placeLegalHold(tenantId, documentId, person(), "Prüfung");

        assertThat(retention.isUnderLegalHold(tenantId, documentId)).isTrue();
    }

    @Test
    @DisplayName("the documents under hold are listed")
    void heldDocumentsAreListed() {
        UUID tenantId = tenant();
        UUID first = document(tenantId);
        UUID second = document(tenantId);
        retention.placeLegalHold(tenantId, first, person(), "A");
        retention.placeLegalHold(tenantId, second, person(), "B");

        var held = retention.underLegalHold(tenantId);

        assertThat(held).hasSize(2);
        assertThat(held).extracting(row -> row.get("id")).contains(first, second);
    }

    @Test
    @DisplayName("placing and lifting a hold are recorded in the audit trail")
    void holdChangesAreAudited() {
        UUID tenantId = tenant();
        UUID documentId = document(tenantId);

        retention.placeLegalHold(tenantId, documentId, person(), "Prüfung");
        retention.liftLegalHold(tenantId, documentId, person(), "beendet");

        var entries = jdbc.queryForList("""
                select action, actor_type, category, result from audit_events
                where tenant_id = ? and entity_id = ? order by created_at
                """, tenantId, documentId);

        assertThat(entries).hasSize(2);
        assertThat(entries.getFirst()).containsEntry("action", "LEGAL_HOLD_PLACED")
                .containsEntry("actor_type", "USER");
        assertThat(entries.get(1)).containsEntry("action", "LEGAL_HOLD_LIFTED");
    }

    @Test
    @DisplayName("a hold survives the deletion of nothing and applies to an unknown document not at all")
    void holdOnUnknownDocumentIsReported() {
        UUID tenantId = tenant();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> retention.placeLegalHold(tenantId, UUID.randomUUID(), person(), "x")))
                .isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);
    }

    @Test
    @DisplayName("a hold of another tenant does not apply here")
    void anotherTenantCannotPlaceAHold() {
        UUID owner = tenant();
        UUID stranger = tenant();
        UUID documentId = document(owner);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> retention.placeLegalHold(stranger, documentId, person(), "x")))
                .isInstanceOf(de.buerokratiecopilot.shared.error.ApiException.class);
        assertThat(retention.isUnderLegalHold(owner, documentId)).isFalse();
    }
}
