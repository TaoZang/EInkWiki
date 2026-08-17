package org.einkwiki.app.update;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/** Blocking HTTPS transport intended to run only on a user-initiated background thread. */
public final class GitHubReleaseClient implements UpdateClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long MAX_CHECKSUM_BYTES = 4L * 1024L;
    private static final int MAX_REDIRECTS = 3;
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final Set<Integer> REDIRECT_STATUS_CODES = new HashSet<>(Arrays.asList(
            301, 302, 303, 307, 308
    ));

    private volatile boolean cancelled;
    private volatile HttpURLConnection activeConnection;

    @Override
    public UpdateRelease latestRelease() throws UpdateException {
        return wrapFailure("Update check failed", () -> {
            String releasePageUrl = latestReleasePageUrl();
            String tagName = UpdatePolicy.releaseTagFromPageUrl(releasePageUrl);
            if (tagName == null) {
                throw new UpdateException("Latest release redirect is invalid");
            }
            String versionName = UpdatePolicy.normalizedVersion(tagName);
            if (versionName == null) {
                throw new UpdateException("Release tag is not a semantic version");
            }
            String expectedApkName = UpdatePolicy.expectedApkName(tagName);
            if (expectedApkName == null) {
                throw new UpdateException("Release APK name cannot be determined");
            }
            String expectedChecksumName = UpdatePolicy.expectedChecksumName(tagName);
            if (expectedChecksumName == null) {
                throw new UpdateException("Release checksum name cannot be determined");
            }

            String downloadUrl = UpdatePolicy.assetUrl(tagName, expectedApkName);
            String checksumUrl = UpdatePolicy.assetUrl(tagName, expectedChecksumName);
            if (downloadUrl == null || checksumUrl == null) {
                throw new UpdateException("Release asset URLs cannot be determined");
            }
            return new UpdateRelease(
                    tagName,
                    versionName,
                    releasePageUrl,
                    new UpdateAsset(
                            expectedApkName,
                            downloadUrl,
                            UpdatePolicy.UNKNOWN_ASSET_SIZE_BYTES,
                            null,
                            checksumUrl
                    )
            );
        });
    }

    @Override
    public File download(UpdateRelease release, File updateDirectory) throws UpdateException {
        return wrapFailure(
                "Update download failed",
                () -> downloadVerified(release, updateDirectory)
        );
    }

    @Override
    public void cancel() {
        cancelled = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    private File downloadVerified(UpdateRelease release, File updateDirectory) throws Exception {
        ensureActive();
        validateDownloadRequest(release);
        String expectedSha256 = resolveExpectedSha256(release);
        if (!updateDirectory.exists() && !updateDirectory.mkdirs()) {
            throw new UpdateException("Update cache directory could not be created");
        }
        if (!updateDirectory.isDirectory()) {
            throw new UpdateException("Update cache path is not a directory");
        }

        File destination = new File(updateDirectory, release.apkAsset().name());
        File partial;
        try {
            partial = File.createTempFile(
                    release.apkAsset().name() + ".",
                    ".part",
                    updateDirectory
            );
        } catch (Exception error) {
            throw new UpdateException("Partial update file could not be created", error);
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long[] downloadedBytes = {0L};
        long[] responseSizeBytes = {UpdatePolicy.UNKNOWN_ASSET_SIZE_BYTES};
        try {
            withConnection(
                    release.apkAsset().downloadUrl(),
                    true,
                    "application/octet-stream",
                    connection -> {
                        requireSuccess(connection);
                        responseSizeBytes[0] = connection.getContentLengthLong();
                        if (responseSizeBytes[0] > UpdatePolicy.MAX_APK_BYTES) {
                            throw new UpdateException("Release APK exceeds the size limit");
                        }
                        try (InputStream input = connection.getInputStream();
                             OutputStream output = new BufferedOutputStream(
                                     new FileOutputStream(partial))) {
                            byte[] buffer = new byte[BUFFER_SIZE];
                            while (true) {
                                ensureActive();
                                int count = input.read(buffer);
                                if (count == -1) {
                                    break;
                                }
                                downloadedBytes[0] += count;
                                if (downloadedBytes[0] > UpdatePolicy.MAX_APK_BYTES) {
                                    throw new UpdateException("Downloaded APK exceeds the size limit");
                                }
                                digest.update(buffer, 0, count);
                                output.write(buffer, 0, count);
                            }
                        }
                        return null;
                    }
            );
            ensureActive();

            long expectedSizeBytes = release.apkAsset().sizeBytes() > 0L
                    ? release.apkAsset().sizeBytes()
                    : responseSizeBytes[0];
            if (expectedSizeBytes > 0L && downloadedBytes[0] != expectedSizeBytes) {
                throw new UpdateException("Downloaded APK size does not match the release");
            }
            String actualSha256 = toHexString(digest.digest());
            if (!actualSha256.equals(expectedSha256)) {
                throw new UpdateException("Downloaded APK digest does not match");
            }

            ensureActive();
            if (destination.exists() && !destination.delete()) {
                throw new UpdateException("Old verified update could not be removed");
            }
            ensureActive();
            if (!partial.renameTo(destination)) {
                throw new UpdateException("Verified update could not be finalized");
            }
            return destination;
        } catch (Exception error) {
            // A partial file is never retained as an installable candidate.
            //noinspection ResultOfMethodCallIgnored
            partial.delete();
            if (error instanceof UpdateException) {
                throw error;
            }
            throw new UpdateException("Update download failed", error);
        }
    }

    private String resolveExpectedSha256(UpdateRelease release) throws Exception {
        UpdateAsset asset = release.apkAsset();
        if (asset.apiSha256() != null) {
            String normalized = UpdatePolicy.normalizeSha256(asset.apiSha256());
            if (normalized == null) {
                throw new UpdateException("Release API digest is invalid");
            }
            return normalized;
        }
        if (asset.checksumUrl() == null) {
            throw new UpdateException("Release has no SHA-256 digest");
        }
        String checksumText = readUrl(
                asset.checksumUrl(),
                MAX_CHECKSUM_BYTES,
                true,
                "application/octet-stream"
        );
        String checksumSha256 = UpdatePolicy.parseChecksumFile(checksumText, asset.name());
        if (checksumSha256 == null) {
            throw new UpdateException("Release checksum file is invalid");
        }
        return checksumSha256;
    }

    private String latestReleasePageUrl() throws Exception {
        String initialUrl = UpdatePolicy.LATEST_RELEASE_URL;
        ensureActive();
        HttpsURLConnection connection = openHttpsConnection(initialUrl);
        configureConnection(connection, "text/html");
        activeConnection = connection;
        try {
            ensureActive();
            int status = connection.getResponseCode();
            if (!REDIRECT_STATUS_CODES.contains(status)) {
                throw new UpdateException("GitHub latest release did not redirect");
            }
            String location = connection.getHeaderField("Location");
            if (location == null) {
                throw new UpdateException("GitHub latest release redirect is missing");
            }
            String releasePageUrl = new URI(initialUrl).resolve(location).toString();
            if (UpdatePolicy.releaseTagFromPageUrl(releasePageUrl) == null) {
                throw new UpdateException("GitHub latest release redirect is not allowed");
            }
            return releasePageUrl;
        } catch (Exception error) {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new UpdateCancelledException(error);
            }
            throw error;
        } finally {
            if (activeConnection == connection) {
                activeConnection = null;
            }
            connection.disconnect();
        }
    }

    private String readUrl(
            String url,
            long maximumBytes,
            boolean allowAssetRedirects,
            String accept
    ) throws Exception {
        return withConnection(url, allowAssetRedirects, accept, connection -> {
            requireSuccess(connection);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long total = 0L;
                while (true) {
                    ensureActive();
                    int count = input.read(buffer);
                    if (count == -1) {
                        break;
                    }
                    total += count;
                    if (total > maximumBytes) {
                        throw new UpdateException("Server response is too large");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toString("UTF-8");
            }
        });
    }

    private <T> T withConnection(
            String initialUrl,
            boolean allowAssetRedirects,
            String accept,
            ConnectionBlock<T> block
    ) throws Exception {
        String currentUrl = initialUrl;
        int redirectCount = 0;
        while (true) {
            ensureActive();
            HttpsURLConnection connection = openHttpsConnection(currentUrl);
            configureConnection(connection, accept);
            activeConnection = connection;
            try {
                ensureActive();
                int status = connection.getResponseCode();
                if (REDIRECT_STATUS_CODES.contains(status)) {
                    if (!allowAssetRedirects || redirectCount >= MAX_REDIRECTS) {
                        throw new UpdateException("Update redirect is not allowed");
                    }
                    String location = connection.getHeaderField("Location");
                    if (location == null) {
                        throw new UpdateException("Update redirect has no destination");
                    }
                    String redirectedUrl = new URI(currentUrl).resolve(location).toString();
                    if (!UpdatePolicy.isAllowedAssetRedirectUrl(redirectedUrl)) {
                        throw new UpdateException("Update redirect destination is not allowed");
                    }
                    currentUrl = redirectedUrl;
                    redirectCount += 1;
                    continue;
                }
                return block.run(connection);
            } catch (Exception error) {
                if (cancelled || Thread.currentThread().isInterrupted()) {
                    throw new UpdateCancelledException(error);
                }
                throw error;
            } finally {
                if (activeConnection == connection) {
                    activeConnection = null;
                }
                connection.disconnect();
            }
        }
    }

    private static HttpsURLConnection openHttpsConnection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if (!(connection instanceof HttpsURLConnection)) {
            connection.disconnect();
            throw new UpdateException("Only HTTPS update URLs are allowed");
        }
        return (HttpsURLConnection) connection;
    }

    private static void requireSuccess(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            throw new UpdateException("GitHub returned HTTP " + status);
        }
    }

    private static void configureConnection(HttpsURLConnection connection, String accept) {
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "EInkWiki-Android");
        connection.setRequestProperty("Accept", accept);
    }

    private void ensureActive() throws UpdateCancelledException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new UpdateCancelledException();
        }
    }

    private static void validateDownloadRequest(UpdateRelease release) throws UpdateException {
        if (release == null) {
            throw new UpdateException("Release is unavailable");
        }
        UpdateAsset asset = release.apkAsset();
        String expectedName = UpdatePolicy.expectedApkName(release.tagName());
        if (expectedName == null || !expectedName.equals(asset.name())) {
            throw new UpdateException("Release APK name is invalid");
        }
        if (!UpdatePolicy.isAllowedAssetUrl(
                asset.downloadUrl(),
                release.tagName(),
                asset.name())) {
            throw new UpdateException("Release APK URL is not allowed");
        }
        if (asset.sizeBytes() != UpdatePolicy.UNKNOWN_ASSET_SIZE_BYTES
                && (asset.sizeBytes() < 1L || asset.sizeBytes() > UpdatePolicy.MAX_APK_BYTES)) {
            throw new UpdateException("Release APK size is invalid");
        }
        if (asset.checksumUrl() != null) {
            String checksumName = UpdatePolicy.expectedChecksumName(release.tagName());
            if (checksumName == null
                    || !UpdatePolicy.isAllowedAssetUrl(
                    asset.checksumUrl(),
                    release.tagName(),
                    checksumName)) {
                throw new UpdateException("Release checksum URL is not allowed");
            }
        }
    }

    private static String toHexString(byte[] value) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[value.length * 2];
        for (int index = 0; index < value.length; index += 1) {
            int unsigned = value[index] & 0xff;
            result[index * 2] = alphabet[unsigned >>> 4];
            result[index * 2 + 1] = alphabet[unsigned & 0x0f];
        }
        return new String(result);
    }

    private static <T> T wrapFailure(String message, ThrowingSupplier<T> work)
            throws UpdateException {
        try {
            return work.get();
        } catch (UpdateException error) {
            throw error;
        } catch (Exception error) {
            throw new UpdateException(message, error);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private interface ConnectionBlock<T> {
        T run(HttpsURLConnection connection) throws Exception;
    }
}
