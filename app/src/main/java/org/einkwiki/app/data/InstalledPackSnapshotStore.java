package org.einkwiki.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keeps immutable descriptors discoverable after a future remote catalog drops old versions. */
public final class InstalledPackSnapshotStore {
    private static final String PREFS = "installed_pack_snapshots";
    private static final String PACK_PREFIX = "pack.";
    private static final String PACK_IDS = "pack_ids";

    private final SharedPreferences preferences;

    public InstalledPackSnapshotStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    InstalledPackSnapshotStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public synchronized void save(OfflinePack pack) throws IOException {
        if (pack == null || !pack.hasDownloadMetadata()) {
            throw new IllegalArgumentException("only resolved installed packs can be saved");
        }
        List<String> ids = storedIds();
        if (!ids.contains(pack.artifactId())) {
            ids.add(pack.artifactId());
        }
        SharedPreferences.Editor editor = preferences.edit();
        OfflinePackPreferenceCodec.write(editor, PACK_PREFIX, pack);
        editor.putString(PACK_IDS, OfflinePackPreferenceCodec.encodeIds(ids));
        if (!editor.commit()) {
            throw new IOException("无法保存已安装离线包描述");
        }
    }

    public synchronized OfflinePack find(String artifactId) {
        if (!storedIds().contains(artifactId)) {
            return null;
        }
        return OfflinePackPreferenceCodec.read(preferences, PACK_PREFIX, artifactId);
    }

    public synchronized List<OfflinePack> loadAll() {
        List<OfflinePack> result = new ArrayList<>();
        for (String artifactId : storedIds()) {
            OfflinePack pack = OfflinePackPreferenceCodec.read(
                    preferences,
                    PACK_PREFIX,
                    artifactId
            );
            if (pack != null && pack.hasDownloadMetadata()) {
                result.add(pack);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized boolean remove(String artifactId) throws IOException {
        List<String> ids = storedIds();
        if (!ids.remove(artifactId)) {
            return false;
        }
        SharedPreferences.Editor editor = preferences.edit();
        OfflinePackPreferenceCodec.remove(editor, PACK_PREFIX, artifactId);
        editor.putString(PACK_IDS, OfflinePackPreferenceCodec.encodeIds(ids));
        if (!editor.commit()) {
            throw new IOException("无法删除已安装离线包描述");
        }
        return true;
    }

    /** Seeds the new registry without touching the verified file or its legacy marker. */
    public synchronized boolean migrateDevelopmentPack(OfflinePackStore legacyStore)
            throws IOException {
        if (find(OfflinePack.DEVELOPMENT.artifactId()) != null
                || !legacyStore.isInstalled(OfflinePack.DEVELOPMENT)) {
            return false;
        }
        save(OfflinePack.DEVELOPMENT);
        return true;
    }

    private List<String> storedIds() {
        try {
            return OfflinePackPreferenceCodec.decodeIds(preferences.getString(PACK_IDS, ""));
        } catch (ClassCastException ignored) {
            return new ArrayList<>();
        }
    }
}
