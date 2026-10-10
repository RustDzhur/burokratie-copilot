package de.buerokratiecopilot.shared.tenancy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import de.buerokratiecopilot.shared.context.RequestContext;

/**
 * Binds every database connection to the tenant of the current request before it is used.
 *
 * <p>This makes the PostgreSQL Row Level Security policies of migration {@code V13} effective
 * and, equally important, prevents a pooled connection from leaking a stale tenant: the
 * session variable is written on <em>every</em> connection checkout, and reset to the system
 * context when no tenant is active.</p>
 *
 * <p>Defence in depth: the primary isolation mechanism remains server-side authorization —
 * this class only makes the database refuse to return foreign rows even if a query is wrong.</p>
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

    private static final String SET_TENANT = "SET app.tenant_id = '%s'";
    private static final String RESET_TENANT = "SET app.tenant_id = ''";

    public TenantAwareDataSource(DataSource targetDataSource) {
        super(targetDataSource);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        bindTenant(connection);
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection connection = super.getConnection(username, password);
        bindTenant(connection);
        return connection;
    }

    private void bindTenant(Connection connection) throws SQLException {
        UUID tenantId = RequestContext.tenantId().orElse(null);
        try (Statement statement = connection.createStatement()) {
            // The value comes from a UUID object, never from raw user input, so the
            // interpolation below cannot be used for SQL injection.
            statement.execute(tenantId == null
                    ? RESET_TENANT
                    : SET_TENANT.formatted(tenantId.toString()));
        }
        catch (SQLException ex) {
            log.error("Failed to bind tenant context to the database connection", ex);
            throw ex;
        }
    }
}
