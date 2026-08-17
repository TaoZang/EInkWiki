package org.einkwiki.app.update;

import java.io.File;
import java.util.Objects;

/** A downloaded APK that passed package, version and signing-certificate checks. */
public final class VerifiedUpdate {
    private final File file;
    private final UpdateRelease release;

    public VerifiedUpdate(File file, UpdateRelease release) {
        this.file = Objects.requireNonNull(file, "file");
        this.release = Objects.requireNonNull(release, "release");
    }

    public File file() {
        return file;
    }

    public UpdateRelease release() {
        return release;
    }
}
