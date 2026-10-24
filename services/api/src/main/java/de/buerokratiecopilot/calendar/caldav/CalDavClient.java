package de.buerokratiecopilot.calendar.caldav;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A minimal CalDAV client: PROPFIND, PUT and DELETE over plain WebDAV, with the JDK's own HTTP client
 * and XML parser. No extra dependencies.
 *
 * <p>What CalDAV needs for a one-way platform-to-calendar sync is small: list the calendars of an
 * account, list the .ics resources of one calendar, put an event and delete an event. Everything else a
 * CalDAV library would bring (scheduling, sharing, REPORT filters) is not used here.</p>
 *
 * <p><strong>Failures are named, not guessed.</strong> A 401/403 means the account or the app password
 * is wrong and says exactly that; a 404 means the calendar path is not there. The exception message is
 * what the interface shows, so it never contains the password.</p>
 */
@Component
public class CalDavClient {

    private static final Logger LOG = LoggerFactory.getLogger(CalDavClient.class);

    /** The DAV namespace, used to read PROPFIND answers. */
    public static final String NS_DAV = "DAV:";
    /** The Apple calendar-server namespace. */
    public static final String NS_CALENDARSERVER = "http://calendarserver.org/ns/";

    private static final String PROPFIND_BODY_CALENDARS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
              <d:prop>
                <d:displayname />
                <d:resourcetype />
                <cs:getctag />
              </d:prop>
            </d:propfind>
            """;

    private static final String PROPFIND_BODY_RESOURCES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname />
                <d:resourcetype />
                <d:getetag />
              </d:prop>
            </d:propfind>
            """;

    private final HttpClient http;

    public CalDavClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    /** For tests: a client with its own transport. */
    public CalDavClient(HttpClient http) {
        this.http = http;
    }

    /**
     * What a person can connect to, in one row.
     *
     * @param serverUrl   the CalDAV root, e.g. {@code https://caldav.icloud.com}
     * @param calendarPath the calendar's path on the server, e.g. {@code /12345/calendars/A-B-C/}
     * @param account     the Apple-ID or CalDAV login
     * @param appPassword the app-specific password (never logged, never returned)
     */
    public record Connection(String serverUrl, String calendarPath, String account, String appPassword) {

        /** The URL a calendar address points at: the server root plus the path. */
        public URI calendarUri() {
            String root = serverUrl.replaceAll("/+$", "");
            String path = calendarPath.startsWith("/") ? calendarPath : "/" + calendarPath;
            return URI.create(root + path);
        }
    }

    /** One calendar of the account, as the PROPFIND list reports it. */
    public record CalendarInfo(String path, String displayName) {
    }

    /** One remote .ics resource of a calendar. */
    public record Resource(String href, String etag) {
    }

    /**
     * The calendars of an account (PROPFIND, Depth 1): everything the server says is a calendar, with
     * its display name. A wrong password is reported as {@link CalDavException.Code#AUTH_FAILED}.
     */
    public List<CalendarInfo> listCalendars(Connection connection) {
        Document document = propfind(connection.serverUrl() + "/", connection, 1, PROPFIND_BODY_CALENDARS,
                "calendars of the account");
        List<CalendarInfo> calendars = new ArrayList<>();
        for (Element response : responses(document)) {
            if (hasChild(response, "resourcetype", "calendar")) {
                calendars.add(new CalendarInfo(hrefOf(response), textOf(response, "displayname")));
            }
        }
        return calendars;
    }

    /**
     * The .ics resources of a calendar (PROPFIND, Depth 1), excluding collections. Used to find which
     * of our pushed events still exist on the server.
     */
    public List<Resource> listResources(Connection connection) {
        Document document = propfind(connection.calendarUri().toString(), connection, 1,
                PROPFIND_BODY_RESOURCES, "the events of the calendar");
        List<Resource> resources = new ArrayList<>();
        for (Element response : responses(document)) {
            String href = hrefOf(response);
            // Collections (the calendar itself, sub-calendars) have a resourcetype child; plain event
            // resources have an empty resourcetype. Only the latter are .ics files.
            if (href == null || href.isBlank() || hasAnyChild(response, "resourcetype")) {
                continue;
            }
            resources.add(new Resource(href, textOf(response, "getetag")));
        }
        return resources;
    }

    /**
     * Checks that a calendar path exists and is a calendar (PROPFIND, Depth 0). This is the access test
     * that runs before a connection is saved.
     */
    public void verify(Connection connection) {
        propfind(connection.calendarUri().toString(), connection, 0, PROPFIND_BODY_RESOURCES,
                "the calendar");
    }

