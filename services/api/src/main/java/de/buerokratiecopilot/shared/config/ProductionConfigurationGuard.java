package de.buerokratiecopilot.shared.config;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a production instance with an unusable configuration.
 *
 * <p>Rationale (spec §1.2, §21, §31): a production deployment without an encryption key, without
 * mail or with a disabled session cookie would look healthy while silently losing security
 * properties — for example by storing encrypted integration credentials with a key that changes
 * on every restart. Failing fast during deployment is the only safe behaviour.</p>
 *
 * <p>In development and test nothing is enforced; instead the gaps are reported at WARN level so
 * a developer knows which feature is currently inactive.</p>
 */
@Component
public class ProductionConfigurationGuard implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigurationGuard.class);

    private final Environment environment;
    private final BurokratieProperties properties;
    private final org.springframework.beans.factory.ObjectProvider<de.buerokratiecopilot.legal.LegalService> legal;

    /** Without the imprint check; for the callers that only look at the configuration. */
    public ProductionConfigurationGuard(Environment environment, BurokratieProperties properties) {
        this(environment, properties, new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(de.buerokratiecopilot.legal.LegalService.class));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductionConfigurationGuard(Environment environment, BurokratieProperties properties,
                                        org.springframework.beans.factory.ObjectProvider<de.buerokratiecopilot.legal.LegalService> legal) {
        this.environment = environment;
        this.properties = properties;
        this.legal = legal;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<String> problems = new ArrayList<>();
        List<String> notices = new ArrayList<>();

        String encryptionKey = properties.getSecurity().getEncryption().getKeyBase64();
        if (encryptionKey == null || encryptionKey.isBlank()) {
            problems.add("burokratie.security.encryption.key-base64 (BC_ENCRYPTION_KEY) is missing: "
                    + "secrets at rest could not be encrypted");
        }
        else if (!isValidAesKey(encryptionKey)) {
            problems.add("BC_ENCRYPTION_KEY must be a base64 encoded 32 byte key (AES-256)");
        }

        if (!properties.getSecurity().getSession().isCookieSecure()) {
            problems.add("BC_COOKIE_SECURE must be true in production: the session cookie would be "
                    + "sent over plain HTTP");
        }

        if (!"Strict".equalsIgnoreCase(properties.getSecurity().getSession().getCookieSameSite())) {
            notices.add("Session cookie SameSite is "
                    + properties.getSecurity().getSession().getCookieSameSite()
                    + "; 'Strict' is recommended for an administrative application");
        }

        try {
            var legalService = legal.getIfAvailable();
            if (legalService != null && !legalService.missing().isEmpty()) {
                // Not fatal, but the imprint is a legal duty (§ 5 DDG): the operator enters it in the admin area.
                notices.add("The operator's imprint data is incomplete (missing: " + legalService.missing()
                        + "); enter it in the admin area before the platform is offered to the public");
            }
        }
        catch (RuntimeException ex) {
            notices.add("The imprint data could not be checked: " + ex.getMessage());
        }

        if (environment.getProperty("spring.mail.host") == null) {
            notices.add("No SMTP host configured: verification, invitation and reset e-mails cannot "
                    + "be delivered");
        }

        if (properties.getAi().isEnabled() && isBlank(properties.getAi().getApiKey())) {
            problems.add("AI is enabled but no API key is configured");
        }
        if (properties.getAi().isEnabled() && properties.getAi().getMonthlyBudgetEur() <= 0) {
            problems.add("AI is enabled but no monthly budget is configured: cost control would be "
                    + "impossible (spec §10.1)");
        }

        if ("s3".equalsIgnoreCase(properties.getStorage().getType())
                && (isBlank(properties.getStorage().getS3().getAccessKey())
                    || isBlank(properties.getStorage().getS3().getSecretKey()))) {
            problems.add("Object storage type is 's3' but no credentials are configured");
        }

        // Files are checked before they are processed and scans are read (spec §34). The defaults switch both
        // off, so a production start that never set them would run without either and say nothing. The operator
        // can accept that explicitly (BC_ALLOW_UNSCANNED / BC_ALLOW_NO_OCR) - what is not allowed is not choosing.
        if ("none".equalsIgnoreCase(properties.getSecurity().getMalwareScanner().getType())
                && !environment.getProperty("BC_ALLOW_UNSCANNED", Boolean.class, false)) {
            problems.add("No malware scanner is configured (BC_MALWARE_SCANNER=none): uploaded files would "
                    + "be processed unchecked. Set BC_MALWARE_SCANNER=clamav-cli, or BC_ALLOW_UNSCANNED=true "
                    + "to accept the risk knowingly");
        }
        if (!properties.getOcr().isEnabled() && !environment.getProperty("BC_ALLOW_NO_OCR", Boolean.class, false)) {
            problems.add("OCR is switched off (BC_OCR_ENABLED=false): scans and photographs would not be "
                    + "read. Set BC_OCR_ENABLED=true, or BC_ALLOW_NO_OCR=true to accept that knowingly");
        }

        boolean production = environment.matchesProfiles("prod");
        if (production && !problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start in production with an incomplete "
                    + "configuration:\n  - " + String.join("\n  - ", problems));
        }
        if (!production) {
            problems.forEach(problem -> log.warn("Configuration gap (not fatal outside production): {}", problem));
        }
        notices.forEach(notice -> log.info("Configuration notice: {}", notice));
    }

    private static boolean isValidAesKey(String base64) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            return decoded.length == 32;
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
