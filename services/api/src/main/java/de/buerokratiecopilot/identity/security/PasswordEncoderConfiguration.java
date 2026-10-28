package de.buerokratiecopilot.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Password hashing is needed by every runtime, including the {@code worker} profile which has no
 * servlet context. It therefore lives in its own configuration instead of the web-only
 * {@code SecurityConfiguration}, where it would not be created without an {@code HttpSecurity}
 * bean and the whole application context would fail to start.
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfiguration {

    @Bean
    @Primary
    public PasswordEncoder passwordEncoder() {
        return SecurityTokens.passwordEncoder();
    }
}
