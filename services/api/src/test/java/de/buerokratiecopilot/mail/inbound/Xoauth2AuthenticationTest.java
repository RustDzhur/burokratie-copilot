package de.buerokratiecopilot.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.mail.Store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What actually goes over the wire when a Microsoft 365 or Google Workspace mailbox is opened with an
 * OAuth access token.
 *
 * <p>Jakarta Mail builds the XOAUTH2 initial response itself, and a test that only called a helper of ours
 * would prove nothing about what the server receives. This test therefore stands up a minimal IMAP server,
 * lets the real connection code authenticate, and decodes the bytes the client sent: RFC 7628 requires
 * {@code user=<address>\u0001auth=Bearer <token>\u0001\u0001}. If the library or the properties ever changed
 * the shape, this fails — and a provider would have rejected it anyway.</p>
 *
 * <p>No external network: the server is a {@link ServerSocket} on the loopback interface.</p>
 */
class Xoauth2AuthenticationTest {

    private static final String USER = "postfach@example.de";
    private static final String TOKEN = "ya29.not-a-real-token";
    private static final String PASSWORD = "greenmail-style-password";

    @Test
    @DisplayName("IMAP XOAUTH2 sends user=… auth=Bearer … in the RFC 7628 shape")
    void xoauth2InitialResponseHasTheRfcShape() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        try (FakeImapServer server = new FakeImapServer(captured, true)) {
            ImapConnectionFactory factory = new ImapConnectionFactory(true);

            Store store = factory.connect("127.0.0.1", server.port(), false, USER, TOKEN,
                    ImapConnectionFactory.Auth.XOAUTH2);
            store.close();

            String response = captured.get();
            assertThat(response).as("the client must authenticate with XOAUTH2").isNotNull();
            String decoded = new String(Base64.getDecoder().decode(response), StandardCharsets.UTF_8);
            assertThat(decoded).isEqualTo("user=" + USER + "\u0001auth=Bearer " + TOKEN + "\u0001\u0001");
        }
    }

    @Test
    @DisplayName("the password path still logs in with a password, not with a token mechanism")
    void passwordStillUsesLogin() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        try (FakeImapServer server = new FakeImapServer(captured, false)) {
            ImapConnectionFactory factory = new ImapConnectionFactory(true);

            Store store = factory.connect("127.0.0.1", server.port(), false, USER, PASSWORD,
                    ImapConnectionFactory.Auth.PASSWORD);
            store.close();

            assertThat(captured.get()).as("the password path must keep working after the refactor")
                    .contains("LOGIN").contains(USER).contains(PASSWORD);
        }
    }

    /**
     * A very small IMAP server: greeting, CAPABILITY, authentication and LOGOUT. With {@code xoauth2} it
     * advertises XOAUTH2 and records the base64 blob of the authentication command; without it, it advertises
     * no SASL mechanism at all, so the client falls back to the classic LOGIN command, which is recorded too.
     */
    private static final class FakeImapServer implements AutoCloseable {

        private final ServerSocket server;
        private final Thread thread;
        private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        private final boolean xoauth2;

        FakeImapServer(AtomicReference<String> captured, boolean xoauth2) throws IOException {
            this.xoauth2 = xoauth2;
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> serve(captured), "fake-imap");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve(AtomicReference<String> captured) {
            try (Socket socket = server.accept();
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(),
                         StandardCharsets.UTF_8));
                 Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                write(out, xoauth2
                        ? "* OK [CAPABILITY IMAP4rev1 AUTH=XOAUTH2 SASL-IR] fake imap ready"
                        : "* OK [CAPABILITY IMAP4rev1] fake imap ready");
                String line;
                while ((line = in.readLine()) != null) {
                    String tag = line.split(" ", 2)[0];
                    String upper = line.toUpperCase(Locale.ROOT);
                    if (xoauth2 && upper.contains("AUTHENTICATE XOAUTH2 ")) {
                        captured.set(line.substring(upper.indexOf("XOAUTH2 ") + "XOAUTH2 ".length()).trim());
                        write(out, tag + " OK AUTHENTICATE completed");
                    }
                    else if (upper.contains("CAPABILITY")) {
                        write(out, xoauth2
                                ? "* CAPABILITY IMAP4rev1 AUTH=XOAUTH2 SASL-IR"
                                : "* CAPABILITY IMAP4rev1");
                        write(out, tag + " OK CAPABILITY completed");
                    }
                    else if (upper.contains(" LOGIN ")) {
                        captured.set(line);
                        write(out, tag + " OK LOGIN completed");
                    }
                    else if (upper.contains("LOGOUT")) {
                        write(out, "* BYE fake imap closing");
                        write(out, tag + " OK LOGOUT completed");
                        return;
                    }
                    else {
                        // The client may ask something else while connecting; a compatible OK is enough.
                        write(out, tag + " OK completed");
                    }
                }
            }
            catch (IOException ex) {
                failure.set(new IllegalStateException("fake IMAP server failed", ex));
            }
        }

        private static void write(Writer out, String line) throws IOException {
            out.write(line + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(5_000);
            if (failure.get() != null) {
                throw failure.get();
            }
        }
    }
}
