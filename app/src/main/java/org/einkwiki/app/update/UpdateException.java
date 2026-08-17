package org.einkwiki.app.update;

/** Expected failure while checking, downloading, validating or installing an update. */
public class UpdateException extends Exception {
    public UpdateException(String message) {
        super(message);
    }

    public UpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
