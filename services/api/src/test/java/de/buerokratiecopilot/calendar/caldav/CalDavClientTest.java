package de.buerokratiecopilot.calendar.caldav;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The real client against a stand-in CalDAV server: what it sends (Depth, Content-Type, Basic auth) and
 * how it reads the multistatus answers and reports failures.
 *
 * <p>The failure cases are the point: a 401 must come back as "the app password is wrong" and must never
 * echo the password, a 404 must be distinguishable from it, and a broken XML answer must be named a
 * protocol error.</p>
 */
class CalDavClientTest {

    private static final String ACCOUNT = "person@example.com";
    private static final String PASSWORD = "app-passwort-geheim-4711";

    private HttpServer server;
    private final AtomicReference<String> seenMethod = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenAuthorization = new AtomicReference<>();
    private final AtomicReference<String> seenDepth = new AtomicReference<>();
    private final AtomicReference<String> seenContentType = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private volatile int status = 207;
    private volatile String answer = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            String query = exchange.getRequestURI().getQuery();
            seenPath.set(exchange.getRequestURI().getPath() + (query == null ? "" : "?" + query));
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            seenDepth.set(exchange.getRequestHeaders().getFirst("Depth"));
            seenContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            seenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private CalDavClient.Connection connection() {
        return new CalDavClient.Connection("http://127.0.0.1:" + server.getAddress().getPort(),
                "/12345/calendars/AAA-BBB/", ACCOUNT, PASSWORD);
    }

