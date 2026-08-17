package org.einkwiki.app.library;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public final class ZimLibraryModelTest {
    @Test
    public void acceptsUnicodeZimFileNames() throws Exception {
        assertEquals("中文 维基.zim", ZimLibraryStore.validateFileName(" 中文 维基.zim "));
        assertEquals("BOOK.ZIM", ZimLibraryStore.validateFileName("BOOK.ZIM"));
    }

    @Test
    public void rejectsNonZimAndPaths() {
        assertRejected("book.txt");
        assertRejected("../book.zim");
        assertRejected("folder/book.zim");
        assertRejected("folder\\book.zim");
        assertRejected("bad\nbook.zim");
    }

    @Test
    public void titleFallsBackToReadableFileName() {
        ZimBook book = new ZimBook("my_book.zim", "", 123L, -1);
        assertEquals("my book", book.title);
        assertEquals(0, book.articleCount);
    }

    private static void assertRejected(String value) {
        try {
            ZimLibraryStore.validateFileName(value);
            throw new AssertionError("Expected invalid file name: " + value);
        } catch (IOException expected) {
            // Expected.
        }
    }
}
