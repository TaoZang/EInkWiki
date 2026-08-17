package org.einkwiki.app.data;

import static org.junit.Assert.assertEquals;

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
}
