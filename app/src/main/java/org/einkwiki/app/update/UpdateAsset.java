package org.einkwiki.app.update;

import java.util.Objects;

/** Immutable metadata for an APK attached to a GitHub release. */
public final class UpdateAsset {
    private final String name;
    private final String downloadUrl;
    private final long sizeBytes;
    private final String apiSha256;
    private final String checksumUrl;

    public UpdateAsset(
            String name,
            String downloadUrl,
            long sizeBytes,
            String apiSha256,
            String checksumUrl
    ) {
        this.name = Objects.requireNonNull(name, "name");
        this.downloadUrl = Objects.requireNonNull(downloadUrl, "downloadUrl");
        this.sizeBytes = sizeBytes;
        this.apiSha256 = apiSha256;
        this.checksumUrl = checksumUrl;
    }

    public String name() {
        return name;
    }

    public String downloadUrl() {
        return downloadUrl;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public String apiSha256() {
        return apiSha256;
    }

    public String checksumUrl() {
        return checksumUrl;
    }
}
