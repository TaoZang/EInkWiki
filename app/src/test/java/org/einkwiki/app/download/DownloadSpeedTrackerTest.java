package org.einkwiki.app.download;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DownloadSpeedTrackerTest {
    private static final String PACK = "wikipedia_zh_all_nopic_2026-07";

    @Test
    public void firstRunningSnapshotEstablishesBaseline() {
        DownloadSpeedTracker tracker = new DownloadSpeedTracker();

        assertEquals(
                DownloadSpeedTracker.UNKNOWN_BYTES_PER_SECOND,
                tracker.update(PACK, running(4_096L), 10_000L)
        );
        assertEquals(1_024L, tracker.update(PACK, running(7_168L), 13_000L));
    }

    @Test
    public void samplesShorterThanOneSecondKeepPreviousSpeed() {
        DownloadSpeedTracker tracker = new DownloadSpeedTracker();
        tracker.update(PACK, running(0L), 1_000L);
        assertEquals(2_048L, tracker.update(PACK, running(2_048L), 2_000L));

        assertEquals(2_048L, tracker.update(PACK, running(3_072L), 2_500L));
        assertEquals(2_048L, tracker.update(PACK, running(4_096L), 3_000L));
    }

    @Test
    public void stalledDownloadReportsZeroSpeed() {
        DownloadSpeedTracker tracker = new DownloadSpeedTracker();
        tracker.update(PACK, running(8_192L), 1_000L);

        assertEquals(0L, tracker.update(PACK, running(8_192L), 4_000L));
    }

    @Test
    public void pauseAndDifferentPackResetBaseline() {
        DownloadSpeedTracker tracker = new DownloadSpeedTracker();
        tracker.update(PACK, running(0L), 1_000L);
        assertEquals(1_000L, tracker.update(PACK, running(1_000L), 2_000L));

        assertEquals(
                DownloadSpeedTracker.UNKNOWN_BYTES_PER_SECOND,
                tracker.update(PACK, snapshot(DownloadSnapshot.State.PAUSED, 1_000L), 3_000L)
        );
        assertEquals(
                DownloadSpeedTracker.UNKNOWN_BYTES_PER_SECOND,
                tracker.update(PACK, running(2_000L), 4_000L)
        );
        assertEquals(
                DownloadSpeedTracker.UNKNOWN_BYTES_PER_SECOND,
                tracker.update("another-pack", running(3_000L), 5_000L)
        );
    }

    @Test
    public void byteCounterRegressionStartsNewBaseline() {
        DownloadSpeedTracker tracker = new DownloadSpeedTracker();
        tracker.update(PACK, running(10_000L), 1_000L);
        assertEquals(1_000L, tracker.update(PACK, running(11_000L), 2_000L));

        assertEquals(
                DownloadSpeedTracker.UNKNOWN_BYTES_PER_SECOND,
                tracker.update(PACK, running(500L), 3_000L)
        );
        assertEquals(500L, tracker.update(PACK, running(1_000L), 4_000L));
    }

    private static DownloadSnapshot running(long bytes) {
        return snapshot(DownloadSnapshot.State.RUNNING, bytes);
    }

    private static DownloadSnapshot snapshot(DownloadSnapshot.State state, long bytes) {
        return new DownloadSnapshot(state, bytes, 20_000L, 0);
    }
}
