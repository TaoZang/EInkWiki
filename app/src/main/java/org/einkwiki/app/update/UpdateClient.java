package org.einkwiki.app.update;

/** Cancellable release lookup and APK download client. Instances are single-use after cancel(). */
public interface UpdateClient extends ReleaseSource, ApkDownloader {
    void cancel();
}
