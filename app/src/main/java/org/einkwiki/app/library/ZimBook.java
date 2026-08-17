package org.einkwiki.app.library;

import java.util.Objects;

/** Immutable metadata for one locally stored ZIM file. */
public final class ZimBook {
    public final String fileName;
    public final String title;
    public final long sizeBytes;
    public final int articleCount;

    public ZimBook(String fileName, String title, long sizeBytes, int articleCount) {
        this.fileName = requireText(fileName, "fileName");
        String normalizedTitle = title == null ? "" : title.trim();
        this.title = normalizedTitle.isEmpty() ? titleFromFileName(fileName) : normalizedTitle;
        if (sizeBytes <= 0L) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
        this.sizeBytes = sizeBytes;
        this.articleCount = Math.max(0, articleCount);
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String titleFromFileName(String fileName) {
        String name = fileName;
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".zim")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.replace('_', ' ');
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ZimBook)) {
            return false;
        }
        ZimBook book = (ZimBook) other;
        return sizeBytes == book.sizeBytes
                && articleCount == book.articleCount
                && fileName.equals(book.fileName)
                && title.equals(book.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, title, sizeBytes, articleCount);
    }
}
