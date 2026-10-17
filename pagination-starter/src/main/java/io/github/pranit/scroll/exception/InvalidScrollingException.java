package io.github.pranit.scroll.exception;

/**
 * Exception thrown when a scrolling operation or scroll cursor parameter is invalid.
 */
public class InvalidScrollingException extends IllegalArgumentException {

    public InvalidScrollingException(final String message) {
        super(message);
    }

    public InvalidScrollingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
