package de.buerokratiecopilot.mail.oauth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the OAuth clients that read Microsoft 365 and Google Workspace mailboxes.
 *
 * <p>The defaults are the providers' public endpoints and the scopes this platform needs; only the
 * application registration itself has to be supplied — by the operator, through the environment
 * ({@code BC_MAIL_OAUTH_MICROSOFT_CLIENT_ID}, {@code ..._CLIENT_SECRET}, and the same for Google). Nothing
 * here has a default secret, and a provider whose id or secret is missing is reported as not configured
 * rather than half-used.</p>
 *
 * <p>The scope values are the ones the providers document for IMAP XOAUTH2:</p>
 * <ul>
 *   <li>Microsoft: {@code https://outlook.office.com/IMAP.AccessAsUser.All} for the mailbox and
 *       {@code .../SMTP.Send} for sending, plus {@code openid email profile offline_access} so that a
 *       refresh token is issued and the address can be read. {@code User.Read} is only needed for the
 *       default user-info endpoint (Microsoft Graph {@code /me}).</li>
 *   <li>Google: {@code https://mail.google.com/} is the scope Gmail requires for IMAP, plus
 *       {@code openid email profile} and {@code access_type=offline} for a refresh token.</li>
 * </ul>
 *
 * <p>A deployment that registers a custom application may override every URL. The redirect URI is not
 * configured here: it is derived from {@code burokratie.security.public-base-url}, so a registered
 * application can never disagree with the page that sent the browser to the provider.</p>
 */
@ConfigurationProperties(prefix = "burokratie.mail.oauth")
public class MailOAuthProperties {

    private final Provider microsoft = new Provider(
            "https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/authorize",
            "https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token",
            "https://graph.microsoft.com/v1.0/me?$select=mail,userPrincipalName",
            "openid email profile offline_access https://outlook.office.com/IMAP.AccessAsUser.All "
                    + "https://outlook.office.com/SMTP.Send https://graph.microsoft.com/User.Read",
            "outlook.office365.com", 993, "smtp.office365.com", 587);
    private final Provider google = new Provider(
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://openidconnect.googleapis.com/v1/userinfo",
            "openid email profile https://mail.google.com/",
            "imap.gmail.com", 993, "smtp.gmail.com", 587);

    public MailOAuthProperties() {
        microsoft.setTenantId("common");
        microsoft.getExtraAuthorizeParameters().put("prompt", "select_account");
        google.getExtraAuthorizeParameters().put("access_type", "offline");
        google.getExtraAuthorizeParameters().put("prompt", "consent");
    }

    public Provider getMicrosoft() {
        return microsoft;
    }

    public Provider getGoogle() {
        return google;
    }

    /** The settings of one provider's registered application. */
    public static class Provider {

        /** Client id of the registered application; empty means "not registered yet". */
        private String clientId = "";
        /** Client secret of the registered application; empty means "not registered yet". */
        private String clientSecret = "";
        /** Microsoft only: {@code common}, {@code organizations} or a tenant id. */
        private String tenantId = "";
        private String authorizeUrl;
        private String tokenUrl;
        private String userInfoUrl;
        private String scope;
        /** Where the mailbox is read from. Both providers offer IMAP with XOAUTH2. */
        private String imapHost;
        private int imapPort;
        private String smtpHost;
        private int smtpPort;
        /** Additional authorize-query parameters (for example {@code access_type=offline}). */
        private final Map<String, String> extraAuthorizeParameters = new LinkedHashMap<>();

        Provider(String authorizeUrl, String tokenUrl, String userInfoUrl, String scope, String imapHost,
                 int imapPort, String smtpHost, int smtpPort) {
            this.authorizeUrl = authorizeUrl;
            this.tokenUrl = tokenUrl;
            this.userInfoUrl = userInfoUrl;
            this.scope = scope;
            this.imapHost = imapHost;
            this.imapPort = imapPort;
            this.smtpHost = smtpHost;
            this.smtpPort = smtpPort;
        }

        /**
         * Whether the operator has registered an application and every endpoint is configured.
         *
         * <p>Registering an application is the only step the platform cannot do itself; a missing endpoint is
         * just as blocking, and answering "configured" for a half-filled configuration would let the browser
         * start a flow that cannot finish.</p>
         */
        public boolean isRegistered() {
            return missing().isEmpty();
        }

        /** What is missing, in the words of the configuration keys, so the answer can be acted on. */
        public java.util.List<String> missing() {
            java.util.List<String> missing = new java.util.ArrayList<>();
            if (clientId.isBlank()) {
                missing.add("client-id");
            }
            if (clientSecret.isBlank()) {
                missing.add("client-secret");
            }
            if (authorizeUrl == null || authorizeUrl.isBlank()) {
                missing.add("authorize-url");
            }
            if (tokenUrl == null || tokenUrl.isBlank()) {
                missing.add("token-url");
            }
            if (userInfoUrl == null || userInfoUrl.isBlank()) {
                missing.add("user-info-url");
            }
            return missing;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId == null ? "" : clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret == null ? "" : clientSecret;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId == null || tenantId.isBlank() ? "common" : tenantId;
        }

        public String getAuthorizeUrl() {
            return authorizeUrl;
        }

        public void setAuthorizeUrl(String authorizeUrl) {
            this.authorizeUrl = authorizeUrl;
        }

        public String getTokenUrl() {
            return tokenUrl;
        }

        public void setTokenUrl(String tokenUrl) {
            this.tokenUrl = tokenUrl;
        }

        public String getUserInfoUrl() {
            return userInfoUrl;
        }

        public void setUserInfoUrl(String userInfoUrl) {
            this.userInfoUrl = userInfoUrl;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getImapHost() {
            return imapHost;
        }

        public void setImapHost(String imapHost) {
            this.imapHost = imapHost;
        }

        public int getImapPort() {
            return imapPort;
        }

        public void setImapPort(int imapPort) {
            this.imapPort = imapPort;
        }

        public String getSmtpHost() {
            return smtpHost;
        }

        public void setSmtpHost(String smtpHost) {
            this.smtpHost = smtpHost;
        }

        public int getSmtpPort() {
            return smtpPort;
        }

        public void setSmtpPort(int smtpPort) {
            this.smtpPort = smtpPort;
        }

        public Map<String, String> getExtraAuthorizeParameters() {
            return extraAuthorizeParameters;
        }
    }
}
