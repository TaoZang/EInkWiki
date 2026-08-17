package org.einkwiki.app.reader;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.kiwix.libzim.Archive;
import org.kiwix.libzim.Blob;
import org.kiwix.libzim.Entry;
import org.kiwix.libzim.Item;
import org.kiwix.libzim.Query;
import org.kiwix.libzim.Search;
import org.kiwix.libzim.SearchIterator;
import org.kiwix.libzim.Searcher;
import org.kiwix.libzim.SuggestionIterator;
import org.kiwix.libzim.SuggestionItem;
import org.kiwix.libzim.SuggestionSearch;
import org.kiwix.libzim.SuggestionSearcher;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Serialized access to one libzim Archive and its native search objects. */
public final class KiwixArchive implements Closeable {
    public static final String CONTENT_ORIGIN = "https://kiwix.local/";
    private static final String TAG = "KiwixArchive";
    private static final long MAX_IN_MEMORY_RESOURCE_BYTES = 16L * 1024L * 1024L;

    private final Archive archive;
    private final Searcher fullTextSearcher;
    private final SuggestionSearcher suggestionSearcher;
    private boolean closed;

    private KiwixArchive(Context context, File file) throws Exception {
        NativeRuntime.ensureLoaded(context);
        archive = new Archive(file.getCanonicalPath());

        Searcher textCandidate = null;
        if (archive.hasFulltextIndex()) {
            try {
                textCandidate = new Searcher(archive);
            } catch (Exception ignored) {
                textCandidate = null;
            }
        }
        fullTextSearcher = textCandidate;

        SuggestionSearcher titleCandidate;
        try {
            titleCandidate = new SuggestionSearcher(archive);
        } catch (Exception ignored) {
            titleCandidate = null;
        }
        suggestionSearcher = titleCandidate;
    }

    public static KiwixArchive open(Context context, File file) throws Exception {
        if (file == null || !file.isFile()) {
            throw new IOException("离线包不存在");
        }
        return new KiwixArchive(context, file);
    }

    static void validate(Context context, File file) throws Exception {
        NativeRuntime.ensureLoaded(context);
        Archive candidate = null;
        try {
            candidate = new Archive(file.getCanonicalPath());
            if (candidate.getEntryCount() <= 0) {
                throw new IOException("ZIM 中没有可读条目");
            }
            // Reading one item catches truncated archives that expose metadata but no content.
            Entry probe = candidate.hasMainEntry()
                    ? candidate.getMainEntry()
                    : candidate.getEntryByPath(0);
            Item item = probe.getItem(true);
            if (item.getSize() <= 0) {
                throw new IOException("ZIM 主页内容为空");
            }
        } finally {
            if (candidate != null) {
                candidate.dispose();
            }
        }
    }

    public synchronized String title() {
        requireOpen();
        try {
            return archive.getMetadata("Title");
        } catch (Exception ignored) {
            return "离线维基百科";
        }
    }

    public synchronized List<SearchResult> search(String term, int limit) {
        requireOpen();
        int safeLimit = Math.max(1, Math.min(100, limit));
        Map<String, SearchResult> unique = new LinkedHashMap<>();
        boolean searchSucceeded = false;

        if (fullTextSearcher != null) {
            searchSucceeded = collectFullText(term, safeLimit, unique);
        }
        if (suggestionSearcher != null && unique.size() < safeLimit) {
            searchSucceeded = collectTitleSuggestions(term, safeLimit, unique)
                    || searchSucceeded;
        }
        if (!searchSucceeded) {
            throw new IllegalStateException("离线包不含可用搜索索引，或索引读取失败");
        }
        return new ArrayList<>(unique.values());
    }

    private boolean collectFullText(
            String term,
            int limit,
            Map<String, SearchResult> destination
    ) {
        Query query = null;
        Search search = null;
        SearchIterator iterator = null;
        int initialSize = destination.size();
        try {
            query = new Query(term);
            search = fullTextSearcher.search(query);
            iterator = search.getResults(0, limit);
            while (iterator != null && iterator.hasNext() && destination.size() < limit) {
                String path = iterator.getPath();
                String title = iterator.getTitle();
                String snippet = iterator.getSnippet();
                iterator.next();
                if (path != null && !path.isEmpty()) {
                    destination.put(path, new SearchResult(title, path, snippet));
                }
            }
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Full-text search failed", error);
            // Keep any results read before a damaged entry and try the title index below.
            return destination.size() > initialSize;
        } finally {
            if (iterator != null) {
                iterator.dispose();
            }
            if (search != null) {
                search.dispose();
            }
            if (query != null) {
                query.dispose();
            }
        }
    }

    private boolean collectTitleSuggestions(
            String term,
            int limit,
            Map<String, SearchResult> destination
    ) {
        SuggestionSearch search = null;
        SuggestionIterator iterator = null;
        int initialSize = destination.size();
        try {
            search = suggestionSearcher.suggest(term);
            iterator = search.getResults(0, limit);
            while (iterator != null && iterator.hasNext() && destination.size() < limit) {
                SuggestionItem item = iterator.next();
                String path = item.getPath();
                if (path != null && !path.isEmpty() && !destination.containsKey(path)) {
                    String snippet = item.hasSnippet() ? item.getSnippet() : "";
                    destination.put(path, new SearchResult(item.getTitle(), path, snippet));
                }
            }
            return true;
        } catch (Exception error) {
            Log.w(TAG, "Title suggestion search failed", error);
            return destination.size() > initialSize;
        } finally {
            if (iterator != null) {
                iterator.dispose();
            }
            if (search != null) {
                search.dispose();
            }
        }
    }

    public synchronized ZimResource resource(String path) throws Exception {
        requireOpen();
        String normalized = path;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        Item item = archive.getEntryByPath(normalized).getItem(true);
        String mimeType = normalizedMime(item.getMimetype());
        if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) {
            return new ZimResource(mimeType, new byte[0]);
        }
        long size = item.getSize();
        if (size < 0 || size > MAX_IN_MEMORY_RESOURCE_BYTES) {
            throw new IOException("资源过大，已跳过");
        }
        Blob blob = item.getData();
        try {
            return new ZimResource(mimeType, blob.getData());
        } finally {
            blob.dispose();
        }
    }

    private static String normalizedMime(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "application/octet-stream";
        }
        return raw.split("[ ;]", 2)[0].toLowerCase(Locale.ROOT);
    }

    public String contentUrl(String path) {
        String normalized = path == null ? "" : path;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return CONTENT_ORIGIN + Uri.encode(normalized, "/");
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Archive is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (suggestionSearcher != null) {
            suggestionSearcher.dispose();
        }
        if (fullTextSearcher != null) {
            fullTextSearcher.dispose();
        }
        archive.dispose();
    }
}
