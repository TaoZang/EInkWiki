package org.einkwiki.app.update;

import java.util.Objects;

/** Immutable description of a release that can update this application. */
public final class UpdateRelease {
    private final String tagName;
    private final String versionName;
    private final String releasePageUrl;
    private final UpdateAsset apkAsset;

    public UpdateRelease(
            String tagName,
            String versionName,
            String releasePageUrl,
            UpdateAsset apkAsset
    ) {
        this.tagName = Objects.requireNonNull(tagName, "tagName");
        this.versionName = Objects.requireNonNull(versionName, "versionName");
        this.releasePageUrl = Objects.requireNonNull(releasePageUrl, "releasePageUrl");
        this.apkAsset = Objects.requireNonNull(apkAsset, "apkAsset");
    }

    public String tagName() {
        return tagName;
    }

    public String versionName() {
        return versionName;
    }

    public String releasePageUrl() {
        return releasePageUrl;
    }

    public UpdateAsset apkAsset() {
        return apkAsset;
    }
}