    private static String multistatus(String responses) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                %s
                </d:multistatus>
                """.formatted(responses);
    }

    private static String response(String href, String props) {
        return """
                <d:response>
                  <d:href>%s</d:href>
                  <d:propstat>
                    <d:prop>%s</d:prop>
                    <d:status>HTTP/1.1 200 OK</d:status>
                  </d:propstat>
                </d:response>
                """.formatted(href, props);
    }

    @Test
    @DisplayName("discovery: PROPFIND Depth 1 with Basic auth, and only real calendars are listed")
    void listsCalendars() {
        answer = multistatus(
                response("/12345/calendars/AAA-BBB/",
                        "<d:displayname>Arbeit</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>")
                        + response("/12345/calendars/CCC/",
                        "<d:displayname>Privat</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype>")
                        + response("/12345/principals/me/",
                        "<d:displayname>Principals</d:displayname><d:resourcetype><d:collection/></d:resourcetype>"));

        List<CalDavClient.CalendarInfo> calendars = new CalDavClient().listCalendars(connection());

        assertThat(seenMethod.get()).isEqualTo("PROPFIND");
        assertThat(seenDepth.get()).isEqualTo("1");
        assertThat(seenAuthorization.get()).isEqualTo("Basic " + Base64.getEncoder()
                .encodeToString((ACCOUNT + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
        assertThat(seenContentType.get()).contains("application/xml");
        assertThat(calendars).extracting(CalDavClient.CalendarInfo::displayName)
                .containsExactly("Arbeit", "Privat");
        assertThat(calendars).extracting(CalDavClient.CalendarInfo::path)
                .containsExactly("/12345/calendars/AAA-BBB/", "/12345/calendars/CCC/");
    }

    @Test
    @DisplayName("listResources: collections are skipped, plain .ics resources are returned")
    void listsResources() {
        answer = multistatus(
                response("/12345/calendars/AAA-BBB/",
                        "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>")
                        + response("/12345/calendars/AAA-BBB/one@copilot.ics",
                        "<d:resourcetype/><d:getetag>\"1\"</d:getetag>")
                        + response("/12345/calendars/AAA-BBB/two@copilot.ics",
                        "<d:getetag>\"2\"</d:getetag>"));

        List<CalDavClient.Resource> resources = new CalDavClient().listResources(connection());

        assertThat(resources).extracting(CalDavClient.Resource::href)
                .containsExactly("/12345/calendars/AAA-BBB/one@copilot.ics",
                        "/12345/calendars/AAA-BBB/two@copilot.ics");
    }

    @Test
    @DisplayName("verify: a 207 multistatus on the calendar path means access works")
    void verifies() {
        answer = multistatus(response("/12345/calendars/AAA-BBB/",
                "<d:resourcetype><d:collection/><c:calendar/></d:resourcetype>"));
        new CalDavClient().verify(connection());
        assertThat(seenMethod.get()).isEqualTo("PROPFIND");
        assertThat(seenDepth.get()).isEqualTo("0");
    }

    @Test
    @DisplayName("put: PUT to the calendar path with the .ics body and calendar content type")
    void putsEvent() {
        status = 201;
        answer = "";
        new CalDavClient().putEvent(connection(), "one@copilot.ics",
                "BEGIN:VCALENDAR\r\nUID:one@copilot\r\nEND:VCALENDAR\r\n");

        assertThat(seenMethod.get()).isEqualTo("PUT");
        assertThat(seenPath.get()).isEqualTo("/12345/calendars/AAA-BBB/one@copilot.ics");
        assertThat(seenContentType.get()).startsWith("text/calendar");
        assertThat(seenBody.get()).contains("UID:one@copilot");
    }

    @Test
    @DisplayName("delete: DELETE by file name; a 404 is the desired end state, not an error")
    void deletesEvent() {
        status = 404;
        new CalDavClient().deleteEvent(connection(), "one@copilot.ics");
        assertThat(seenMethod.get()).isEqualTo("DELETE");
        assertThat(seenPath.get()).isEqualTo("/12345/calendars/AAA-BBB/one@copilot.ics");

        status = 204;
        new CalDavClient().deleteEvent(connection(), "one@copilot.ics");
        assertThat(seenMethod.get()).isEqualTo("DELETE");
    }

    @Test
    @DisplayName("a 401 becomes 'app password is wrong' and never contains the password")
    void authFailure() {
        status = 401;
        answer = "unauthorized";

        assertThatThrownBy(() -> new CalDavClient().listCalendars(connection()))
                .isInstanceOfSatisfying(CalDavException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(CalDavException.Code.AUTH_FAILED);
                    assertThat(ex.isAuthFailure()).isTrue();
                    assertThat(ex.getMessage()).contains("App-Passwort");
                    assertThat(ex.getMessage()).doesNotContain(PASSWORD).doesNotContain(ACCOUNT);
                });

        assertThatThrownBy(() -> new CalDavClient().putEvent(connection(), "x.ics", "BEGIN:VCALENDAR"))
                .isInstanceOfSatisfying(CalDavException.class,
                        ex -> assertThat(ex.code()).isEqualTo(CalDavException.Code.AUTH_FAILED));

        assertThatThrownBy(() -> new CalDavClient().deleteEvent(connection(), "x.ics"))
                .isInstanceOfSatisfying(CalDavException.class,
                        ex -> assertThat(ex.code()).isEqualTo(CalDavException.Code.AUTH_FAILED));

        assertThatThrownBy(() -> new CalDavClient().verify(connection()))
                .isInstanceOfSatisfying(CalDavException.class,
                        ex -> assertThat(ex.code()).isEqualTo(CalDavException.Code.AUTH_FAILED));
    }

    @Test
    @DisplayName("a 404 is a missing calendar, distinguishable from a wrong password")
    void notFound() {
        status = 404;
        assertThatThrownBy(() -> new CalDavClient().verify(connection()))
                .isInstanceOfSatisfying(CalDavException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(CalDavException.Code.NOT_FOUND);
                    assertThat(ex.status()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("an answer that is not a multistatus is named a protocol error")
    void protocolFailure() {
        status = 200;
        answer = "<html>kein WebDAV</html>";
        assertThatThrownBy(() -> new CalDavClient().listCalendars(connection()))
                .isInstanceOfSatisfying(CalDavException.class,
                        ex -> assertThat(ex.code()).isEqualTo(CalDavException.Code.PROTOCOL));
    }

    @Test
    @DisplayName("a dead server is a network failure, with the password nowhere near the message")
    void networkFailure() {
        CalDavClient.Connection dead = new CalDavClient.Connection(
                "http://127.0.0.1:1", "/x/", ACCOUNT, PASSWORD);
        assertThatThrownBy(() -> new CalDavClient().verify(dead))
                .isInstanceOfSatisfying(CalDavException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(CalDavException.Code.NETWORK);
                    assertThat(ex.getMessage()).doesNotContain(PASSWORD);
                });
    }

    @Test
    @DisplayName("baseName keeps the file name and decodes it")
    void baseNames() {
        assertThat(CalDavSyncService.baseName("/12345/calendars/x/one@copilot.ics"))
                .isEqualTo("one@copilot.ics");
        assertThat(CalDavSyncService.baseName("/12345/calendars/x/task-deadline-ab@copilot.ics"))
                .isEqualTo("task-deadline-ab@copilot.ics");
        assertThat(CalDavSyncService.baseName("/x/a%20b.ics")).isEqualTo("a b.ics");
        assertThat(CalDavSyncService.baseName(null)).isNull();
    }
}
