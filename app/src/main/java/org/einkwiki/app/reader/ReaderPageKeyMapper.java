package org.einkwiki.app.reader;

import android.view.KeyEvent;

/** Maps common e-reader page, side and navigation keys to page directions. */
public final class ReaderPageKeyMapper {
    private ReaderPageKeyMapper() {
    }

    public static int directionFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                return -1;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
                return 1;
            default:
                return 0;
        }
    }
}
