package org.einkwiki.app.reader;

/** Search result detached from libzim native object lifetimes. */
public final class SearchResult {
    public final String title;
    public final String path;
    public final String snippet;

    public SearchResult(String title, String path, String snippet) {
        this.title = title == null ? "" : title;
        this.path = path == null ? "" : path;
        this.snippet = snippet == null ? "" : snippet;
    }
}
