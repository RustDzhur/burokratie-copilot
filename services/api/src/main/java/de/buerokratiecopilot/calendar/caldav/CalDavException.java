package de.buerokratiecopilot.calendar.caldav;

/**
 * A failure of a CalDAV call, named by what went wrong.
 *
 * <p>The status alone is not enough: a 401 and a 404 need completely different answers in the interface,
 * and a network failure is neither. The code names the failure, the message is safe to show to a person
 * and never contains the password.</p>
 */
public class CalDavException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** What went wrong, stable for the interface. */
    public enum Code {
        /** 401/403: the account or the app password is wrong. */
        AUTH_FAILED,
        /** The calendar (path) does not exist or is not a calendar. */
        NOT_FOUND,
        /** The server answered, but not with CalDAV. */
        PROTOCOL,
        /** The server could not be reached at all. */
        NETWORK,
        /** Anything else the server answered (5xx, 409, ...). */
        SERVER
    }

    private final int status;
    private final Code code;

    public CalDavException(Code code, String message) {
        this(code, message, 0, null);
    }

    public CalDavException(Code code, String message, int status) {
        this(code, message, status, null);
    }

    public CalDavException(Code code, String message, int status, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public Code code() {
        return code;
    }

    /** The HTTP status that was answered, or 0 for a network failure. */
    public int status() {
        return status;
    }

    /** Whether the account or app password was rejected. */
    public boolean isAuthFailure() {
        return code == Code.AUTH_FAILED;
    }
}
