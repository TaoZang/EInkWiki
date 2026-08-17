package org.einkwiki.app.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;

/** Persists the exact installed artifact selected for home-page searches. */
public final class OfflinePackSelectionStore {
    private static final String PREFS = "offline_pack_selection";
    private static final String SELECTED_ARTIFACT_ID = "selected_artifact_id";

    private final SharedPreferences preferences;

    public OfflinePackSelectionStore(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    OfflinePackSelectionStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public String selectedArtifactId() {
        try {
            String selected = preferences.getString(SELECTED_ARTIFACT_ID, "");
            return OfflinePack.isValidArtifactId(selected) ? selected : "";
        } catch (ClassCastException ignored) {
            return "";
        }
    }

    public String getSelectedArtifactId() {
        return selectedArtifactId();
    }

    public void select(OfflinePack pack) throws IOException {
        if (pack == null) {
            throw new IllegalArgumentException("pack must not be null");
        }
        setSelectedArtifactId(pack.artifactId());
    }

    public void setSelectedArtifactId(String artifactId) throws IOException {
        if (!OfflinePack.isValidArtifactId(artifactId)) {
            throw new IllegalArgumentException("artifactId is invalid");
        }
        if (!preferences.edit().putString(SELECTED_ARTIFACT_ID, artifactId).commit()) {
            throw new IOException("无法保存当前搜索离线包");
        }
    }

    public void clear() throws IOException {
        if (!preferences.edit().remove(SELECTED_ARTIFACT_ID).commit()) {
            throw new IOException("无法清除当前搜索离线包");
        }
    }

    public boolean clearIfSelected(OfflinePack pack) throws IOException {
        if (pack == null || !pack.artifactId().equals(selectedArtifactId())) {
            return false;
        }
        clear();
        return true;
    }
}
