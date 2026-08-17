package org.einkwiki.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Atomic local fallback for the last successfully parsed official catalog. */
public final class OfflinePackCatalogCache {
    private static final String PREFS = "offline_pack_catalog_cache";
    private static final String PACK_PREFIX = "pack.";
    private static final String PACK_IDS = "pack_ids";
    private static final String SAVED_AT = "saved_at";

    private final SharedPreferences preferences;

    public OfflinePackCatalogCache(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    OfflinePackCatalogCache(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public synchronized void save(List<OfflinePack> packs, long savedAtEpochMillis)
            throws IOException {
        if (packs == null || packs.isEmpty()) {
            throw new IllegalArgumentException("packs must not be empty");
        }
        if (savedAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("savedAtEpochMillis must be positive");
        }
        List<String> ids = new ArrayList<>(packs.size());
        Set<String> unique = new HashSet<>();
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (OfflinePack pack : packs) {
            if (pack == null || !unique.add(pack.artifactId())) {
                throw new IllegalArgumentException("catalog contains a null or duplicate pack");
            }
            ids.add(pack.artifactId());
            OfflinePackPreferenceCodec.write(editor, PACK_PREFIX, pack);
        }
        editor.putString(PACK_IDS, OfflinePackPreferenceCodec.encodeIds(ids));
        editor.putLong(SAVED_AT, savedAtEpochMillis);
        if (!editor.commit()) {
            throw new IOException("无法保存离线包目录缓存");
        }
    }

    public synchronized Snapshot loadSnapshot() {
        List<OfflinePack> packs = loadInternal();
        long savedAt = packs.isEmpty() ? 0L : safeSavedAt();
        return new Snapshot(packs, savedAt);
    }

    public synchronized List<OfflinePack> load() {
        return loadInternal();
    }

    /**
     * Atomically retains the latest cached catalog while replacing one exact artifact descriptor.
     * This prevents an older in-memory catalog snapshot from erasing freshly resolved Meta4 data.
     */
    public synchronized List<OfflinePack> upsert(
            List<OfflinePack> fallbackCatalog,
            OfflinePack replacement,
            long savedAtEpochMillis
    ) throws IOException {
        if (replacement == null) {
            throw new NullPointerException("replacement");
        }
        List<OfflinePack> base = loadInternal();
        if (base.isEmpty()) {
            base = fallbackCatalog == null ? Collections.emptyList() : fallbackCatalog;
        }
        List<OfflinePack> updated = new ArrayList<>(base.size() + 1);
        boolean replaced = false;
        for (OfflinePack pack : base) {
            if (pack.artifactId().equals(replacement.artifactId())) {
                if (!replaced) {
                    updated.add(replacement);
                    replaced = true;
                }
            } else {
                updated.add(pack);
            }
        }
        if (!replaced) {
            updated.add(replacement);
        }
        save(updated, savedAtEpochMillis);
        return Collections.unmodifiableList(updated);
    }

    public synchronized long savedAtEpochMillis() {
        return safeSavedAt();
    }

    public synchronized void clear() throws IOException {
        if (!preferences.edit().clear().commit()) {
            throw new IOException("无法清理离线包目录缓存");
        }
    }

    private List<OfflinePack> loadInternal() {
        List<String> ids;
        try {
            ids = OfflinePackPreferenceCodec.decodeIds(preferences.getString(PACK_IDS, ""));
        } catch (ClassCastException ignored) {
            return Collections.emptyList();
        }
        List<OfflinePack> result = new ArrayList<>(ids.size());
        for (String artifactId : ids) {
            OfflinePack pack = OfflinePackPreferenceCodec.read(
                    preferences,
                    PACK_PREFIX,
                    artifactId
            );
            if (pack != null) {
                result.add(pack);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private long safeSavedAt() {
        try {
            return Math.max(0L, preferences.getLong(SAVED_AT, 0L));
        } catch (ClassCastException ignored) {
            return 0L;
        }
    }

    public static final class Snapshot {
        public final List<OfflinePack> packs;
        public final long savedAtEpochMillis;

        Snapshot(List<OfflinePack> packs, long savedAtEpochMillis) {
            this.packs = packs;
            this.savedAtEpochMillis = savedAtEpochMillis;
        }

        public boolean isEmpty() {
            return packs.isEmpty();
        }
    }
}
