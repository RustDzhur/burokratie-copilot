package de.buerokratiecopilot.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural verification of the data model against the entity list required by spec §19.
 *
 * <p>The list below is copied from the specification; the test fails if a table required by
 * the specification is missing, so "the data model is complete" is a checked claim and not
 * an assumption.</p>
 */
class SchemaIT {

    private static final String URL = de.buerokratiecopilot.support.MigrationTestDatabase.url();
    private static final String USER = de.buerokratiecopilot.support.MigrationTestDatabase.user();
    private static final String PASSWORD = de.buerokratiecopilot.support.MigrationTestDatabase.password();

    /** Every table explicitly required by spec §19. */
    private static final List<String> REQUIRED_TABLES = List.of(
            // users and organization
            "users", "user_profiles", "organizations", "tenants", "workspaces", "memberships",
            "roles", "permissions", "role_permissions", "membership_roles", "invitations",
            "sessions", "mfa_methods",
            // documents
            "documents", "document_versions", "document_blobs", "document_pages",
            "document_extractions", "document_classifications", "document_tags",
            "document_relations", "document_sources", "document_reviews",
            "document_retention_policies", "document_access_logs",
            // cases and contacts
            "cases", "case_participants", "case_documents", "case_events", "contacts",
            "contact_addresses", "organizations_contacts", "contact_relations",
            // tasks and workflow
            "tasks", "task_assignees", "task_comments", "task_checklist_items", "task_events",
            "workflow_definitions", "workflow_versions", "workflow_runs", "workflow_run_steps",
            "workflow_events", "approvals", "approval_events",
            // contracts and finance
            "contracts", "contract_parties", "contract_clauses", "contract_events",
            "invoices", "invoice_lines", "credit_notes", "payments", "payment_matches",
            "financial_accounts", "suppliers", "customers", "purchase_orders", "payment_approvals",
            // mail and communication
            "mail_accounts", "mail_messages", "mail_attachments", "outbound_messages",
            "message_delivery_events", "document_templates", "template_versions",
            "template_fields", "generated_documents",
            // integrations and devices
            "integrations", "integration_credentials", "integration_sync_runs",
            "webhook_endpoints", "webhook_events", "devices", "device_jobs", "scan_sessions",
            // system data
            "notifications", "notification_preferences", "audit_events", "ai_jobs",
            "ai_model_runs", "ai_usage_records", "system_settings", "feature_flags",
            "retention_jobs", "outbox_events", "idempotency_records");

