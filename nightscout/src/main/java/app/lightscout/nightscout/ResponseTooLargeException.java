package app.lightscout.nightscout;

import java.io.IOException;

/**
 * Thrown while reading a response body that exceeds the decompressed size cap (C-11, SR-18).
 * Carries no message: nothing from the response may reach a log (SR-07).
 */
final class ResponseTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception without a message. */
    ResponseTooLargeException() {
        super();
    }
}
