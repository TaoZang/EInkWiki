package org.einkwiki.app.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure validation rules for release versions, URLs, asset names and checksums. */
public final class UpdatePolicy {
    public static final String LATEST_RELEASE_URL =
            "https://github.com/TaoZang/EInkWiki/releases/latest";
    public static final long UNKNOWN_ASSET_SIZE_BYTES = -1L;
    public static final long MAX_APK_BYTES = 128L * 1024L * 1024L;

    private static final String RELEASE_DOWNLOAD_PATH_PREFIX =
            "/TaoZang/EInkWiki/releases/download/";
    private static final String RELEASE_PAGE_PATH_PREFIX =
            "/TaoZang/EInkWiki/releases/tag/";
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    private UpdatePolicy() {
    }

    public static String normalizedVersion(String value) {
        if (value == null) {
            return null;
        }
        Matcher match = VERSION_PATTERN.matcher(value.trim());
        if (!match.matches()) {
            return null;
        }
        try {
            long major = Long.parseLong(match.group(1));
            long minor = Long.parseLong(match.group(2));
            long patch = Long.parseLong(match.group(3));
            return major + "." + minor + "." + patch;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    public static boolean isNewer(String candidate, String current) {
        long[] candidateParts = versionParts(candidate);
        long[] currentParts = versionParts(current);
        if (candidateParts == null || currentParts == null) {
            return false;
        }
        for (int index = 0; index < candidateParts.length; index += 1) {
            if (candidateParts[index] != currentParts[index]) {
                return candidateParts[index] > currentParts[index];
            }
        }
        return false;
    }

    public static String expectedApkName(String tagName) {
        return normalizedVersion(tagName) == null ? null : "EInkWiki-" + tagName + ".apk";
    }

    public static String expectedChecksumName(String tagName) {
        String apkName = expectedApkName(tagName);
        return apkName == null ? null : apkName + ".sha256";
    }

    public static String releaseTagFromPageUrl(String url) {
        URI uri = parseUri(url);
        if (uri == null
                || !"https".equals(uri.getScheme())
                || !"github.com".equals(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            return null;
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null || !rawPath.startsWith(RELEASE_PAGE_PATH_PREFIX)) {
            return null;
        }
        String tagName = rawPath.substring(RELEASE_PAGE_PATH_PREFIX.length());
        if (tagName.isEmpty() || tagName.indexOf('/') >= 0) {
            return null;
        }
        return normalizedVersion(tagName) == null ? null : tagName;
    }

    public static String assetUrl(String tagName, String assetName) {
        if (normalizedVersion(tagName) == null
                || assetName == null
                || assetName.trim().isEmpty()
                || assetName.indexOf('/') >= 0) {
            return null;
        }
        return "https://github.com" + RELEASE_DOWNLOAD_PATH_PREFIX + tagName + "/" + assetName;
    }

    public static String normalizeSha256(String value) {
        if (value == null) {
            return null;
        }
        String digest = value.trim();
        if (digest.startsWith("sha256:")) {
            digest = digest.substring("sha256:".length()).trim();
        }
        if (!SHA256_PATTERN.matcher(digest).matches()) {
            return null;
        }
        return digest.toLowerCase(Locale.ROOT);
    }

    public static String parseChecksumFile(String contents, String expectedApkName) {
        if (contents == null || expectedApkName == null) {
            return null;
        }
        String firstLine = null;
        for (String line : contents.split("\\r?\\n", -1)) {
            if (!line.trim().isEmpty()) {
                firstLine = line.trim();
                break;
            }
        }
        if (firstLine == null) {
            return null;
        }

        String[] parts = firstLine.split("\\s+", 2);
        String digest = normalizeSha256(parts[0]);
        if (digest == null) {
            return null;
        }
        if (parts.length == 2) {
            String fileName = parts[1].trim();
            if (fileName.startsWith("*")) {
                fileName = fileName.substring(1);
            }
            if (!expectedApkName.equals(fileName)) {
                return null;
            }
        }
        return digest;
    }

    public static boolean isAllowedAssetUrl(String url, String tagName, String assetName) {
        URI uri = parseUri(url);
        if (uri == null
                || !"https".equals(uri.getScheme())
                || !"github.com".equals(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1) {
            return false;
        }
        String expectedPath = RELEASE_DOWNLOAD_PATH_PREFIX + tagName + "/" + assetName;
        return uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && expectedPath.equals(uri.getRawPath());
    }

    public static boolean isAllowedAssetRedirectUrl(String url) {
        URI uri = parseUri(url);
        return uri != null
                && "https".equals(uri.getScheme())
                && "release-assets.githubusercontent.com".equals(uri.getHost())
                && uri.getUserInfo() == null
                && uri.getPort() == -1
                && uri.getRawFragment() == null
                && uri.getRawPath() != null
                && !uri.getRawPath().isEmpty();
    }

    private static long[] versionParts(String value) {
        String normalized = normalizedVersion(value);
        if (normalized == null) {
            return null;
        }
        String[] parts = normalized.split("\\.", -1);
        return new long[]{
                Long.parseLong(parts[0]),
                Long.parseLong(parts[1]),
                Long.parseLong(parts[2])
        };
    }

    private static URI parseUri(String value) {
        if (value == null) {
            return null;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException error) {
            return null;
        }
    }
}
