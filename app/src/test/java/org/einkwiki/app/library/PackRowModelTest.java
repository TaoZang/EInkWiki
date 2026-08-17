package org.einkwiki.app.library;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class PackRowModelTest {
    @Test
    public void availableRowOffersOnlyDownload() {
        PackRowModel row = row(PackRowModel.State.AVAILABLE, PackRowModel.NO_PROGRESS);

        assertEquals(PackRowModel.Action.DOWNLOAD, row.primaryAction());
        assertTrue(row.isPrimaryEnabled());
        assertEquals(PackRowModel.Action.NONE, row.secondaryAction());
        assertFalse(row.isSecondaryEnabled());
        assertFalse(row.hasProgress());
    }

    @Test
    public void blockedDownloadKeepsActionVisibleButDisabled() {
        PackRowModel row = row(PackRowModel.State.DOWNLOAD_BLOCKED, PackRowModel.NO_PROGRESS);

        assertEquals(PackRowModel.Action.DOWNLOAD, row.primaryAction());
        assertFalse(row.isPrimaryEnabled());
    }

    @Test
    public void activeDownloadsOfferCancelAndMayShowProgress() {
        PackRowModel.State[] states = {
                PackRowModel.State.PENDING,
                PackRowModel.State.DOWNLOADING,
                PackRowModel.State.PAUSED
        };

        for (PackRowModel.State state : states) {
            PackRowModel row = row(state, 37);
            assertEquals(state.name(), PackRowModel.Action.CANCEL, row.primaryAction());
            assertTrue(state.name(), row.isPrimaryEnabled());
            assertTrue(state.name(), row.hasProgress());
            assertEquals(state.name(), 37, row.progressPercent);
        }
    }

    @Test
    public void installedRowsExposeExplicitSearchLibraryActions() {
        PackRowModel installed = row(
                PackRowModel.State.INSTALLED,
                PackRowModel.NO_PROGRESS
        );
        PackRowModel current = row(PackRowModel.State.CURRENT, PackRowModel.NO_PROGRESS);

        assertEquals(PackRowModel.Action.SET_CURRENT, installed.primaryAction());
        assertEquals(PackRowModel.Action.DELETE, installed.secondaryAction());
        assertTrue(installed.isSecondaryEnabled());
        assertEquals(PackRowModel.Action.OPEN_SEARCH, current.primaryAction());
        assertEquals(PackRowModel.Action.DELETE, current.secondaryAction());
    }

    @Test
    public void nonInteractiveWorkStatesHaveVisibleDisabledLabels() {
        PackRowModel preparing = row(
                PackRowModel.State.PREPARING,
                PackRowModel.NO_PROGRESS
        );
        PackRowModel verifying = row(
                PackRowModel.State.VERIFYING,
                PackRowModel.NO_PROGRESS
        );
        PackRowModel deleting = row(PackRowModel.State.DELETING, PackRowModel.NO_PROGRESS);

        assertEquals(PackRowModel.Action.PREPARING, preparing.primaryAction());
        assertFalse(preparing.isPrimaryEnabled());
        assertEquals(PackRowModel.Action.VERIFYING, verifying.primaryAction());
        assertFalse(verifying.isPrimaryEnabled());
        assertEquals(PackRowModel.Action.DELETING, deleting.primaryAction());
        assertFalse(deleting.isPrimaryEnabled());
    }

    @Test
    public void updateAndFailureStatesMapToUnambiguousActions() {
        assertEquals(
                PackRowModel.Action.RETRY,
                row(PackRowModel.State.DOWNLOAD_FAILED, PackRowModel.NO_PROGRESS)
                        .primaryAction()
        );
        assertEquals(
                PackRowModel.Action.REDOWNLOAD,
                row(PackRowModel.State.VERIFICATION_FAILED, PackRowModel.NO_PROGRESS)
                        .primaryAction()
        );
        assertEquals(
                PackRowModel.Action.RETRY_REGISTRY,
                row(PackRowModel.State.REGISTRY_FAILED, PackRowModel.NO_PROGRESS)
                        .primaryAction()
        );
        PackRowModel update = row(
                PackRowModel.State.UPDATE_AVAILABLE,
                PackRowModel.NO_PROGRESS
        );
        assertEquals(PackRowModel.Action.UPDATE, update.primaryAction());
        assertEquals(PackRowModel.Action.DELETE, update.secondaryAction());
    }

    @Test
    public void progressIsRejectedForNonDownloadState() {
        try {
            row(PackRowModel.State.CURRENT, 1);
            fail("Expected progress/state validation failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("does not support"));
        }
    }

    @Test
    public void progressMustUseSentinelOrPercentRange() {
        assertInvalidProgress(-2);
        assertInvalidProgress(101);
    }

    @Test
    public void optionalTextIsNormalizedAndValueEqualitySuppressesDuplicateRows() {
        PackRowModel first = new PackRowModel(
                " pack-id ",
                " 中文维基百科 ",
                null,
                " 推荐 ",
                " 尚未下载 ",
                null,
                PackRowModel.State.AVAILABLE,
                PackRowModel.NO_PROGRESS
        );
        PackRowModel same = new PackRowModel(
                "pack-id",
                "中文维基百科",
                "",
                "推荐",
                "尚未下载",
                "",
                PackRowModel.State.AVAILABLE,
                PackRowModel.NO_PROGRESS
        );
        PackRowModel changed = new PackRowModel(
                "pack-id",
                "中文维基百科",
                "",
                "推荐",
                "等待下载",
                "",
                PackRowModel.State.PENDING,
                0
        );

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, changed);
        assertEquals("pack-id", first.packKey);
        assertEquals("", first.metadata);
        assertEquals("推荐", first.badge);
    }

    private static void assertInvalidProgress(int progress) {
        try {
            row(PackRowModel.State.DOWNLOADING, progress);
            fail("Expected invalid progress failure for " + progress);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("progressPercent"));
        }
    }

    private static PackRowModel row(PackRowModel.State state, int progress) {
        return new PackRowModel(
                "wikipedia_zh_all_nopic_2026-06.zim",
                "中文维基百科 · 无图版",
                "2026-06 · 3.2 GiB · Kiwix/OpenZIM",
                "推荐",
                "明确状态",
                "状态详情",
                state,
                progress
        );
    }
}
