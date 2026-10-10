package de.buerokratiecopilot.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Provides a database that only the migration tests touch.
 *
 * <p>Why a separate database: {@code SchemaIT} and {@code FlywayMigrationIT} drop and recreate the
 * whole schema to prove that the migrations run on an empty database. Doing that in the database the
 * other integration tests use leaves stale prepared statements in their connection pool, and
 * PostgreSQL then fails the next query with "cached plan must not change result type" — a flaky
 * failure that looks like a product bug but is caused by the test setup.</p>
 *
 * <p>The database is created on demand, so both a local run and CI work without extra preparation.</p>
 */
public final class MigrationTestDatabase {

    private MigrationTestDatabase() {
    }

    public static String url() {
        return "jdbc:postgresql://%s:%s/%s".formatted(host(), port(), name());
    }

    public static String name() {
        return env("BC_MIGRATION_TEST_DB_NAME", "burokratie_migration_test");
    }

    public static String user() {
        return env("BC_TEST_DB_USER", "burokratie");
    }

    public static String password() {
        return env("BC_TEST_DB_PASSWORD", "burokratie_dev_password");
    }

    /** Creates the database when it is missing. Requires the role to have CREATEDB. */
    public static void ensureExists() throws Exception {
        String adminUrl = "jdbc:postgresql://%s:%s/postgres".formatted(host(), port());
        try (Connection connection = DriverManager.getConnection(adminUrl, user(), password());
             Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname = '" + name() + "'")) {
                if (rs.next()) {
                    return;
                }
            }
            statement.execute("CREATE DATABASE " + name() + " OWNER " + user());
        }
    }

    private static String host() {
        return env("BC_TEST_DB_HOST", "localhost");
    }

    private static String port() {
        return env("BC_TEST_DB_PORT", "5432");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
