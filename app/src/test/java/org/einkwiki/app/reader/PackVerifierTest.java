package org.einkwiki.app.reader;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class PackVerifierTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void computesStreamingSha256() throws Exception {
        File file = temporaryFolder.newFile("sample.bin");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("墨水维基".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(
                "cb3c25f7c6b3e08e2faf3f113ed09775719c94a887783e0f0881d100bb08dbd3",
                PackVerifier.sha256(file)
        );
    }
}
