package org.einkwiki.app.reader;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Injects a final, static stylesheet tailored to monochrome e-ink reading. */
public final class HtmlEInkAdapter {
    private static final String INJECTION = "<meta name=\"viewport\" "
            + "content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\">"
            + "<style id=\"einkwiki-style\">"
            + ":root{color-scheme:light;scroll-behavior:auto!important;}"
            + "html,body{margin:0!important;padding:0!important;background:#fff!important;"
            + "color:#000!important;font-family:serif!important;font-size:18px!important;"
            + "line-height:1.68!important;text-rendering:optimizeLegibility!important;}"
            + "body{padding:18px 16px 40px!important;box-sizing:border-box!important;"
            + "max-width:none!important;}"
            + "*,*::before,*::after{animation:none!important;transition:none!important;"
            + "scroll-behavior:auto!important;text-shadow:none!important;box-shadow:none!important;}"
            + "h1,h2,h3,h4,h5,h6{font-family:sans-serif!important;color:#000!important;"
            + "line-height:1.3!important;break-after:avoid!important;margin-top:1.35em!important;}"
            + "h1{font-size:1.65em!important;border-bottom:2px solid #000!important;}"
            + "h2{font-size:1.4em!important;border-bottom:1px solid #000!important;}"
            + "h3{font-size:1.2em!important;}"
            + "p,li,dd,dt{orphans:3;widows:3;}"
            + "a,a:visited{color:#000!important;text-decoration:underline!important;"
            + "text-decoration-thickness:1px!important;}"
            + "img,svg{max-width:100%!important;height:auto!important;filter:grayscale(1) contrast(1.08)!important;}"
            + "video,audio,canvas{display:none!important;}"
            + "table{border-collapse:collapse!important;display:block!important;max-width:100%!important;"
            + "overflow-x:auto!important;background:#fff!important;}"
            + "th,td{border:1px solid #000!important;padding:.35em .5em!important;"
            + "background:#fff!important;color:#000!important;}"
            + "pre,code{white-space:pre-wrap!important;overflow-wrap:anywhere!important;"
            + "background:#fff!important;color:#000!important;border:1px solid #000!important;}"
            + "blockquote{margin-left:.5em!important;padding-left:.8em!important;"
            + "border-left:3px solid #000!important;color:#000!important;}"
            + ".mw-editsection,.mw-jump-link,.navbox,.vertical-navbox,.sistersitebox,"
            + ".ambox-notice .mbox-image{display:none!important;}"
            + ".infobox,.thumb,.toc,.mw-parser-output{background:#fff!important;color:#000!important;}"
            + "</style>";

    private HtmlEInkAdapter() {
    }

    public static byte[] adapt(byte[] original) {
        String html = new String(original, StandardCharsets.UTF_8);
        String lower = html.toLowerCase(Locale.ROOT);
        int headEnd = lower.indexOf("</head>");
        String adapted;
        if (headEnd >= 0) {
            adapted = html.substring(0, headEnd) + INJECTION + html.substring(headEnd);
        } else {
            adapted = "<head>" + INJECTION + "</head>" + html;
        }
        return adapted.getBytes(StandardCharsets.UTF_8);
    }
}
