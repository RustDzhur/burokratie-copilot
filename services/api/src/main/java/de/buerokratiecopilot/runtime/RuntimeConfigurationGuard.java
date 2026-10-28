package de.buerokratiecopilot.runtime;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import de.buerokratiecopilot.license.LicenseProperties;
import de.buerokratiecopilot.shared.config.BurokratieProperties;

/**
 * Refuses to run the API-only profile with a configuration that would silently drop a protection.
 *
 * <p>The ordinary production guard only fires under {@code prod}. The runtime profile is not
 * {@code prod} (it has no browser session, no SMTP and no public frontend), so without this
 * check an installer mistake — an empty encryption key, registration left open, OCR switched
 * off — would start a healthy-looking installation with a weaker contract than the product
 * promises.</p>
 */
@Component
@Profile("runtime")
public class RuntimeConfigurationGuard implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(RuntimeConfigurationGuard.class);

    private final BurokratieProperties properties;
    private final RuntimeProperties runtime;
    private final LicenseProperties license;
    private final Environment environment;

    public RuntimeConfigurationGuard(BurokratieProperties properties, RuntimeProperties runtime,
                                     LicenseProperties license, Environment environment) {
        this.properties = properties;
        this.runtime = runtime;
        this.license = license;
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<String> problems = new ArrayList<>();

        String key = properties.getSecurity().getEncryption().getKeyBase64();
        if (key == null || key.isBlank()) {
            problems.add("BC_ENCRYPTION_KEY is missing: encrypted integration credentials could not be read");
        }
        else if (!isAes256Key(key)) {
            problems.add("BC_ENCRYPTION_KEY must be base64 of exactly 32 bytes (AES-256)");
        }
        if (properties.getSecurity().isRegistrationOpen()) {
            problems.add("BC_REGISTRATION_OPEN must be false: the runtime is token-only");
        }
        if (!properties.getOcr().isEnabled()
                && !environment.getProperty("BC_ALLOW_NO_OCR", Boolean.class, false)) {
            problems.add("BC_OCR_ENABLED must be true (or BC_ALLOW_NO_OCR=true to accept the loss of OCR)");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start the runtime profile with an "
                    + "incomplete configuration:\n  - " + String.join("\n  - ", problems));
        }

        if (runtime.getAdminToken() == null || runtime.getAdminToken().isBlank()) {
            log.warn("BC_RUNTIME_ADMIN_TOKEN is empty: bootstrap and token administration are disabled. "
                    + "Set it in the environment file and restart to issue tokens.");
        }
        if (!license.isEnforced()) {
            log.warn("Licence enforcement is off in the runtime profile; this should only be used in tests.");
        }
    }

    private static boolean isAes256Key(String base64) {
        try {
            return java.util.Base64.getDecoder().decode(base64).length == 32;
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
