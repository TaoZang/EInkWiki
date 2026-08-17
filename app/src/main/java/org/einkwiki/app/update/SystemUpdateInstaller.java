package org.einkwiki.app.update;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

/** Creates user-visible system intents; it never installs an APK silently. */
public final class SystemUpdateInstaller {
    private static final String UPDATE_DIRECTORY_NAME = "updates";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    private final Context appContext;
    private final PackageManager packageManager;
    private final File updateDirectory;

    public SystemUpdateInstaller(Context context) {
        Context applicationContext = context.getApplicationContext();
        appContext = applicationContext == null ? context : applicationContext;
        packageManager = appContext.getPackageManager();
        updateDirectory = new File(appContext.getCacheDir(), UPDATE_DIRECTORY_NAME);
    }

    public boolean canRequestInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || packageManager.canRequestPackageInstalls();
    }

    public Intent permissionIntent() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + appContext.getPackageName())
            );
        } else {
            intent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
        }
        return intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    public Intent installIntent(File file) throws UpdateException {
        if (!canRequestInstall()) {
            throw new UpdateException("Permission to install unknown apps has not been granted");
        }
        File candidate = validatedUpdateFile(file);
        Uri contentUri;
        try {
            contentUri = FileProvider.getUriForFile(
                    appContext,
                    appContext.getPackageName() + ".update-files",
                    candidate
            );
        } catch (IllegalArgumentException error) {
            throw new UpdateException(
                    "Downloaded APK cannot be shared with the system installer",
                    error
            );
        } catch (SecurityException error) {
            throw new UpdateException("Downloaded APK access was denied", error);
        }

        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            throw new UpdateException("System package installer is unavailable");
        }
        return intent;
    }

    private File validatedUpdateFile(File file) throws UpdateException {
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
        if (!candidate.isFile()
                || candidate.length() <= 0L
                || !candidate.getName().endsWith(".apk")) {
            throw new UpdateException("Downloaded APK is missing or invalid");
        }
        return candidate;
    }
}
