package org.einkwiki.app.reader;

import android.content.Context;

import org.einkwiki.app.data.OfflinePack;
import org.einkwiki.app.data.OfflinePackStore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Size, SHA-256 and libzim structural validation for a completed download. */
public final class PackVerifier {
    private static final Object PACK_OPERATION_LOCK = new Object();

    private PackVerifier() {
    }

    public static void verify(Context context, OfflinePack pack, File file) throws Exception {
        if (!file.isFile()) {
            throw new IOException("下载文件不存在");
        }
        if (file.length() != pack.expectedBytes) {
            throw new IOException(
                    "文件大小不匹配：应为 " + pack.humanSize()
                            + "，实际为 " + OfflinePack.formatBytes(file.length())
            );
        }
        String actual = sha256(file);
        if (!pack.sha256.equalsIgnoreCase(actual)) {
            throw new IOException("SHA-256 校验失败，请重新下载");
        }
        KiwixArchive.validate(context.getApplicationContext(), file);
    }

    /** Serializes verification and activation across Activity instances in this app process. */
    public static File verifyAndActivate(
            Context context,
            OfflinePack pack,
            OfflinePackStore store
    ) throws Exception {
        synchronized (PACK_OPERATION_LOCK) {
            if (store.isInstalled(pack)) {
                return store.installedFile(pack);
            }

            File installed = store.installedFile(pack);
            if (installed.isFile()) {
                verify(context, pack, installed);
                store.markVerified(pack);
                return installed;
            }

            File partial = store.partialFile(pack);
            verify(context, pack, partial);
            return store.activate(pack);
        }
    }

    /** Revalidates a candidate left between the atomic rename and registry commit. */
    public static void verifyInstalled(
            Context context,
            OfflinePack pack,
            OfflinePackStore store
    ) throws Exception {
        synchronized (PACK_OPERATION_LOCK) {
            if (store.isInstalled(pack)) {
                return;
            }
            File installed = store.installedFile(pack);
            verify(context, pack, installed);
            store.markVerified(pack);
        }
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Android 缺少 SHA-256", impossible);
        }

        byte[] buffer = new byte[1024 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }
}
