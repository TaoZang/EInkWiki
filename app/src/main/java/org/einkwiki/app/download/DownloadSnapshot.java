package org.einkwiki.app.download;

/** Stable app-facing view of an Android DownloadManager row. */
public final class DownloadSnapshot {
    public enum State {
        NONE,
        PENDING,
        RUNNING,
        PAUSED,
        SUCCESSFUL,
        FAILED
    }

    public final State state;
    public final long downloadedBytes;
    public final long totalBytes;
    public final int reason;

    public DownloadSnapshot(State state, long downloadedBytes, long totalBytes, int reason) {
        this.state = state;
        this.downloadedBytes = downloadedBytes;
        this.totalBytes = totalBytes;
        this.reason = reason;
    }

    public static DownloadSnapshot none() {
        return new DownloadSnapshot(State.NONE, 0, -1, 0);
    }

    public int percent() {
        if (totalBytes <= 0 || downloadedBytes <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, downloadedBytes * 100L / totalBytes));
    }

    public boolean isActive() {
        return state == State.PENDING || state == State.RUNNING || state == State.PAUSED;
    }
}
