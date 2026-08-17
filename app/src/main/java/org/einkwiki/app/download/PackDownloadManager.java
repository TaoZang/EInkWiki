package org.einkwiki.app.download;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import org.einkwiki.app.data.OfflinePack;
import org.einkwiki.app.data.OfflinePackStore;

import java.io.IOException;

/** Thin, persistent wrapper around the platform DownloadManager. */
public final class PackDownloadManager {
    private static final String PREFS = "pack_downloads";
    private static final String DOWNLOAD_ID = "download_id";
    private static final long NO_DOWNLOAD = -1L;

    private final Context context;
    private final DownloadManager manager;
    private final SharedPreferences preferences;
    private final OfflinePackStore store;

    public PackDownloadManager(Context context, OfflinePackStore store) {
        this.context = context.getApplicationContext();
        this.manager = (DownloadManager) this.context.getSystemService(Context.DOWNLOAD_SERVICE);
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.store = store;
    }

    public long start(OfflinePack pack) throws IOException {
        if (manager == null) {
            throw new IOException("系统下载服务不可用");
        }
        cancelTrackedDownload();
        store.clearPartial(pack);
        store.requireEnoughSpace(pack);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(pack.downloadUrl))
                .setTitle(pack.title)
                .setDescription("下载维基百科离线包")
                .setMimeType("application/octet-stream")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(
                        context,
                        null,
                        "offline/packs/" + pack.partialFileName()
                );
        long id = manager.enqueue(request);
        if (!preferences.edit().putLong(DOWNLOAD_ID, id).commit()) {
            manager.remove(id);
            throw new IOException("无法保存下载状态");
        }
        return id;
    }

    public long trackedId() {
        return preferences.getLong(DOWNLOAD_ID, NO_DOWNLOAD);
    }

    public DownloadSnapshot query() {
        long id = trackedId();
        if (id == NO_DOWNLOAD || manager == null) {
            return DownloadSnapshot.none();
        }

        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (Cursor cursor = manager.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return DownloadSnapshot.none();
            }
            int rawStatus = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            );
            long downloaded = cursor.getLong(
                    cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                    )
            );
            long total = cursor.getLong(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            );
            int reason = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            );
            return new DownloadSnapshot(mapState(rawStatus), downloaded, total, reason);
        } catch (RuntimeException ignored) {
            // Do not forget a live system download because of a transient provider error.
            return new DownloadSnapshot(DownloadSnapshot.State.PENDING, 0, -1, 0);
        }
    }

    public void cancelTrackedDownload() {
        long id = trackedId();
        if (id != NO_DOWNLOAD && manager != null) {
            manager.remove(id);
        }
        clearTracking();
    }

    public void clearTracking() {
        preferences.edit().remove(DOWNLOAD_ID).apply();
    }

    /** Removes the now-stale DownloadManager row after its file has been atomically renamed. */
    public void removeCompletedRecord() {
        long id = trackedId();
        if (id != NO_DOWNLOAD && manager != null) {
            manager.remove(id);
        }
        clearTracking();
    }

    private static DownloadSnapshot.State mapState(int status) {
        switch (status) {
            case DownloadManager.STATUS_PENDING:
                return DownloadSnapshot.State.PENDING;
            case DownloadManager.STATUS_RUNNING:
                return DownloadSnapshot.State.RUNNING;
            case DownloadManager.STATUS_PAUSED:
                return DownloadSnapshot.State.PAUSED;
            case DownloadManager.STATUS_SUCCESSFUL:
                return DownloadSnapshot.State.SUCCESSFUL;
            case DownloadManager.STATUS_FAILED:
                return DownloadSnapshot.State.FAILED;
            default:
                return DownloadSnapshot.State.NONE;
        }
    }
}
