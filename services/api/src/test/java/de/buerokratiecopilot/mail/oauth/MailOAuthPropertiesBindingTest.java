package de.buerokratiecopilot.mail.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The operator's configuration, bound the way Spring Boot binds it.
 *
 * <p>The endpoints, scopes and IMAP hosts have defaults, and only the application registration has to be
 * supplied. A binding that quietly replaced those defaults when the client id was set would leave a
 * deployment with a scope of {@code null} and a mailbox that cannot be read — and the failure would only
 * appear in front of a person trying to connect. This test binds exactly the two values an operator sets
 * and checks that everything else survived.</p>
 */
class MailOAuthPropertiesBindingTest {

    private static MailOAuthProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        // bindOrCreate is what Spring Boot itself does for a @ConfigurationProperties bean: with no property at
        // all the defaults still apply, and that is exactly the case of a fresh installation.
        return binder.bindOrCreate("burokratie.mail.oauth", Bindable.of(MailOAuthProperties.class));
    }

    @Test
    @DisplayName("setting client id and secret keeps the documented endpoints and scopes")
    void defaultsSurviveTheRegistration() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("burokratie.mail.oauth.microsoft.client-id", "an-application-id");
        values.put("burokratie.mail.oauth.microsoft.client-secret", "an-application-secret");
        values.put("burokratie.mail.oauth.google.client-id", "another-application-id");
        values.put("burokratie.mail.oauth.google.client-secret", "another-application-secret");

        MailOAuthProperties properties = bind(values);

        assertThat(properties.getMicrosoft().isRegistered()).isTrue();
        assertThat(properties.getMicrosoft().getAuthorizeUrl())
                .contains("login.microsoftonline.com").contains("{tenantId}");
        assertThat(properties.getMicrosoft().getScope())
                .contains("IMAP.AccessAsUser.All").contains("offline_access");
        assertThat(properties.getMicrosoft().getImapHost()).isEqualTo("outlook.office365.com");
        assertThat(properties.getMicrosoft().getImapPort()).isEqualTo(993);
        assertThat(properties.getMicrosoft().getExtraAuthorizeParameters())
                .containsEntry("prompt", "select_account");
        assertThat(properties.getGoogle().isRegistered()).isTrue();
        assertThat(properties.getGoogle().getScope()).contains("https://mail.google.com/");
        assertThat(properties.getGoogle().getImapHost()).isEqualTo("imap.gmail.com");
        assertThat(properties.getGoogle().getExtraAuthorizeParameters())
                .containsEntry("access_type", "offline")
                .containsEntry("prompt", "consent");
    }

    @Test
    @DisplayName("an installation without an application is reported as not configured, not half-configured")
    void missingApplicationIsReported() {
        MailOAuthProperties properties = bind(Map.of());

        assertThat(properties.getMicrosoft().isRegistered()).isFalse();
        assertThat(properties.getMicrosoft().missing()).contains("client-id", "client-secret");
        assertThat(properties.getGoogle().isRegistered()).isFalse();
        assertThat(properties.getGoogle().missing()).contains("client-id", "client-secret");
    }
}
