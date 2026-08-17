package org.einkwiki.app.reader;

/** Resource bytes detached from a libzim Blob. */
public final class ZimResource {
    public final String mimeType;
    public final byte[] bytes;

    ZimResource(String mimeType, byte[] bytes) {
        this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        this.bytes = bytes == null ? new byte[0] : bytes;
    }
}
