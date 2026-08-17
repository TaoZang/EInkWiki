package org.einkwiki.app.data;

import java.util.Locale;

/** Immutable metadata for one downloadable OpenZIM archive. */
public final class OfflinePack {
    public static final OfflinePack DEVELOPMENT = new OfflinePack(
            "wikipedia_zh_chemistry_nopic",
            "中文维基百科 · 化学 · 无图",
            "2026-06",
            "wikipedia_zh_chemistry_nopic_2026-06.zim",
            "https://download.kiwix.org/zim/wikipedia/"
                    + "wikipedia_zh_chemistry_nopic_2026-06.zim",
            43_883_567L,
            "3a25f1e50da3f20d5c63bb54fdb7cfaf0d5af03656d7fc83511bd300bf9dbbbd"
    );

    public final String id;
    public final String title;
    public final String version;
    public final String fileName;
    public final String downloadUrl;
    public final long expectedBytes;
    public final String sha256;

    public OfflinePack(
            String id,
            String title,
            String version,
            String fileName,
            String downloadUrl,
            long expectedBytes,
            String sha256
    ) {
        this.id = id;
        this.title = title;
        this.version = version;
        this.fileName = fileName;
        this.downloadUrl = downloadUrl;
        this.expectedBytes = expectedBytes;
        this.sha256 = sha256;
    }

    public String partialFileName() {
        return fileName + ".partial";
    }

    public String humanSize() {
        return formatBytes(expectedBytes);
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
}
