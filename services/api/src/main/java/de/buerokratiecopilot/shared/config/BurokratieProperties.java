package de.buerokratiecopilot.shared.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed application configuration for everything under the {@code burokratie.*} prefix.
 *
 * <p>All security relevant values (keys, credentials, provider URLs) are supplied through
 * environment variables — never hard-coded (spec §1.2, §21). Values that represent business
 * rules are deliberately <em>not</em> defined here: they live in the database with versioning
 * so that a domain expert can change them without a redeployment (spec §31).</p>
 */
@ConfigurationProperties(prefix = "burokratie")
public class BurokratieProperties {

    private final Security security = new Security();
    private final Storage storage = new Storage();
    private final Mail mail = new Mail();
    private final Ai ai = new Ai();
    private final Ocr ocr = new Ocr();
    private final Outbox outbox = new Outbox();
    private final Upload upload = new Upload();
    private final Worker worker = new Worker();

    public Security getSecurity() {
        return security;
    }

    public Storage getStorage() {
        return storage;
    }

    public Mail getMail() {
        return mail;
    }

    public Ai getAi() {
        return ai;
    }

    public Ocr getOcr() {
        return ocr;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public Upload getUpload() {
        return upload;
    }

    public Worker getWorker() {
        return worker;
    }

    public static class Security {

        private String publicBaseUrl = "http://localhost:3000";
        /**
         * Whether anybody may open an account. A public installation closes it once the owner has registered:
         * the endpoint is reachable from the internet and each registration creates a tenant.
         */
        private boolean registrationOpen = true;
        private final Session session = new Session();
        private final Password password = new Password();
        private final Login login = new Login();
        private final Mfa mfa = new Mfa();
        private final Encryption encryption = new Encryption();
        private final MalwareScanner malwareScanner = new MalwareScanner();

        public String getPublicBaseUrl() {
            return publicBaseUrl;
        }

        public boolean isRegistrationOpen() {
            return registrationOpen;
        }

        public void setRegistrationOpen(boolean registrationOpen) {
            this.registrationOpen = registrationOpen;
        }

        public void setPublicBaseUrl(String publicBaseUrl) {
            this.publicBaseUrl = publicBaseUrl;
        }

        public Session getSession() {
            return session;
        }

        public Password getPassword() {
            return password;
        }

        public Login getLogin() {
            return login;
        }

        public Mfa getMfa() {
            return mfa;
        }

        public Encryption getEncryption() {
            return encryption;
        }

        public MalwareScanner getMalwareScanner() {
            return malwareScanner;
        }

        public static class Session {

            private String cookieName = "bc_session";
            private Duration idleTimeout = Duration.ofHours(12);
            private Duration absoluteTimeout = Duration.ofDays(30);
            private boolean cookieSecure = false;
            private String cookieSameSite = "Lax";

            public String getCookieName() {
                return cookieName;
            }

            public void setCookieName(String cookieName) {
                this.cookieName = cookieName;
            }

            public Duration getIdleTimeout() {
                return idleTimeout;
            }

            public void setIdleTimeout(Duration idleTimeout) {
                this.idleTimeout = idleTimeout;
            }

            public Duration getAbsoluteTimeout() {
                return absoluteTimeout;
            }

            public void setAbsoluteTimeout(Duration absoluteTimeout) {
                this.absoluteTimeout = absoluteTimeout;
            }

            public boolean isCookieSecure() {
                return cookieSecure;
            }

            public void setCookieSecure(boolean cookieSecure) {
                this.cookieSecure = cookieSecure;
            }

            public String getCookieSameSite() {
                return cookieSameSite;
            }

            public void setCookieSameSite(String cookieSameSite) {
                this.cookieSameSite = cookieSameSite;
            }
        }

        public static class Password {

            private int minLength = 12;
            private int maxLength = 128;

            public int getMinLength() {
                return minLength;
            }

            public void setMinLength(int minLength) {
                this.minLength = minLength;
            }

            public int getMaxLength() {
                return maxLength;
            }

            public void setMaxLength(int maxLength) {
                this.maxLength = maxLength;
            }
        }

        public static class Login {

            private int maxFailedAttempts = 5;
            private Duration lockoutDuration = Duration.ofMinutes(15);

            public int getMaxFailedAttempts() {
                return maxFailedAttempts;
            }

            public void setMaxFailedAttempts(int maxFailedAttempts) {
                this.maxFailedAttempts = maxFailedAttempts;
            }

            public Duration getLockoutDuration() {
                return lockoutDuration;
            }

            public void setLockoutDuration(Duration lockoutDuration) {
                this.lockoutDuration = lockoutDuration;
            }
        }

        public static class Mfa {

            private String issuer = "Bürokratie-Copilot";
            private int allowedClockDriftSeconds = 30;
            private int recoveryCodeCount = 10;

            public String getIssuer() {
                return issuer;
            }

            public void setIssuer(String issuer) {
                this.issuer = issuer;
            }

            public int getAllowedClockDriftSeconds() {
                return allowedClockDriftSeconds;
            }

            public void setAllowedClockDriftSeconds(int allowedClockDriftSeconds) {
                this.allowedClockDriftSeconds = allowedClockDriftSeconds;
            }

            public int getRecoveryCodeCount() {
                return recoveryCodeCount;
            }

            public void setRecoveryCodeCount(int recoveryCodeCount) {
                this.recoveryCodeCount = recoveryCodeCount;
            }
        }

        /**
         * Antivirus component (spec §21.2). {@code none} is a legitimate configuration, but then the
         * platform records files as "not checked" instead of pretending they were scanned.
         */
        public static class MalwareScanner {

            /** {@code none} or {@code clamav-cli}. */
            private String type = "none";
            private String executablePath = "clamscan";
            private Duration timeout = Duration.ofMinutes(2);
            private String spoolDirectory = "";

            public String getType() {
                return type;
            }

            public void setType(String type) {
                this.type = type;
            }

            public String getExecutablePath() {
                return executablePath;
            }

            public void setExecutablePath(String executablePath) {
                this.executablePath = executablePath;
            }

            public Duration getTimeout() {
                return timeout;
            }

            public void setTimeout(Duration timeout) {
                this.timeout = timeout;
            }

            public String getSpoolDirectory() {
                return spoolDirectory;
            }

            public void setSpoolDirectory(String spoolDirectory) {
                this.spoolDirectory = spoolDirectory;
            }
        }

        public static class Encryption {

            /** Base64 encoded 32-byte AES-256 key used for data at rest (spec §21.1). */
            private String keyBase64 = "";

            public String getKeyBase64() {
                return keyBase64;
            }

            public void setKeyBase64(String keyBase64) {
                this.keyBase64 = keyBase64;
            }
        }
    }

    public static class Storage {

        /** {@code local-fs} for development/offline, {@code s3} for S3-compatible storage. */
        private String type = "local-fs";
        private final Local local = new Local();
        private final S3 s3 = new S3();

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Local getLocal() {
            return local;
        }

        public S3 getS3() {
            return s3;
        }

        public static class Local {

            private String basePath = "./data/blobs";

            public String getBasePath() {
                return basePath;
            }

            public void setBasePath(String basePath) {
                this.basePath = basePath;
            }
        }

        public static class S3 {

            private String endpoint = "";
            private String region = "eu-central-1";
            private String bucket = "burokratie";
            private String accessKey = "";
            private String secretKey = "";
            private boolean pathStyleAccess = true;

            public String getEndpoint() {
                return endpoint;
            }

            public void setEndpoint(String endpoint) {
                this.endpoint = endpoint;
            }

            public String getRegion() {
                return region;
            }

            public void setRegion(String region) {
                this.region = region;
            }

            public String getBucket() {
                return bucket;
            }

            public void setBucket(String bucket) {
                this.bucket = bucket;
            }

            public String getAccessKey() {
                return accessKey;
            }

            public void setAccessKey(String accessKey) {
                this.accessKey = accessKey;
            }

            public String getSecretKey() {
                return secretKey;
            }

            public void setSecretKey(String secretKey) {
                this.secretKey = secretKey;
            }

            public boolean isPathStyleAccess() {
                return pathStyleAccess;
            }

            public void setPathStyleAccess(boolean pathStyleAccess) {
                this.pathStyleAccess = pathStyleAccess;
            }
        }
    }

    public static class Mail {

        private String from = "no-reply@buerokratie-copilot.local";

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }
    }

