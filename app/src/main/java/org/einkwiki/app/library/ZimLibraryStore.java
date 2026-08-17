package org.einkwiki.app.library;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StatFs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Owns local ZIM files and the selected search library. */
public final class ZimLibraryStore {
    private static final String PREFS = "zim_library";
    private static final String SELECTED_FILE = "selected_file";
    private static final long SAFETY_MARGIN_BYTES = 16L * 1024L * 1024L;

    private final Context context;
    private final SharedPreferences preferences;

    public ZimLibraryStore(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Reuses the historical directory so existing installations keep their downloaded ZIMs. */
    public File directory() throws IOException {
        File external = context.getExternalFilesDir(null);
        if (external == null) {
            throw new IOException("应用专属外部存储当前不可用");
        }
        File directory = new File(external, "offline/packs");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建 ZIM 书库目录");
        }
        return directory;
    }

    public List<File> scan() throws IOException {
        File[] files = directory().listFiles(file -> file.isFile()
                && file.getName().toLowerCase(Locale.ROOT).endsWith(".zim"));
        if (files == null) {
            return new ArrayList<>();
        }
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return new ArrayList<>(Arrays.asList(files));
    }

    public File file(String fileName) throws IOException {
        return safeChild(validateFileName(fileName));
    }

    public File partialFile(String fileName) throws IOException {
        return safeChild(validateFileName(fileName) + ".partial");
    }

    public long resumableBytes(String fileName, long totalBytes) throws IOException {
        requirePositiveSize(totalBytes);
        File installed = file(fileName);
        if (installed.exists()) {
            throw new IOException("同名 ZIM 已经存在");
        }
        File partial = partialFile(fileName);
        long length = partial.isFile() ? partial.length() : 0L;
        if (length > totalBytes) {
            throw new IOException("未完成文件比待导入文件更大，请在 App 中取消后重试");
        }
        long remaining = totalBytes - length;
        long available = new StatFs(directory().getAbsolutePath()).getAvailableBytes();
        if (available < remaining + SAFETY_MARGIN_BYTES) {
            throw new IOException("存储空间不足，还需要约 " + formatBytes(remaining));
        }
        return length;
    }

    public File activate(String fileName, long expectedBytes) throws IOException {
        requirePositiveSize(expectedBytes);
        File partial = partialFile(fileName);
        File installed = file(fileName);
        if (!partial.isFile() || partial.length() != expectedBytes) {
            throw new IOException("接收的文件大小不完整");
        }
        if (installed.exists()) {
            throw new IOException("同名 ZIM 已经存在");
        }
        if (!partial.renameTo(installed)) {
            throw new IOException("无法将文件加入书库");
        }
        return installed;
    }

    public void delete(ZimBook book) throws IOException {
        File target = file(book.fileName);
        if (target.exists() && !target.delete()) {
            throw new IOException("无法删除 ZIM 文件");
        }
        if (book.fileName.equals(selectedFileName())) {
            clearSelection();
        }
    }

    public void deletePartial(String fileName) throws IOException {
        File target = partialFile(fileName);
        if (target.exists() && !target.delete()) {
            throw new IOException("无法删除未完成文件");
        }
    }

    public String selectedFileName() {
        try {
            String selected = preferences.getString(SELECTED_FILE, "");
            return selected == null ? "" : selected;
        } catch (ClassCastException ignored) {
            return "";
        }
    }

    public void select(ZimBook book) throws IOException {
        if (!preferences.edit().putString(SELECTED_FILE, book.fileName).commit()) {
            throw new IOException("无法保存当前搜索库");
        }
    }

    public void clearSelection() throws IOException {
        if (!preferences.edit().remove(SELECTED_FILE).commit()) {
            throw new IOException("无法清除当前搜索库");
        }
    }

    public static String validateFileName(String value) throws IOException {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()
                || name.length() > 200
                || !name.toLowerCase(Locale.ROOT).endsWith(".zim")
                || name.equals(".")
                || name.equals("..")
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0) {
            throw new IOException("请选择扩展名为 .zim 的文件");
        }
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) {
                throw new IOException("ZIM 文件名包含不支持的字符");
            }
        }
        return name;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int index = -1;
        do {
            value /= 1024.0;
            index += 1;
        } while (value >= 1024.0 && index < units.length - 1);
        return String.format(Locale.CHINA, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[index]);
    }

    private File safeChild(String name) throws IOException {
        File parent = directory().getCanonicalFile();
        File child = new File(parent, name).getCanonicalFile();
        if (!parent.equals(child.getParentFile())) {
            throw new IOException("ZIM 文件路径无效");
        }
        return child;
    }

    private static void requirePositiveSize(long value) throws IOException {
        if (value <= 0L) {
            throw new IOException("ZIM 文件大小无效");
        }
    }
}
