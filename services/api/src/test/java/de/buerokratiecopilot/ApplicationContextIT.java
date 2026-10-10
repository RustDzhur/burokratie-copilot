package de.buerokratiecopilot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Boots the whole application context against a real PostgreSQL database.
 *
 * <p>This is the foundation smoke test: it proves that configuration binding, the
 * DataSource, Flyway migrations and the JPA/Hibernate setup are mutually consistent.
 * It intentionally uses the real database (spec §28.1 — no in-memory substitution).</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextIT {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private BurokratieProperties properties;

    @Test
    void contextLoadsAndConfigurationIsBound() {
        assertThat(context).isNotNull();
        assertThat(properties.getSecurity().getSession().getCookieName()).isEqualTo("bc_session");
        assertThat(properties.getSecurity().getPassword().getMinLength()).isEqualTo(12);
        assertThat(properties.getStorage().getType()).isEqualTo("local-fs");
        assertThat(properties.getAi().isEnabled()).isFalse();
    }
}
