package org.einkwiki.app.reader;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import org.junit.Test;

public final class ReaderPageKeyMapperTest {
    @Test
    public void previousPageKeysAreRecognized() {
        assertEquals(-1, ReaderPageKeyMapper.directionFor(KeyEvent.KEYCODE_PAGE_UP));
        assertEquals(-1, ReaderPageKeyMapper.directionFor(KeyEvent.KEYCODE_VOLUME_UP));
        assertEquals(-1, ReaderPageKeyMapper.directionFor(KeyEvent.KEYCODE_DPAD_LEFT));
        assertEquals(-1, ReaderPageKeyMapper.directionFor(KeyEvent.KEYCODE_NAVIGATE_PREVIOUS));
    }

    @Test
    public void nextPageKeysAreRecognized() {
        assertEquals(1, ReaderPageKeyMapper.directionFor(KeyEvent.KEYCODE_PAGE_DOWN));
        assertEquals(1, ReaderPageKeyMapper.directionFor(KeyEvent.KEYCODE_VOLUME_DOWN));
        assertEquals(1, ReaderPageKeyMapper.directionFor(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(1, ReaderPageKeyMapper.directionFor(KeyEvent.KEYCODE_NAVIGATE_NEXT));
    }

    @Test
    public void unrelatedKeysAreIgnored() {
        assertEquals(0, ReaderPageKeyMapper.directionFor(KeyEvent.KEYCODE_BACK));
        assertEquals(0, ReaderPageKeyMapper.directionFor(KeyEvent.KEYCODE_DPAD_CENTER));
    }
}
