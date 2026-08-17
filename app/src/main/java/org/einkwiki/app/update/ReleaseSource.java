package org.einkwiki.app.update;

/** Source of the latest supported application release. */
public interface ReleaseSource {
    UpdateRelease latestRelease() throws UpdateException;
}
