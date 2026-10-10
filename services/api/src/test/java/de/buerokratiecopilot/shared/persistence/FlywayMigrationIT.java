package de.buerokratiecopilot.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Flyway migrations against a <em>real, clean</em> PostgreSQL database.
 *
 * <p>Docker is unavailable in the reference environment (see implementation plan Q-01), so
 * integration tests use a real PostgreSQL server instead of Testcontainers. The schema is
 * dropped and recreated before migrating, which is the closest equivalent of testing on an
 * empty database (spec §34: "Миграции проходят на чистой PostgreSQL").</p>
 */
class FlywayMigrationIT {

    private static final String URL = "jdbc:postgresql://%s:%s/%s".formatted(
            env("BC_TEST_DB_HOST", "localhost"),
            env("BC_TEST_DB_PORT", "5432"),
            env("BC_TEST_DB_NAME", "burokratie_test"));
    private static final String USER = env("BC_TEST_DB_USER", "burokratie");
    private static final String PASSWORD = env("BC_TEST_DB_PASSWORD", "burokratie_dev_password");

    @BeforeAll
    static void resetSchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
    }

    @Test
    @DisplayName("all migrations apply on an empty schema and validate cleanly")
    void migrationsApplyOnCleanDatabase() throws Exception {
        MigrateResult result = flyway().migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted)
                .as("every migration file must be executed on a clean database")
                .isEqualTo(countMigrationFiles());
        assertThat(result.targetSchemaVersion).isNotBlank();

        // Second run must be a no-op: migrations are repeatable in effect.
        MigrateResult second = flyway().migrate();
        assertThat(second.migrationsExecuted).isZero();

        flyway().validateWithResult();
    }

    @Test
    @DisplayName("extensions required by the design are installed")
    void requiredExtensionsAreInstalled() throws Exception {
        flyway().migrate();

        try (Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT extname FROM pg_extension WHERE extname IN ('pgcrypto','citext','pg_trgm')")) {
            List<String> extensions = new ArrayList<>();
            while (rs.next()) {
                extensions.add(rs.getString(1));
            }
            assertThat(extensions).containsExactlyInAnyOrder("pgcrypto", "citext", "pg_trgm");
        }
    }

    private static int countMigrationFiles() throws Exception {
        // Counted from the classpath so the assertion stays honest when new migrations appear.
        var url = FlywayMigrationIT.class.getClassLoader().getResource("db/migration");
        assertThat(url).isNotNull();
        java.nio.file.Path path = java.nio.file.Paths.get(url.toURI());
        try (var stream = java.nio.file.Files.list(path)) {
            return (int) stream.filter(p -> p.getFileName().toString().endsWith(".sql")).count();
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
