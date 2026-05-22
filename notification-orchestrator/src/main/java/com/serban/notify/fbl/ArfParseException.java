package com.serban.notify.fbl;

/**
 * Raised when an ARF (RFC 5965) message cannot be parsed into an
 * {@link ArfReport} — missing feedback-report part, malformed MIME,
 * unreadable structure (Faz 23.8 M7 T4.3.5 FBL).
 *
 * <p>Checked exception: {@code FblService} catches it, increments the
 * {@code parse_error} outcome metric, and skips the message (fail-closed —
 * an unparseable report never causes a suppression).
 */
public class ArfParseException extends Exception {

    public ArfParseException(String message) {
        super(message);
    }

    public ArfParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
