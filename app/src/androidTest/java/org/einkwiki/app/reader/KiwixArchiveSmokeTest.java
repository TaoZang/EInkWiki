package org.einkwiki.app.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/** Runs on an ARM device and catches missing native libraries or ICU packaging errors. */
@RunWith(AndroidJUnit4.class)
public final class KiwixArchiveSmokeTest {
    @Test
    public void testBundledZimCanBeOpened() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        File zim = new File(instrumentation.getTargetContext().getCacheDir(), "small.zim");
        try (InputStream input = instrumentation.getContext().getAssets().open("small.zim");
             FileOutputStream output = new FileOutputStream(zim)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }

        try (KiwixArchive archive = KiwixArchive.open(
                instrumentation.getTargetContext(),
                zim
        )) {
            assertNotNull(archive.title());

            List<SearchResult> results = archive.search("test", 10);
            assertFalse(results.isEmpty());
            assertEquals("main.html", results.get(0).path);

            ZimResource mainPage = archive.resource("main.html");
            assertEquals("text/html", mainPage.mimeType);
            assertTrue(mainPage.bytes.length > 0);
        }
    }
}
