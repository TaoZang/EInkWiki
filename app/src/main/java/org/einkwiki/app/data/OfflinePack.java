package org.einkwiki.app.data;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable metadata for one exact downloadable OpenZIM archive. */
public final class OfflinePack {
    private static final Pattern LOGICAL_ID = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,127}"
    );
    private static final Pattern ARTIFACT_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,191}"
    );
    private static final Pattern FILE_NAME = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,191}\\.zim"
    );
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

    public static final OfflinePack DEVELOPMENT = new OfflinePack(
            "wikipedia_zh_chemistry_nopic",
            "wikipedia_zh_chemistry_nopic_2026-06",
            "中文维基百科 · 化学 · 无图",
            "在化学维基百科文章的选择",
            "nopic",
            11_874L,
            "2026-06",
            "wikipedia_zh_chemistry_nopic_2026-06.zim",
            "https://lb.download.kiwix.org/zim/wikipedia/"
                    + "wikipedia_zh_chemistry_nopic_2026-06.zim.meta4",
            43_884_544L,
            "https://download.kiwix.org/zim/wikipedia/"
                    + "wikipedia_zh_chemistry_nopic_2026-06.zim",
            43_883_567L,
            "3a25f1e50da3f20d5c63bb54fdb7cfaf0d5af03656d7fc83511bd300bf9dbbbd"
    );

    /** Backwards-compatible alias for {@link #logicalId}. */
    public final String id;
    /** Stable identity for one content scope and flavour across monthly rebuilds. */
    public final String logicalId;
    /** Exact immutable identity for this one dated ZIM artifact. */
    public final String artifactId;
    public final String title;
    public final String description;
    public final String flavour;
    /** -1 when the OPDS entry omitted the article count. */
    public final long articleCount;
    public final String version;
    public final String fileName;
    /** Official Metalink 4 descriptor URL, or an empty string for legacy packs. */
    public final String metalinkUrl;
    /** OPDS size hint. It is not used for integrity checks because it can be rounded. */
    public final long advertisedBytes;
    /** Empty until the Metalink descriptor has been resolved. */
    public final String downloadUrl;
    /** Exact Metalink size, or -1 until download metadata has been resolved. */
    public final long expectedBytes;
    /** Exact Metalink SHA-256, or an empty string while unresolved. */
    public final String sha256;

    /**
     * Compatibility constructor used by the original single-pack implementation.
     * The exact artifact id is derived from the versioned ZIM filename.
     */
    public OfflinePack(
            String id,
            String title,
            String version,
            String fileName,
            String downloadUrl,
            long expectedBytes,
            String sha256
    ) {
        this(
                id,
                artifactIdFromFileName(fileName),
                title,
                "",
                inferFlavour(id),
                -1L,
                version,
                fileName,
                "",
                expectedBytes,
                downloadUrl,
                expectedBytes,
                sha256
        );
    }

    /** Complete constructor used by catalog parsing and persistent descriptor snapshots. */
    public OfflinePack(
            String logicalId,
            String artifactId,
            String title,
            String description,
            String flavour,
            long articleCount,
            String version,
            String fileName,
            String metalinkUrl,
            long advertisedBytes,
            String downloadUrl,
            long expectedBytes,
            String sha256
    ) {
        this.logicalId = requireMatch(logicalId, LOGICAL_ID, "logicalId");
        this.id = this.logicalId;
        this.artifactId = requireMatch(artifactId, ARTIFACT_ID, "artifactId");
        this.title = requireText(title, "title");
        this.description = normalize(description);
        this.flavour = requireMatch(flavour, LOGICAL_ID, "flavour");
        if (articleCount < -1L) {
            throw new IllegalArgumentException("articleCount must be -1 or non-negative");
        }
        this.articleCount = articleCount;
        this.version = requireText(version, "version");
        this.fileName = requireMatch(fileName, FILE_NAME, "fileName");
        if (!this.fileName.equals(this.artifactId + ".zim")) {
            throw new IllegalArgumentException("fileName must match artifactId");
        }
        if (!this.artifactId.startsWith(this.logicalId + "_")) {
            throw new IllegalArgumentException("artifactId must belong to logicalId");
        }
        this.metalinkUrl = normalizeHttpsUrl(metalinkUrl, "metalinkUrl", true);
        if (advertisedBytes < -1L || advertisedBytes == 0L) {
            throw new IllegalArgumentException("advertisedBytes must be -1 or positive");
        }
        this.advertisedBytes = advertisedBytes;

        String normalizedDownloadUrl = normalizeHttpsUrl(downloadUrl, "downloadUrl", true);
        String normalizedSha = normalize(sha256).toLowerCase(Locale.ROOT);
        boolean anyResolvedField = !normalizedDownloadUrl.isEmpty()
                || expectedBytes != -1L
                || !normalizedSha.isEmpty();
        boolean allResolvedFields = !normalizedDownloadUrl.isEmpty()
                && expectedBytes > 0L
                && SHA_256.matcher(normalizedSha).matches();
        if (expectedBytes < -1L || expectedBytes == 0L || anyResolvedField != allResolvedFields) {
            throw new IllegalArgumentException(
                    "downloadUrl, expectedBytes and sha256 must be all resolved or all absent"
            );
        }
        this.downloadUrl = normalizedDownloadUrl;
        this.expectedBytes = expectedBytes;
        this.sha256 = normalizedSha;
    }

    /** Creates a catalog entry whose exact size and digest still require its meta4 document. */
    public static OfflinePack unresolved(
            String logicalId,
            String artifactId,
            String title,
            String description,
            String flavour,
            long articleCount,
            String version,
            String fileName,
            String metalinkUrl,
            long advertisedBytes
    ) {
        return new OfflinePack(
                logicalId,
                artifactId,
                title,
                description,
                flavour,
                articleCount,
                version,
                fileName,
                metalinkUrl,
                advertisedBytes,
                "",
                -1L,
                ""
        );
    }

    /** Returns a new descriptor bound to exact metadata from the official Metalink document. */
    public OfflinePack withDownloadMetadata(
            String resolvedDownloadUrl,
            long resolvedBytes,
            String resolvedSha256
    ) {
        return new OfflinePack(
                logicalId,
                artifactId,
                title,
                description,
                flavour,
                articleCount,
                version,
                fileName,
                metalinkUrl,
                advertisedBytes,
                resolvedDownloadUrl,
                resolvedBytes,
                resolvedSha256
        );
    }

    public String artifactId() {
        return artifactId;
    }

    public boolean hasDownloadMetadata() {
        return expectedBytes > 0L
                && !downloadUrl.isEmpty()
                && SHA_256.matcher(sha256).matches();
    }

    public String partialFileName() {
        return fileName + ".partial";
    }

    public String humanSize() {
        long displayBytes = hasDownloadMetadata() ? expectedBytes : advertisedBytes;
        return formatBytes(displayBytes);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "未知大小";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kib = bytes / 1024d;
        if (kib < 1024d) {
            return String.format(Locale.ROOT, "%.1f KiB", kib);
        }
        double mib = kib / 1024d;
        if (mib < 1024d) {
            return String.format(Locale.ROOT, "%.1f MiB", mib);
        }
        return String.format(Locale.ROOT, "%.2f GiB", mib / 1024d);
    }

    /** Visible for persistence validation and selection recovery. */
    public static boolean isValidArtifactId(String value) {
        return value != null && ARTIFACT_ID.matcher(value).matches();
    }

    private static String artifactIdFromFileName(String fileName) {
        String normalized = normalize(fileName);
        if (!normalized.endsWith(".zim")) {
            throw new IllegalArgumentException("fileName must end with .zim");
        }
        return normalized.substring(0, normalized.length() - 4);
    }

    private static String inferFlavour(String id) {
        String normalized = normalize(id);
        int separator = normalized.lastIndexOf('_');
        return separator < 0 ? "unknown" : normalized.substring(separator + 1);
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String requireMatch(String value, Pattern pattern, String field) {
        String normalized = requireText(value, field);
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " has an invalid format");
        }
        return normalized;
    }

    private static String normalizeHttpsUrl(String value, String field, boolean optional) {
        String normalized = normalize(value);
        if (normalized.isEmpty() && optional) {
            return "";
        }
        try {
            URI uri = new URI(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw new IllegalArgumentException(field + " must be a plain HTTPS URL");
            }
            return uri.toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException(field + " is not a valid URL", error);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OfflinePack)) {
            return false;
        }
        OfflinePack pack = (OfflinePack) other;
        return articleCount == pack.articleCount
                && advertisedBytes == pack.advertisedBytes
                && expectedBytes == pack.expectedBytes
                && logicalId.equals(pack.logicalId)
                && artifactId.equals(pack.artifactId)
                && title.equals(pack.title)
                && description.equals(pack.description)
                && flavour.equals(pack.flavour)
                && version.equals(pack.version)
                && fileName.equals(pack.fileName)
                && metalinkUrl.equals(pack.metalinkUrl)
                && downloadUrl.equals(pack.downloadUrl)
                && sha256.equals(pack.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                logicalId,
                artifactId,
                title,
                description,
                flavour,
                articleCount,
                version,
                fileName,
                metalinkUrl,
                advertisedBytes,
                downloadUrl,
                expectedBytes,
                sha256
        );
    }

    @Override
    public String toString() {
        return "OfflinePack{" + artifactId + '}';
    }
}
