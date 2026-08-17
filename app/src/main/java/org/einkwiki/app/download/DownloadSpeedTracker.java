package org.einkwiki.app.download;

import java.util.Objects;

/** Calculates a stable bytes-per-second value from periodic DownloadManager snapshots. */
public final class DownloadSpeedTracker {
    public static final long UNKNOWN_BYTES_PER_SECOND = -1L;

    private static final long MIN_SAMPLE_INTERVAL_MS = 1_000L;

    private String downloadKey = "";
    private long previousBytes;
    private long previousSampleMs = -1L;
    private long bytesPerSecond = UNKNOWN_BYTES_PER_SECOND;

    public long update(String key, DownloadSnapshot snapshot, long elapsedRealtimeMs) {
        Objects.requireNonNull(snapshot, "snapshot");
        String normalizedKey = key == null ? "" : key.trim();
        if (normalizedKey.isEmpty()
                || snapshot.state != DownloadSnapshot.State.RUNNING
                || elapsedRealtimeMs < 0L) {
            reset();
            return UNKNOWN_BYTES_PER_SECOND;
        }

        long downloadedBytes = Math.max(0L, snapshot.downloadedBytes);
        if (!normalizedKey.equals(downloadKey)
                || previousSampleMs < 0L
                || elapsedRealtimeMs <= previousSampleMs
                || downloadedBytes < previousBytes) {
            beginSample(normalizedKey, downloadedBytes, elapsedRealtimeMs);
            return UNKNOWN_BYTES_PER_SECOND;
        }

        long elapsedMs = elapsedRealtimeMs - previousSampleMs;
        if (elapsedMs < MIN_SAMPLE_INTERVAL_MS) {
            return bytesPerSecond;
        }

        long downloadedDelta = downloadedBytes - previousBytes;
        bytesPerSecond = downloadedDelta == 0L
                ? 0L
                : Math.max(1L, Math.round(downloadedDelta * 1_000d / elapsedMs));
        previousBytes = downloadedBytes;
        previousSampleMs = elapsedRealtimeMs;
        return bytesPerSecond;
    }

    public void reset() {
        downloadKey = "";
        previousBytes = 0L;
        previousSampleMs = -1L;
        bytesPerSecond = UNKNOWN_BYTES_PER_SECOND;
    }

    private void beginSample(String key, long downloadedBytes, long elapsedRealtimeMs) {
        downloadKey = key;
        previousBytes = downloadedBytes;
        previousSampleMs = elapsedRealtimeMs;
        bytesPerSecond = UNKNOWN_BYTES_PER_SECOND;
    }
}
