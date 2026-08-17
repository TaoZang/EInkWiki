package org.einkwiki.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/** Owns exact, versioned paths inside the app-specific external storage directory. */
public final class OfflinePackStore {
    private static final String PREFS = "offline_pack_store";
    private static final String VERIFIED_SUFFIX = ".verified_sha256";
    private static final long SAFETY_MARGIN_BYTES = 16L * 1024L * 1024L;

    private final Context context;
    private final SharedPreferences preferences;

    /** A verified file exists, but its small local registry entry could not be persisted. */
    public static final class RegistryWriteException extends IOException {
        RegistryWriteException() {
            super("无法保存离线包校验状态");
        }
    }

    public OfflinePackStore(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public File packDirectory() throws IOException {
        File external = context.getExternalFilesDir(null);
        if (external == null) {
            throw new IOException("应用专属外部存储当前不可用");
        }
        File directory = new File(external, "offline/packs");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建离线包目录");
        }
        return directory;
    }

    public File installedFile(OfflinePack pack) throws IOException {
        return new File(packDirectory(), pack.fileName);
    }

    public File partialFile(OfflinePack pack) throws IOException {
        return new File(packDirectory(), pack.partialFileName());
    }

    public boolean isInstalled(OfflinePack pack) {
        try {
            File file = installedFile(pack);
            String verified = preferences.getString(pack.id + VERIFIED_SUFFIX, "");
            return file.isFile()
                    && file.length() == pack.expectedBytes
                    && pack.sha256.equalsIgnoreCase(verified);
        } catch (IOException ignored) {
            return false;
        }
    }

    public boolean hasInstalledCandidate(OfflinePack pack) {
        try {
            File file = installedFile(pack);
            return file.isFile() && file.length() > 0;
        } catch (IOException ignored) {
            return false;
        }
    }

    public long availableBytes() throws IOException {
        return new StatFs(packDirectory().getAbsolutePath()).getAvailableBytes();
    }

    public void requireEnoughSpace(OfflinePack pack) throws IOException {
        long required = pack.expectedBytes + SAFETY_MARGIN_BYTES;
        long available = availableBytes();
        if (available < required) {
            throw new IOException(
                    "存储空间不足：至少需要 " + OfflinePack.formatBytes(required)
                            + "，当前可用 " + OfflinePack.formatBytes(available)
            );
        }
    }

    /** Activates a verified file using a same-directory rename. */
    public File activate(OfflinePack pack) throws IOException {
        File partial = partialFile(pack);
        File installed = installedFile(pack);
        if (!partial.isFile()) {
            throw new IOException("校验文件不存在");
        }
        if (installed.exists()) {
            throw new IOException("目标离线包已经存在");
        }
        if (!partial.renameTo(installed)) {
            throw new IOException("无法激活离线包");
        }
        // A crash here leaves a recoverable installed candidate, never a falsely verified file.
        markVerified(pack);
        return installed;
    }

    public void markVerified(OfflinePack pack) throws IOException {
        if (!preferences.edit()
                .putString(pack.id + VERIFIED_SUFFIX, pack.sha256.toLowerCase(Locale.ROOT))
                .commit()) {
            throw new RegistryWriteException();
        }
    }

    public boolean deleteInstalled(OfflinePack pack) throws IOException {
        File installed = installedFile(pack);
        if (installed.exists() && !installed.delete()) {
            return false;
        }
        preferences.edit().remove(pack.id + VERIFIED_SUFFIX).apply();
        return true;
    }

    public void clearPartial(OfflinePack pack) throws IOException {
        File partial = partialFile(pack);
        if (partial.exists() && !partial.delete()) {
            throw new IOException("无法清理上次未完成的下载");
        }
    }
}
