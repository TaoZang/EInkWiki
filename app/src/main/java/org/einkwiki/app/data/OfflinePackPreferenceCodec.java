package org.einkwiki.app.data;

import android.content.SharedPreferences;

/** SharedPreferences codec for immutable pack descriptors. */
public final class OfflinePackPreferenceCodec {
    private static final String LOGICAL_ID = "logical_id";
    private static final String ARTIFACT_ID = "artifact_id";
    private static final String TITLE = "title";
    private static final String DESCRIPTION = "description";
    private static final String FLAVOUR = "flavour";
    private static final String ARTICLE_COUNT = "article_count";
    private static final String VERSION = "version";
    private static final String FILE_NAME = "file_name";
    private static final String METALINK_URL = "metalink_url";
    private static final String ADVERTISED_BYTES = "advertised_bytes";
    private static final String DOWNLOAD_URL = "download_url";
    private static final String EXPECTED_BYTES = "expected_bytes";
    private static final String SHA256 = "sha256";

    private OfflinePackPreferenceCodec() {
    }

    public static void write(
            SharedPreferences.Editor editor,
            String prefix,
            OfflinePack pack
    ) {
        editor.putString(key(prefix, pack.artifactId(), LOGICAL_ID), pack.logicalId)
                .putString(key(prefix, pack.artifactId(), ARTIFACT_ID), pack.artifactId())
                .putString(key(prefix, pack.artifactId(), TITLE), pack.title)
                .putString(key(prefix, pack.artifactId(), DESCRIPTION), pack.description)
                .putString(key(prefix, pack.artifactId(), FLAVOUR), pack.flavour)
                .putLong(key(prefix, pack.artifactId(), ARTICLE_COUNT), pack.articleCount)
                .putString(key(prefix, pack.artifactId(), VERSION), pack.version)
                .putString(key(prefix, pack.artifactId(), FILE_NAME), pack.fileName)
                .putString(key(prefix, pack.artifactId(), METALINK_URL), pack.metalinkUrl)
                .putLong(key(prefix, pack.artifactId(), ADVERTISED_BYTES), pack.advertisedBytes)
                .putString(key(prefix, pack.artifactId(), DOWNLOAD_URL), pack.downloadUrl)
                .putLong(key(prefix, pack.artifactId(), EXPECTED_BYTES), pack.expectedBytes)
                .putString(key(prefix, pack.artifactId(), SHA256), pack.sha256);
    }

    public static OfflinePack read(
            SharedPreferences preferences,
            String prefix,
            String artifactId
    ) {
        if (!OfflinePack.isValidArtifactId(artifactId)) {
            return null;
        }
        try {
            String storedArtifact = preferences.getString(
                    key(prefix, artifactId, ARTIFACT_ID),
                    ""
            );
            if (!artifactId.equals(storedArtifact)) {
                return null;
            }
            return new OfflinePack(
                    preferences.getString(key(prefix, artifactId, LOGICAL_ID), ""),
                    storedArtifact,
                    preferences.getString(key(prefix, artifactId, TITLE), ""),
                    preferences.getString(key(prefix, artifactId, DESCRIPTION), ""),
                    preferences.getString(key(prefix, artifactId, FLAVOUR), ""),
                    preferences.getLong(key(prefix, artifactId, ARTICLE_COUNT), -1L),
                    preferences.getString(key(prefix, artifactId, VERSION), ""),
                    preferences.getString(key(prefix, artifactId, FILE_NAME), ""),
                    preferences.getString(key(prefix, artifactId, METALINK_URL), ""),
                    preferences.getLong(key(prefix, artifactId, ADVERTISED_BYTES), -1L),
                    preferences.getString(key(prefix, artifactId, DOWNLOAD_URL), ""),
                    preferences.getLong(key(prefix, artifactId, EXPECTED_BYTES), -1L),
                    preferences.getString(key(prefix, artifactId, SHA256), "")
            );
        } catch (IllegalArgumentException | ClassCastException ignored) {
            return null;
        }
    }

    public static void remove(
            SharedPreferences.Editor editor,
            String prefix,
            String artifactId
    ) {
        editor.remove(key(prefix, artifactId, LOGICAL_ID))
                .remove(key(prefix, artifactId, ARTIFACT_ID))
                .remove(key(prefix, artifactId, TITLE))
                .remove(key(prefix, artifactId, DESCRIPTION))
                .remove(key(prefix, artifactId, FLAVOUR))
                .remove(key(prefix, artifactId, ARTICLE_COUNT))
                .remove(key(prefix, artifactId, VERSION))
                .remove(key(prefix, artifactId, FILE_NAME))
                .remove(key(prefix, artifactId, METALINK_URL))
                .remove(key(prefix, artifactId, ADVERTISED_BYTES))
                .remove(key(prefix, artifactId, DOWNLOAD_URL))
                .remove(key(prefix, artifactId, EXPECTED_BYTES))
                .remove(key(prefix, artifactId, SHA256));
    }

    static String encodeIds(Iterable<String> artifactIds) {
        StringBuilder result = new StringBuilder();
        for (String artifactId : artifactIds) {
            if (!OfflinePack.isValidArtifactId(artifactId)) {
                throw new IllegalArgumentException("invalid artifactId");
            }
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(artifactId);
        }
        return result.toString();
    }

    static java.util.List<String> decodeIds(String value) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (value == null || value.isEmpty()) {
            return result;
        }
        for (String artifactId : value.split("\\n")) {
            if (OfflinePack.isValidArtifactId(artifactId) && !result.contains(artifactId)) {
                result.add(artifactId);
            }
        }
        return result;
    }

    private static String key(String prefix, String artifactId, String field) {
        return prefix + artifactId + "." + field;
    }
}
