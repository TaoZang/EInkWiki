package org.einkwiki.app.update;

/** Indicates that the caller cancelled an in-flight update operation. */
public final class UpdateCancelledException extends UpdateException {
    public UpdateCancelledException() {
        super("Update request was cancelled");
    }

    public UpdateCancelledException(Throwable cause) {
        super("Update request was cancelled", cause);
    }
}
