package org.einkwiki.app.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public final class HtmlEInkAdapterTest {
    @Test
    public void injectsStyleBeforeHeadEnd() {
        String source = "<html><head><title>测试</title></head><body>正文</body></html>";
        String result = new String(
                HtmlEInkAdapter.adapt(source.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8
        );

        assertTrue(result.contains("id=\"einkwiki-style\""));
        assertTrue(result.indexOf("einkwiki-style") < result.indexOf("</head>"));
        assertTrue(result.contains("正文"));
        assertTrue(result.contains("animation:none!important"));
        assertTrue(result.contains("EInkWiki Serif"));
        assertTrue(result.contains("h1{font-size:1.35em!important"));
    }

    @Test
    public void createsHeadWhenDocumentHasNone() {
        String source = "<p>只有正文</p>";
        String result = new String(
                HtmlEInkAdapter.adapt(source.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8
        );

        assertTrue(result.startsWith("<head>"));
        assertTrue(result.endsWith(source));
        assertEquals(1, occurrences(result, "einkwiki-style"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int position = 0;
        while ((position = text.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }
}