    public static class Ai {

        /** External AI must be switchable off entirely (spec §10.1). */
        private boolean enabled = false;
        private String provider = "none";
        private String baseUrl = "";
        private String apiKey = "";
        private double monthlyBudgetEur = 0;
        /** Model name sent to the provider (for example gpt-4o-mini or deepseek-chat). */
        private String model = "";
        /** How many questions one workspace may ask Iris per day. */
        private int guideDailyLimit = 100;
        /**
         * Whether a document may be sent to the AI by itself when the platform's own reading left a value it needs
         * unread (typically handwriting). A person's click on "read with AI" never depends on this.
         */
        private boolean autoRead = true;
        /** The most documents one workspace has read by AI on its own per day: a ceiling on cost whatever happens. */
        private int autoReadDailyLimit = 20;
        /**
         * Price per million tokens in euro, as the provider's price list says. Providers do not report the cost of a
         * call, so the monthly budget can only be kept if the platform computes it from the tokens; 0 means "not
         * configured" and the budget then counts nothing.
         */
        private java.math.BigDecimal priceInputPerMillion = java.math.BigDecimal.ZERO;
        private java.math.BigDecimal priceOutputPerMillion = java.math.BigDecimal.ZERO;

        public boolean isAutoRead() {
            return autoRead;
        }

