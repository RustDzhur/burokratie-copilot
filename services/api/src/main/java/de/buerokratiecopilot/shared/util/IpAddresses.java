package de.buerokratiecopilot.shared.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * Safe conversion of request metadata into {@link InetAddress}.
 *
 * <p>Only IP <em>literals</em> are accepted. A hostname is never resolved: resolving would
 * (a) perform an outbound DNS request triggered by untrusted header content and (b) store a
 * resolved address instead of what the client actually sent.</p>
 */
public final class IpAddresses {

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]{2,45}$");

    private IpAddresses() {
    }

    /**
     * Parses an IP literal; returns {@code null} for blank, malformed or hostname input so the
     * caller stores "unknown" instead of a wrong value.
     */
    public static InetAddress parse(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.length() > 45) {
            return null;
        }
        // A "host:port" form (IPv4) or an X-Forwarded-For list: keep only the first address.
        int comma = candidate.indexOf(',');
        if (comma > 0) {
            candidate = candidate.substring(0, comma).trim();
        }
        // Strip an IPv6 zone identifier, which is not part of the address itself.
        int percent = candidate.indexOf('%');
        if (percent > 0) {
            candidate = candidate.substring(0, percent);
        }
        if (!IPV4.matcher(candidate).matches() && !IPV6.matcher(candidate).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate);
        }
        catch (UnknownHostException ex) {
            return null;
        }
    }

    /** Best-effort textual form for responses and logs. */
    public static String format(InetAddress address) {
        return address == null ? null : address.getHostAddress();
    }
}
