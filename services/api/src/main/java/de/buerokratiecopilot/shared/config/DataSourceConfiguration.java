package de.buerokratiecopilot.shared.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

import de.buerokratiecopilot.shared.tenancy.TenantAwareDataSource;

/**
 * Builds the application {@link DataSource} explicitly so that every connection is wrapped
 * by {@link TenantAwareDataSource} (tenant binding for Row Level Security).
 *
 * <p>Configuration properties are still read from {@code spring.datasource.*}, so the
 * {@code application*.yml} files remain the single source of truth.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfiguration {

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource hikari = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        hikari.setPoolName("burokratie-pool");
        return new TenantAwareDataSource(hikari);
    }
}