        public void setAutoRead(boolean autoRead) {
            this.autoRead = autoRead;
        }

        public int getAutoReadDailyLimit() {
            return autoReadDailyLimit;
        }

        public void setAutoReadDailyLimit(int autoReadDailyLimit) {
            this.autoReadDailyLimit = autoReadDailyLimit;
        }

        public java.math.BigDecimal getPriceInputPerMillion() {
            return priceInputPerMillion;
        }

        public void setPriceInputPerMillion(java.math.BigDecimal priceInputPerMillion) {
            this.priceInputPerMillion = priceInputPerMillion;
        }

        public java.math.BigDecimal getPriceOutputPerMillion() {
            return priceOutputPerMillion;
        }

        public void setPriceOutputPerMillion(java.math.BigDecimal priceOutputPerMillion) {
            this.priceOutputPerMillion = priceOutputPerMillion;
        }

        /** What a call cost, from its tokens and the configured prices (zero when no price is configured). */
        public java.math.BigDecimal costOf(int inputTokens, int outputTokens) {
            return priceInputPerMillion.multiply(java.math.BigDecimal.valueOf(inputTokens))
                    .add(priceOutputPerMillion.multiply(java.math.BigDecimal.valueOf(outputTokens)))
                    .divide(java.math.BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.HALF_UP);
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getGuideDailyLimit() {
            return guideDailyLimit;
        }

        public void setGuideDailyLimit(int guideDailyLimit) {
            this.guideDailyLimit = guideDailyLimit;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public double getMonthlyBudgetEur() {
            return monthlyBudgetEur;
        }

        public void setMonthlyBudgetEur(double monthlyBudgetEur) {
            this.monthlyBudgetEur = monthlyBudgetEur;
        }
    }

    public static class Ocr {

        private boolean enabled = false;
        private String engine = "tesseract";
        private String languages = "deu+eng";
        private String tesseractPath = "tesseract";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEngine() {
            return engine;
        }

        public void setEngine(String engine) {
            this.engine = engine;
        }

        public String getLanguages() {
            return languages;
        }

        public void setLanguages(String languages) {
            this.languages = languages;
        }

        public String getTesseractPath() {
            return tesseractPath;
        }

        public void setTesseractPath(String tesseractPath) {
            this.tesseractPath = tesseractPath;
        }
    }

    public static class Outbox {

        private int batchSize = 50;
        private int maxAttempts = 8;
        private Duration pollInterval = Duration.ofSeconds(5);

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }
    }

    public static class Upload {

        private long maxFileSizeBytes = 100L * 1024 * 1024;
        private List<String> allowedMimeTypes = new ArrayList<>();

        public long getMaxFileSizeBytes() {
            return maxFileSizeBytes;
        }

        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes;
        }

        public List<String> getAllowedMimeTypes() {
            return allowedMimeTypes;
        }

        public void setAllowedMimeTypes(List<String> allowedMimeTypes) {
            this.allowedMimeTypes = allowedMimeTypes;
        }
    }

    public static class Worker {

        private boolean enabled = false;
        private int concurrency = 4;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }
    }
}
