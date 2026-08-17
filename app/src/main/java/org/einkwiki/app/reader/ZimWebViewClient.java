package org.einkwiki.app.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Serves every article resource from ZIM and denies all network navigation. */
public final class ZimWebViewClient extends WebViewClient {
    public interface Listener {
        void onExternalLinkBlocked();

        void onPageStarted();

        void onPageFinished(String title);

        void onMainFrameError();
    }

    private static final String LOCAL_HOST = "zim.local";
    private static final String READER_FONT_PATH = "__einkwiki/NotoSerifSC.ttf";
    private static final Map<String, String> SECURITY_HEADERS;

    static {
        Map<String, String> headers = new HashMap<>();
        headers.put(
                "Content-Security-Policy",
                "default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                        + "font-src 'self' data:; media-src 'none'; script-src 'none'; "
                        + "object-src 'none'; frame-src 'none'; connect-src 'none'"
        );
        headers.put("X-Content-Type-Options", "nosniff");
        SECURITY_HEADERS = Collections.unmodifiableMap(headers);
    }

    private final Context context;
    private final ZimArchive archive;
    private final Listener listener;

    public ZimWebViewClient(Context context, ZimArchive archive, Listener listener) {
        this.context = context.getApplicationContext();
        this.archive = archive;
        this.listener = listener;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(
            WebView view,
            WebResourceRequest request
    ) {
        Uri uri = request.getUrl();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !LOCAL_HOST.equalsIgnoreCase(uri.getHost())) {
            if ("data".equalsIgnoreCase(uri.getScheme())
                    || "about".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            return response(403, "Blocked", "text/plain", "UTF-8", new byte[0]);
        }

        String encodedPath = uri.getEncodedPath();
        if (encodedPath == null) {
            return response(404, "Not Found", "text/plain", "UTF-8", new byte[0]);
        }
        String path = Uri.decode(encodedPath);
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (READER_FONT_PATH.equals(path)) {
            try {
                return response(
                        200,
                        "OK",
                        "font/ttf",
                        null,
                        context.getAssets().open("fonts/NotoSerifSC.ttf")
                );
            } catch (IOException ignored) {
                return response(404, "Not Found", "text/plain", "UTF-8", new byte[0]);
            }
        }
        try {
            ZimResource resource = archive.resource(path);
            String mimeType = normalizedMime(resource.mimeType);
            byte[] bytes = resource.bytes;
            String encoding = isTextMime(mimeType) ? "UTF-8" : null;
            if ("text/html".equals(mimeType)) {
                bytes = HtmlEInkAdapter.adapt(bytes);
            }
            // The product is text-first. Large audio/video payloads are intentionally blocked.
            if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) {
                return response(204, "No Content", "text/plain", "UTF-8", new byte[0]);
            }
            return response(200, "OK", mimeType, encoding, bytes);
        } catch (Exception ignored) {
            return response(404, "Not Found", "text/plain", "UTF-8", new byte[0]);
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        boolean local = "https".equalsIgnoreCase(uri.getScheme())
                && LOCAL_HOST.equalsIgnoreCase(uri.getHost());
        if (!local) {
            listener.onExternalLinkBlocked();
        }
        return !local;
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        listener.onPageStarted();
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        listener.onPageFinished(view.getTitle());
    }

    @Override
    public void onReceivedError(
            WebView view,
            WebResourceRequest request,
            android.webkit.WebResourceError error
    ) {
        if (request.isForMainFrame()) {
            listener.onMainFrameError();
        }
    }

    private static WebResourceResponse response(
            int status,
            String reason,
            String mimeType,
            String encoding,
            byte[] bytes
    ) {
        return response(
                status,
                reason,
                mimeType,
                encoding,
                new ByteArrayInputStream(bytes)
        );
    }

    private static WebResourceResponse response(
            int status,
            String reason,
            String mimeType,
            String encoding,
            InputStream input
    ) {
        return new WebResourceResponse(
                mimeType,
                encoding,
                status,
                reason,
                SECURITY_HEADERS,
                input
        );
    }

    private static String normalizedMime(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "application/octet-stream";
        }
        return raw.split("[ ;]", 2)[0].toLowerCase(Locale.ROOT);
    }

    private static boolean isTextMime(String mimeType) {
        return mimeType.startsWith("text/")
                || "application/javascript".equals(mimeType)
                || "application/json".equals(mimeType)
                || "image/svg+xml".equals(mimeType);
    }
}
