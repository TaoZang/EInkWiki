package org.einkwiki.app.reader;

import android.content.Context;
import android.content.res.AssetManager;

import org.kiwix.libkiwix.JNIKiwix;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Loads Kiwix native libraries once and supplies the external ICU data they require. */
final class NativeRuntime {
    private static boolean initialized;
    private static JNIKiwix runtime;

    private NativeRuntime() {
    }

    static synchronized void ensureLoaded(Context context) throws IOException {
        if (initialized) {
            return;
        }
        Context appContext = context.getApplicationContext();
        JNIKiwix candidate = new JNIKiwix(appContext);
        File icuDirectory = copyIcuAssets(appContext);
        candidate.setDataDirectory(icuDirectory.getAbsolutePath());
        runtime = candidate;
        initialized = true;
    }

    private static File copyIcuAssets(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        String[] names = assets.list("icu");
        if (names == null || names.length == 0) {
            throw new IOException("APK 中缺少 ICU 搜索数据");
        }

        File directory = new File(context.getFilesDir(), "icu");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建 ICU 数据目录");
        }

        byte[] buffer = new byte[128 * 1024];
        for (String name : names) {
            File destination = new File(directory, name);
            if (destination.isFile() && destination.length() > 0) {
                continue;
            }
            File temporary = new File(directory, name + ".partial");
            if (temporary.exists() && !temporary.delete()) {
                throw new IOException("无法清理 ICU 临时文件");
            }
            try (InputStream input = assets.open("icu/" + name);
                 OutputStream output = new FileOutputStream(temporary)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("无法安装 ICU 搜索数据");
            }
        }
        return directory;
    }
}
