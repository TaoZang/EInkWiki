package org.einkwiki.app.update;

import java.io.File;

/** Downloads and hashes an APK into an application-controlled directory. */
public interface ApkDownloader {
    File download(UpdateRelease release, File updateDirectory) throws UpdateException;
}
