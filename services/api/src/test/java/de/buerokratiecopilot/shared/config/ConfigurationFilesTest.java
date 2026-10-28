package de.buerokratiecopilot.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Every profile file must be readable as it is. The tests run with the test profile and never read the production file, so a
 * mistake there (a key written twice, as happened once) stops the production start and nothing else notices. Here the files are
 * parsed with duplicate keys forbidden.
 */
class ConfigurationFilesTest {

    @Test
    @DisplayName("application.yml and every profile file parse without a key written twice")
    void profileFilesAreValidYaml() throws Exception {
        for (String name : List.of("application.yml", "application-prod.yml", "application-dev.yml", "application-worker.yml")) {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            try (InputStream in = new ClassPathResource(name).getInputStream()) {
                Object parsed = new Yaml(new SafeConstructor(options)).load(in);
                assertThat(parsed).as(name).isInstanceOf(Map.class);
            }
        }
    }

    @Test
    @DisplayName("the production profile requires the acceptance of the terms and keeps the secure session cookie")
    void productionKeepsItsSettings() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream in = new ClassPathResource("application-prod.yml").getInputStream()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> prod = (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(in);
            @SuppressWarnings("unchecked")
            Map<String, Object> security = (Map<String, Object>) ((Map<String, Object>) prod.get("burokratie")).get("security");
            assertThat(security.get("require-terms-acceptance")).isEqualTo(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> session = (Map<String, Object>) security.get("session");
            assertThat(session.get("cookie-secure")).isEqualTo(true);
        }
    }
}
