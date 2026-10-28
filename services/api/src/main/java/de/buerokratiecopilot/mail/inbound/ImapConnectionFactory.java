package de.buerokratiecopilot.mail.inbound;

import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Opens an IMAP connection, with a password or with an OAuth access token.
 *
 * <p>Both Microsoft 365 and Google Workspace accept a SASL XOAUTH2 authentication on IMAP, and Jakarta Mail
 * (Angus) implements that mechanism: the properties below enable exactly {@code XOAUTH2} and disable the
 * password mechanisms, and the access token is passed where a password would go. The initial response is
 * built by the library as RFC 7628 prescribes — {@code user=<name>\u0001auth=Bearer <token>\u0001\u0001} —
 * which the test {@code Xoauth2AuthenticationTest} observes on the wire rather than trusting a comment.</p>
 *
 * <p>The password path is unchanged: an ordinary IMAP mailbox keeps working as before, and the platform
 * never silently turns one into the other.</p>
 *
 * <p>This class holds no secret. It receives the credential, hands it to the provider, and returns the
 * connected store; the caller closes it.</p>
 */
@Component
public class ImapConnectionFactory {

    /** How the secret is presented to the server. */
    public enum Auth {
        /** The mailbox password, over the server's password mechanisms. */
        PASSWORD,
        /** An OAuth access token, through SASL XOAUTH2. */
        XOAUTH2
    }

    /**
     * Whether the TLS certificate must match the server name.
     *
     * <p>On by default, because a credential crossing a connection to an unverified server is exactly what
     * an attacker on the network wants. It is configurable because some self-hosted mail servers use
     * certificates that do not carry the name the client dials, and the operator — not the platform — has to
     * weigh that. A test that connects to a local server turns it off explicitly.</p>
     */
    private final boolean checkServerIdentity;

    public ImapConnectionFactory(
            @Value("${burokratie.mail.imap.check-server-identity:true}") boolean checkServerIdentity) {
        this.checkServerIdentity = checkServerIdentity;
    }

    /** Opens a connection using the same TLS choice the ingest has always made: on unless port 143. */
    public Store connect(String host, int port, String username, String credential, Auth auth)
            throws MessagingException {
        return connect(host, port, port != 143, username, credential, auth);
    }

    /** Opens a connection with an explicit TLS decision. Package-private so the wire test can use plain TCP. */
    Store connect(String host, int port, boolean ssl, String username, String credential, Auth auth)
            throws MessagingException {
        // The plain and the TLS store are different providers in Jakarta Mail; asking for "imaps" and then
        // switching TLS off is not the same thing and some versions ignore it.
        String protocol = ssl ? "imaps" : "imap";
        Properties properties = new Properties();
        properties.put("mail.store.protocol", protocol);
        properties.put("mail." + protocol + ".host", host);
        properties.put("mail." + protocol + ".port", String.valueOf(port));
        properties.put("mail." + protocol + ".ssl.enable", String.valueOf(ssl));
        properties.put("mail." + protocol + ".ssl.trust", "*");
        properties.put("mail." + protocol + ".ssl.checkserveridentity", String.valueOf(checkServerIdentity));
        properties.put("mail." + protocol + ".timeout", "10000");
        if (auth == Auth.XOAUTH2) {
            // Only XOAUTH2 is offered; the password mechanisms stay disabled so a token is never sent as one.
            properties.put("mail." + protocol + ".auth.mechanisms", "XOAUTH2");
            properties.put("mail." + protocol + ".auth.login.disable", "true");
            properties.put("mail." + protocol + ".auth.plain.disable", "true");
        }
        Session session = Session.getInstance(properties);
        Store store = session.getStore(protocol);
        store.connect(host, port, username, credential);
        return store;
    }
}
