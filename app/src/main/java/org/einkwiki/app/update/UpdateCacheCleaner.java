package org.einkwiki.app.update;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/** Best-effort cleanup for update files left by a previous app process. */
public final class UpdateCacheCleaner {
    private static final String UPDATE_DIRECTORY_NAME = "updates";

    private UpdateCacheCleaner() {
    }

    public static void clearAbandoned(Context context) {
        Context applicationContext = context.getApplicationContext();
        Context appContext = applicationContext == null ? context : applicationContext;
        File directory = new File(appContext.getCacheDir(), UPDATE_DIRECTORY_NAME);
        if (!directory.isDirectory()) {
            return;
        }

        File canonicalDirectory;
        try {
            canonicalDirectory = directory.getCanonicalFile();
        } catch (IOException error) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child.getName();
            if (!name.endsWith(".apk") && !name.endsWith(".part")) {
                continue;
            }
            try {
                File absoluteChild = child.getAbsoluteFile();
                File canonicalChild = child.getCanonicalFile();
                if (!canonicalChild.equals(absoluteChild)
                        || !canonicalDirectory.equals(canonicalChild.getParentFile())
                        || !canonicalChild.isFile()) {
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                canonicalChild.delete();
            } catch (IOException | SecurityException ignored) {
                // Cache cleanup must never prevent the reader from starting.
            }
        }
    }
}
