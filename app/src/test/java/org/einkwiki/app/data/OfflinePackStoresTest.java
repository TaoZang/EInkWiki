package org.einkwiki.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OfflinePackStoresTest {
    @Test
    public void catalogCacheRoundTripsResolvedAndUnresolvedDescriptorsInOrder() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        OfflinePackCatalogCache cache = new OfflinePackCatalogCache(preferences);
        OfflinePack unresolved = unresolvedAllMini();

        cache.save(Arrays.asList(unresolved, OfflinePack.DEVELOPMENT), 123_456L);

        OfflinePackCatalogCache.Snapshot snapshot = cache.loadSnapshot();
        assertEquals(123_456L, snapshot.savedAtEpochMillis);
        assertEquals(Arrays.asList(unresolved, OfflinePack.DEVELOPMENT), snapshot.packs);
        assertFalse(snapshot.isEmpty());

        cache.clear();
        assertTrue(cache.load().isEmpty());
        assertEquals(0L, cache.savedAtEpochMillis());
    }

    @Test
    public void catalogUpsertPreservesNewerCatalogAndResolvedMetadata() throws Exception {
        MemoryPreferences preferences = new MemoryPreferences();
        OfflinePackCatalogCache cache = new OfflinePackCatalogCache(preferences);
        OfflinePack unresolved = unresolvedAllMini();
        OfflinePack resolved = unresolved.withDownloadMetadata(
                "https://mirror.example/wikipedia_zh_all_mini_2026-07b.zim",
                4_828_300_000L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        );
        cache.save(Arrays.asList(unresolved, OfflinePack.DEVELOPMENT), 100L);

        List<OfflinePack> updated = cache.upsert(
                Collections.singletonList(unresolved),
                resolved,
                200L
        );

        assertEquals(Arrays.asList(resolved, OfflinePack.DEVELOPMENT), updated);
        assertEquals(updated, cache.load());
        assertEquals(200L, cache.savedAtEpochMillis());
    }

    @Test
    public void selectionStoresExactArtifactAndOnlyClearsMatchingPack() throws Exception {
        OfflinePackSelectionStore selection = new OfflinePackSelectionStore(
                new MemoryPreferences()
        );

        selection.select(OfflinePack.DEVELOPMENT);
        assertEquals(OfflinePack.DEVELOPMENT.artifactId(), selection.selectedArtifactId());
        assertFalse(selection.clearIfSelected(unresolvedAllMini()));
        assertEquals(OfflinePack.DEVELOPMENT.artifactId(), selection.selectedArtifactId());
        assertTrue(selection.clearIfSelected(OfflinePack.DEVELOPMENT));
        assertEquals("", selection.selectedArtifactId());
    }

    @Test
    public void installedSnapshotsRetainExactResolvedDescriptorUntilExplicitRemoval()
            throws Exception {
        InstalledPackSnapshotStore installed = new InstalledPackSnapshotStore(
                new MemoryPreferences()
        );

        installed.save(OfflinePack.DEVELOPMENT);

        assertEquals(
                OfflinePack.DEVELOPMENT,
                installed.find(OfflinePack.DEVELOPMENT.artifactId())
        );
        assertEquals(
                Collections.singletonList(OfflinePack.DEVELOPMENT),
                installed.loadAll()
        );
        assertNull(installed.find("wikipedia_zh_all_mini_2026-07b"));
        assertTrue(installed.remove(OfflinePack.DEVELOPMENT.artifactId()));
        assertTrue(installed.loadAll().isEmpty());
        assertFalse(installed.remove(OfflinePack.DEVELOPMENT.artifactId()));
    }

    private static OfflinePack unresolvedAllMini() {
        return OfflinePack.unresolved(
                "wikipedia_zh_all_mini",
                "wikipedia_zh_all_mini_2026-07b",
                "维基百科",
                "迷你版维基百科，每个人的百科全书",
                "mini",
                3_505_388L,
                "2026-07b",
                "wikipedia_zh_all_mini_2026-07b.zim",
                "https://lb.download.kiwix.org/zim/wikipedia/"
                        + "wikipedia_zh_all_mini_2026-07b.zim.meta4",
                4_828_333_056L
        );
    }

    /** Minimal in-memory implementation so local JVM tests do not need Android storage. */
    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public synchronized Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public synchronized String getString(String key, String defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (String) value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Set<String> getStringSet(String key, Set<String> defaultValues) {
            Object value = values.get(key);
            return value == null
                    ? defaultValues
                    : new HashSet<>((Set<String>) value);
        }

        @Override
        public synchronized int getInt(String key, int defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Integer) value;
        }

        @Override
        public synchronized long getLong(String key, long defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Long) value;
        }

        @Override
        public synchronized float getFloat(String key, float defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Float) value;
        }

        @Override
        public synchronized boolean getBoolean(String key, boolean defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Boolean) value;
        }

        @Override
        public synchronized boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener
        ) {
            // Not needed by these stores.
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener
        ) {
            // Not needed by these stores.
        }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                pending.put(key, values == null ? null : new HashSet<>(values));
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                pending.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                pending.remove(key);
                removals.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                synchronized (MemoryPreferences.this) {
                    if (clear) {
                        MemoryPreferences.this.values.clear();
                    }
                    for (String key : removals) {
                        MemoryPreferences.this.values.remove(key);
                    }
                    for (Map.Entry<String, Object> entry : pending.entrySet()) {
                        if (entry.getValue() == null) {
                            MemoryPreferences.this.values.remove(entry.getKey());
                        } else {
                            MemoryPreferences.this.values.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