    @BeforeAll
    static void migrate() throws Exception {
        de.buerokratiecopilot.support.MigrationTestDatabase.ensureExists();
        // Start from an empty schema so this test always proves "migrations run on a
        // clean PostgreSQL", independent of the order in which test classes execute.
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
        Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    @Test
    @DisplayName("every table required by spec §19 exists")
    void allRequiredTablesExist() throws Exception {
        Set<String> actual = new TreeSet<>(queryStrings(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"));

        assertThat(actual).containsAll(REQUIRED_TABLES);

        List<String> missing = REQUIRED_TABLES.stream().filter(t -> !actual.contains(t)).toList();
        assertThat(missing).as("tables required by §19 but missing from the schema").isEmpty();
    }

    @Test
    @DisplayName("tenant scoped tables carry an indexed tenant_id column")
    void tenantScopedTablesAreIndexedByTenant() throws Exception {
        Set<String> indexed = new TreeSet<>(queryStrings("""
                SELECT DISTINCT t.relname
                FROM pg_index i
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace AND n.nspname = 'public'
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (i.indkey::int2[])
                WHERE t.relkind = 'r' AND a.attname = 'tenant_id'
                """));

        // Spot-check the tables that are always queried per tenant.
        assertThat(indexed).contains(
                "documents", "cases", "tasks", "invoices", "contracts", "mail_messages",
                "audit_events", "notifications");
    }

    @Test
    @DisplayName("a tenant cannot see another tenant's rows (RLS)")
    void rowLevelSecurityIsEnforced() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO tenants (id, slug, name, account_type) VALUES
                    ('11111111-1111-1111-1111-111111111111', 'rls-a', 'Tenant A', 'COMPANY'),
                    ('22222222-2222-2222-2222-222222222222', 'rls-b', 'Tenant B', 'COMPANY')
                    ON CONFLICT (slug) DO NOTHING
                    """);

            // Keep the probe deterministic when the database is reused between runs.
            statement.execute("""
                    DELETE FROM approvals WHERE subject_id IN
                    ('33333333-3333-3333-3333-333333333333', '44444444-4444-4444-4444-444444444444')
                    """);

            // Tenant A writes its own row.
            statement.execute("SET app.tenant_id = '11111111-1111-1111-1111-111111111111'");
            statement.execute("""
                    INSERT INTO approvals (tenant_id, subject_type, subject_id, approval_type,
                                           title, automation_level)
                    VALUES ('11111111-1111-1111-1111-111111111111', 'DOCUMENT',
                            '33333333-3333-3333-3333-333333333333', 'OTHER', 'Tenant A approval', 4)
                    """);

            // Tenant B cannot be impersonated from tenant A's session: WITH CHECK rejects
            // an insert that claims another tenant (§3.2 "tenant from the body is ignored").
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO approvals (tenant_id, subject_type, subject_id, approval_type,
                                           title, automation_level)
                    VALUES ('22222222-2222-2222-2222-222222222222', 'DOCUMENT',
                            '44444444-4444-4444-4444-444444444444', 'OTHER', 'Tenant B approval', 4)
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");

            // Tenant B writes its own row in its own session.
            statement.execute("SET app.tenant_id = '22222222-2222-2222-2222-222222222222'");
            statement.execute("""
                    INSERT INTO approvals (tenant_id, subject_type, subject_id, approval_type,
                                           title, automation_level)
                    VALUES ('22222222-2222-2222-2222-222222222222', 'DOCUMENT',
                            '44444444-4444-4444-4444-444444444444', 'OTHER', 'Tenant B approval', 4)
                    """);

            statement.execute("SET app.tenant_id = '11111111-1111-1111-1111-111111111111'");

            try (ResultSet rs = statement.executeQuery("SELECT title FROM approvals ORDER BY title")) {
                List<String> visible = new ArrayList<>();
                while (rs.next()) {
                    visible.add(rs.getString(1));
                }
                assertThat(visible)
                        .as("a session bound to tenant A must not see tenant B rows")
                        .containsExactly("Tenant A approval");
            }

            statement.execute("SET app.tenant_id = ''");
            try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM approvals")) {
                rs.next();
                assertThat(rs.getInt(1)).as("system context sees all rows").isEqualTo(2);
            }
        }
    }

    @Test
    @DisplayName("audit_events is append-only: UPDATE and DELETE are rejected")
    void auditEventsCannotBeModifiedOrDeleted() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO audit_events (category, action, description)
                    VALUES ('ADMIN', 'TEST_PROBE', 'append-only probe')
                    """);

            assertThatThrownBy(() -> statement.execute(
                    "UPDATE audit_events SET description = 'tampered' WHERE action = 'TEST_PROBE'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");

            assertThatThrownBy(() -> statement.execute(
                    "DELETE FROM audit_events WHERE action = 'TEST_PROBE'"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
        }
    }

    @Test
    @DisplayName("CHECK constraints reject impossible data instead of storing it")
    void checkConstraintsRejectInvalidData() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.tenant_id = ''");

            // A case cannot be closed without a closing timestamp (§11.2).
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO cases (tenant_id, reference, title, status)
                    VALUES ('11111111-1111-1111-1111-111111111111', 'CASE-BAD-1', 'Bad', 'CLOSED')
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("cases_closed_consistency_chk");

            // A payment cannot be executed without a confirming user (§14.4, §32).
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO payments (tenant_id, direction, amount, status, executed_at)
                    VALUES ('11111111-1111-1111-1111-111111111111', 'OUTGOING', 100, 'EXECUTED', now())
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("payments_executed_chk");

            // A promised delivery cannot be claimed without evidence (§15.3).
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO message_delivery_events
                        (outbound_message_id, tenant_id, event_type, proves_delivery)
                    VALUES ('55555555-5555-5555-5555-555555555555',
                            '11111111-1111-1111-1111-111111111111',
                            'ACCEPTED_BY_PROVIDER', true)
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("message_delivery_events_proof_chk");

            // A contract cannot end before it starts (§13).
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO contracts (tenant_id, title, contract_type, start_date, end_date)
                    VALUES ('11111111-1111-1111-1111-111111111111', 'Bad period', 'RENT',
                            DATE '2026-06-01', DATE '2026-01-01')
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("contracts_period_chk");

            // Content integrity: a malformed hash must never be stored (§6.2, §22).
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO documents (tenant_id, original_filename, internal_name, mime_type,
                                           size_bytes, content_hash)
                    VALUES ('11111111-1111-1111-1111-111111111111', 'x.pdf', 'x.pdf',
                            'application/pdf', 10, 'not-a-sha256')
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("documents_hash_format_chk");
        }
    }

    private static List<String> queryStrings(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
