package org.einkwiki.app.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Verifies that an APK is a strictly newer build signed like this installation. */
public final class UpdatePackageVerifier {
    private static final String UPDATE_DIRECTORY_NAME = "updates";

    private final PackageManager packageManager;
    private final String packageName;
    private final File updateDirectory;

    public UpdatePackageVerifier(Context context) {
        Context applicationContext = context.getApplicationContext();
        Context appContext = applicationContext == null ? context : applicationContext;
        packageManager = appContext.getPackageManager();
        packageName = appContext.getPackageName();
        updateDirectory = new File(appContext.getCacheDir(), UPDATE_DIRECTORY_NAME);
    }

    public String currentVersionName() throws UpdateException {
        String versionName = installedPackageInfo(0L).versionName;
        if (versionName == null || versionName.trim().isEmpty()) {
            throw new UpdateException("Installed app has no version name");
        }
        return versionName;
    }

    public VerifiedUpdate verify(File file, UpdateRelease release) throws UpdateException {
        validateReleaseVersion(release);
        File candidate = validateUpdateFile(file, release);
        long signingFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo installed = installedPackageInfo(signingFlags);
        PackageInfo archive = archivePackageInfo(candidate, signingFlags);

        if (!packageName.equals(archive.packageName)) {
            throw new UpdateException("Downloaded APK package name does not match");
        }
        String archiveVersionName = archive.versionName;
        if (archiveVersionName == null) {
            throw new UpdateException("Downloaded APK has no version name");
        }
        if (!archiveVersionName.equals(release.versionName())) {
            throw new UpdateException("Downloaded APK version name does not match the release tag");
        }

        String installedVersionName = installed.versionName;
        if (installedVersionName == null) {
            throw new UpdateException("Installed app has no version name");
        }
        if (!UpdatePolicy.isNewer(release.versionName(), installedVersionName)) {
            throw new UpdateException("Downloaded APK version name is not newer");
        }

        if (longVersionCode(archive) <= longVersionCode(installed)) {
            throw new UpdateException("Downloaded APK version code is not strictly newer");
        }
        if (!currentSignerDigests(installed).equals(currentSignerDigests(archive))) {
            throw new UpdateException("Downloaded APK signing certificates do not match");
        }
        return new VerifiedUpdate(candidate, release);
    }

    private static void validateReleaseVersion(UpdateRelease release) throws UpdateException {
        if (release == null) {
            throw new UpdateException("Release is unavailable");
        }
        String normalizedTag = UpdatePolicy.normalizedVersion(release.tagName());
        if (normalizedTag == null) {
            throw new UpdateException("Release tag is not a semantic version");
        }
        if (!normalizedTag.equals(release.versionName())) {
            throw new UpdateException("Release version does not match its tag");
        }
        if (!release.versionName().equals(UpdatePolicy.normalizedVersion(release.versionName()))) {
            throw new UpdateException("Release version name is not normalized");
        }
    }

    private File validateUpdateFile(File file, UpdateRelease release) throws UpdateException {
        if (file == null) {
            throw new UpdateException("Downloaded APK is unavailable");
        }
        File expectedDirectory;
        File candidate;
        try {
            expectedDirectory = updateDirectory.getCanonicalFile();
            candidate = file.getCanonicalFile();
        } catch (IOException error) {
            throw new UpdateException("Downloaded APK path could not be resolved", error);
        }
        if (!expectedDirectory.equals(candidate.getParentFile())) {
            throw new UpdateException("Downloaded APK is outside the update cache directory");
        }
        if (!candidate.getName().equals(release.apkAsset().name())
                || !candidate.getName().endsWith(".apk")) {
            throw new UpdateException("Downloaded APK file name does not match the release");
        }
        if (!candidate.isFile() || candidate.length() <= 0L) {
            throw new UpdateException("Downloaded APK is missing or empty");
        }
        if (candidate.length() > UpdatePolicy.MAX_APK_BYTES) {
            throw new UpdateException("Downloaded APK exceeds the size limit");
        }
        if (release.apkAsset().sizeBytes() > 0L
                && candidate.length() != release.apkAsset().sizeBytes()) {
            throw new UpdateException("Downloaded APK size does not match the release");
        }
        return candidate;
    }

    private PackageInfo installedPackageInfo(long flags) throws UpdateException {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(flags)
                );
            }
            //noinspection deprecation
            return packageManager.getPackageInfo(packageName, (int) flags);
        } catch (PackageManager.NameNotFoundException error) {
            throw new UpdateException("Installed app package information is unavailable", error);
        } catch (RuntimeException error) {
            throw new UpdateException(
                    "Installed app package information could not be read",
                    error
            );
        }
    }

    private PackageInfo archivePackageInfo(File file, long flags) throws UpdateException {
        try {
            PackageInfo archive;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                archive = packageManager.getPackageArchiveInfo(
                        file.getAbsolutePath(),
                        PackageManager.PackageInfoFlags.of(flags)
                );
            } else {
                //noinspection deprecation
                archive = packageManager.getPackageArchiveInfo(file.getAbsolutePath(), (int) flags);
            }
            if (archive == null) {
                throw new UpdateException("Downloaded file is not a valid APK");
            }
            return archive;
        } catch (UpdateException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new UpdateException("Downloaded APK could not be parsed", error);
        }
    }

    private static Set<String> currentSignerDigests(PackageInfo packageInfo)
            throws UpdateException {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (packageInfo.signingInfo == null) {
                throw new UpdateException("APK signing certificates are unavailable");
            }
            signatures = packageInfo.signingInfo.getApkContentsSigners();
        } else {
            //noinspection deprecation
            signatures = packageInfo.signatures;
        }
        if (signatures == null || signatures.length == 0) {
            throw new UpdateException("APK has no signing certificates");
        }

        Set<String> digests = new HashSet<>();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Signature signature : signatures) {
                digests.add(toHexString(digest.digest(signature.toByteArray())));
                digest.reset();
            }
        } catch (Exception error) {
            throw new UpdateException("APK signing certificates could not be hashed", error);
        }
        return digests;
    }

    @SuppressWarnings("deprecation")
    private static long longVersionCode(PackageInfo packageInfo) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? packageInfo.getLongVersionCode()
                : packageInfo.versionCode;
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
}