    /**
     * Writes one event (PUT .ics, create or replace). The UID inside the object and the file name must
     * match, which {@code IcsEventWriter} guarantees.
     *
     * @param fileName the resource name, e.g. {@code <uid>.ics}
     * @param ics      the iCalendar text of the single event
     */
    public void putEvent(Connection connection, String fileName, String ics) {
        URI uri = resolve(connection, fileName);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", basicAuth(connection))
                .header("Content-Type", "text/calendar; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(ics, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, "write the event " + fileName);
        // CalDAV servers accept 200, 201 and 204 for a PUT; 412 is not used here because we always
        // create-or-replace by UID.
        if (response.statusCode() >= 300) {
            throw failure(response, "write the event " + fileName);
        }
    }

    /**
     * Deletes one event by file name. A 404 means the event is already gone, which is the desired end
     * state, so it is not an error.
     */
    public void deleteEvent(Connection connection, String fileName) {
        URI uri = resolve(connection, fileName);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", basicAuth(connection))
                .DELETE()
                .build();
        HttpResponse<String> response = send(request, "delete the event " + fileName);
        if (response.statusCode() >= 300 && response.statusCode() != 404) {
            throw failure(response, "delete the event " + fileName);
        }
    }

    /* ------------------------------------------------------------------ */
    /* HTTP and XML plumbing                                              */
    /* ------------------------------------------------------------------ */

    private Document propfind(String url, Connection connection, int depth, String body, String what) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", basicAuth(connection))
                .header("Depth", String.valueOf(depth))
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request, "list " + what);
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new CalDavException(CalDavException.Code.AUTH_FAILED,
                    "Das App-Passwort oder das Konto ist nicht korrekt", status);
        }
        if (status == 404) {
            throw new CalDavException(CalDavException.Code.NOT_FOUND,
                    "Der Kalender wurde auf dem Server nicht gefunden", status);
        }
        if (status != 207) {
            // 2xx that is not a multistatus: the server answered, but not as a CalDAV server.
            CalDavException.Code code = status >= 200 && status < 300 ? CalDavException.Code.PROTOCOL
                    : CalDavException.Code.SERVER;
            throw new CalDavException(code,
                    "Der Kalenderserver hat mit Status " + status + " geantwortet", status);
        }
        return parse(response.body(), what);
    }

    private HttpResponse<String> send(HttpRequest request, String action) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (IOException ex) {
            LOG.warn("CalDAV call failed ({})", action, ex);
            throw new CalDavException(CalDavException.Code.NETWORK,
                    "Der Kalenderserver ist nicht erreichbar", 0, ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CalDavException(CalDavException.Code.NETWORK,
                    "Die Verbindung zum Kalenderserver wurde unterbrochen", 0, ex);
        }
    }

    /** Turns a non-2xx answer into the named exception; never copies the password into the message. */
    private CalDavException failure(HttpResponse<String> response, String action) {
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            return new CalDavException(CalDavException.Code.AUTH_FAILED,
                    "Das App-Passwort oder das Konto ist nicht korrekt", status);
        }
        if (status == 404) {
            return new CalDavException(CalDavException.Code.NOT_FOUND,
                    "Der Kalender wurde auf dem Server nicht gefunden", status);
        }
        if (status == 409 || status == 412) {
            return new CalDavException(CalDavException.Code.SERVER,
                    "Der Kalenderserver hat den Eintrag abgelehnt", status);
        }
        LOG.warn("CalDAV {} failed with status {}: {}", action, status,
                response.body() == null ? "" : response.body().substring(0, Math.min(200, response.body().length())));
        return new CalDavException(CalDavException.Code.SERVER,
                "Der Kalenderserver hat mit Status " + status + " geantwortet", status);
    }

    /** The href of a calendar or event, resolved against the connection. */
    private URI resolve(Connection connection, String fileName) {
        String root = connection.serverUrl().replaceAll("/+$", "");
        String path = connection.calendarPath().replaceAll("/+$", "");
        return URI.create(root + path + "/" + encodeSegment(fileName));
    }

    /** One path segment, URL-encoded without touching existing escapes or the path structure. */
    static String encodeSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String basicAuth(Connection connection) {
        String credentials = connection.account() + ":" + connection.appPassword();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private Document parse(String body, String what) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                    body.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) {
            throw new CalDavException(CalDavException.Code.PROTOCOL,
                    "Die Antwort des Kalenderservers war kein gültiges WebDAV-XML", 0, ex);
        }
    }

    /** All {@code response} elements of a multistatus document. */
    private List<Element> responses(Document document) {
        List<Element> result = new ArrayList<>();
        collect(document.getDocumentElement(), "response", result);
        if (result.isEmpty()) {
            throw new CalDavException(CalDavException.Code.PROTOCOL,
                    "Die Antwort des Kalenderservers enthielt keine Einträge");
        }
        return result;
    }

    private void collect(Element parent, String localName, List<Element> into) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element) {
                if (localName.equals(element.getLocalName()) && NS_DAV.equals(element.getNamespaceURI())) {
                    into.add(element);
                }
                collect(element, localName, into);
            }
        }
    }

    private Element propOf(Element response) {
        // Only propstats that succeeded (status 200) are read; a prop the server does not know is not
        // a reason to fail the whole answer.
        for (Element propstat : children(response, "propstat")) {
            String status = directText(propstat, "status");
            if (status != null && status.contains(" 200")) {
                for (Element prop : children(propstat, "prop")) {
                    return prop;
                }
            }
        }
        return null;
    }

    private List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())
                    && NS_DAV.equals(element.getNamespaceURI())) {
                result.add(element);
            }
        }
        return result;
    }

    /** The text of a direct DAV child of an element, or null when absent. */
    private String directText(Element parent, String localName) {
        for (Element child : children(parent, localName)) {
            String value = child.getTextContent();
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /** The href of a response entry, as written by the server. */
    private String hrefOf(Element response) {
        return directText(response, "href");
    }

    /** The text of a DAV property of a response entry, or null when absent. */
    private String textOf(Element response, String property) {
        Element prop = propOf(response);
        return prop == null ? null : directText(prop, property);
    }

    private boolean hasChild(Element response, String property, String childLocalName) {
        Element prop = propOf(response);
        if (prop == null) {
            return false;
        }
        for (Element child : children(prop, property)) {
            NodeList inside = child.getChildNodes();
            for (int i = 0; i < inside.getLength(); i++) {
                Node node = inside.item(i);
                if (node instanceof Element element && childLocalName.equals(element.getLocalName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasAnyChild(Element response, String property) {
        Element prop = propOf(response);
        if (prop == null) {
            return false;
        }
        for (Element child : children(prop, property)) {
            if (child.getChildNodes().getLength() > 0) {
                return true;
            }
        }
        return false;
    }
}
