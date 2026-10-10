package de.buerokratiecopilot.shared.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * A production instance must not start with a configuration that silently weakens security
 * (spec §1.2, §21). Outside production the same gaps are only reported.
 */
class ProductionConfigurationGuardTest {

    private static BurokratieProperties properties(String encryptionKey, boolean cookieSecure) {
        BurokratieProperties properties = new BurokratieProperties();
        properties.getSecurity().getEncryption().setKeyBase64(encryptionKey);
        properties.getSecurity().getSession().setCookieSecure(cookieSecure);
        return properties;
    }

    @Test
    @DisplayName("production refuses to start without an encryption key")
    void productionRequiresEncryptionKey() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var guard = new ProductionConfigurationGuard(environment, properties("", true));

        assertThatThrownBy(() -> guard.onApplicationEvent(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BC_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("production refuses a key that is not a 32 byte AES key")
    void productionRequiresValidKeyLength() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var guard = new ProductionConfigurationGuard(environment, properties("c2hvcnQ=", true));

        assertThatThrownBy(() -> guard.onApplicationEvent(null))
                .hasMessageContaining("32 byte");
    }

    @Test
    @DisplayName("production refuses an insecure session cookie")
    void productionRequiresSecureCookie() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var guard = new ProductionConfigurationGuard(environment,
                properties("YWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWE=", false));

        assertThatThrownBy(() -> guard.onApplicationEvent(null))
                .hasMessageContaining("BC_COOKIE_SECURE");
    }

    @Test
    @DisplayName("a development profile logs the gaps instead of refusing to start")
    void developmentOnlyWarns() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        var guard = new ProductionConfigurationGuard(environment, properties("", false));

        assertThatCode(() -> guard.onApplicationEvent(null)).doesNotThrowAnyException();
    }
}
