package org.einkwiki.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OfflinePackTest {
    @Test
    public void formatsDevelopmentPackSize() {
        assertEquals("41.9 MiB", OfflinePack.DEVELOPMENT.humanSize());
    }

    @Test
    public void partialNameNeverLooksActive() {
        assertEquals(
                "wikipedia_zh_chemistry_nopic_2026-06.zim.partial",
                OfflinePack.DEVELOPMENT.partialFileName()
        );
    }

    @Test
    public void legacyConstructorDerivesExactArtifactIdentity() {
        assertEquals(
                "wikipedia_zh_chemistry_nopic",
                OfflinePack.DEVELOPMENT.logicalId
        );
        assertEquals(
                "wikipedia_zh_chemistry_nopic_2026-06",
                OfflinePack.DEVELOPMENT.artifactId()
        );
        assertTrue(OfflinePack.DEVELOPMENT.hasDownloadMetadata());
    }

    @Test
    public void unresolvedCatalogEntryUsesAdvertisedSizeUntilMetalinkResolution() {
        OfflinePack unresolved = OfflinePack.unresolved(
                "wikipedia_zh_all_nopic",
                "wikipedia_zh_all_nopic_2026-07",
                "维基百科",
                "完整正文，不含图片",
                "nopic",
                3_541_817L,
                "2026-07",
                "wikipedia_zh_all_nopic_2026-07.zim",
                "https://lb.download.kiwix.org/zim/wikipedia/"
                        + "wikipedia_zh_all_nopic_2026-07.zim.meta4",
                14_749_857_792L
        );

        assertFalse(unresolved.hasDownloadMetadata());
        assertEquals(-1L, unresolved.expectedBytes);
        assertEquals("13.74 GiB", unresolved.humanSize());

        OfflinePack resolved = unresolved.withDownloadMetadata(
                "https://lb.download.kiwix.org/zim/wikipedia/"
                        + "wikipedia_zh_all_nopic_2026-07.zim",
                14_749_856_321L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        );
        assertTrue(resolved.hasDownloadMetadata());
        assertEquals(unresolved.artifactId(), resolved.artifactId());
        assertEquals(14_749_856_321L, resolved.expectedBytes);
    }
}
