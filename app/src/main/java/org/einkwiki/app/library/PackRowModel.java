package org.einkwiki.app.library;

import java.util.Objects;

/** Immutable, Android-free presentation state for one offline-pack list row. */
public final class PackRowModel {
    public static final int NO_PROGRESS = -1;

    /** Complete lifecycle states understood by the offline-pack row component. */
    public enum State {
        AVAILABLE(Action.DOWNLOAD, true, Action.NONE, false, false),
        DOWNLOAD_BLOCKED(Action.DOWNLOAD, false, Action.NONE, false, false),
        PREPARING(Action.PREPARING, false, Action.NONE, false, false),
        PENDING(Action.CANCEL, true, Action.NONE, false, true),
        DOWNLOADING(Action.CANCEL, true, Action.NONE, false, true),
        PAUSED(Action.CANCEL, true, Action.NONE, false, true),
        VERIFYING(Action.VERIFYING, false, Action.NONE, false, false),
        DOWNLOAD_FAILED(Action.RETRY, true, Action.NONE, false, false),
        REGISTRY_FAILED(Action.RETRY_REGISTRY, true, Action.NONE, false, false),
        VERIFICATION_FAILED(Action.REDOWNLOAD, true, Action.NONE, false, false),
        INSTALLED(Action.SET_CURRENT, true, Action.DELETE, true, false),
        CURRENT(Action.OPEN_SEARCH, true, Action.DELETE, true, false),
        UPDATE_AVAILABLE(Action.UPDATE, true, Action.DELETE, true, false),
        DELETING(Action.DELETING, false, Action.NONE, false, false);

        private final Action primaryAction;
        private final boolean primaryEnabled;
        private final Action secondaryAction;
        private final boolean secondaryEnabled;
        private final boolean supportsProgress;

        State(
                Action primaryAction,
                boolean primaryEnabled,
                Action secondaryAction,
                boolean secondaryEnabled,
                boolean supportsProgress
        ) {
            this.primaryAction = primaryAction;
            this.primaryEnabled = primaryEnabled;
            this.secondaryAction = secondaryAction;
            this.secondaryEnabled = secondaryEnabled;
            this.supportsProgress = supportsProgress;
        }
    }

    /** Semantic actions returned to the host instead of view-specific button identifiers. */
    public enum Action {
        NONE,
        DOWNLOAD,
        PREPARING,
        CANCEL,
        RETRY,
        RETRY_REGISTRY,
        REDOWNLOAD,
        SET_CURRENT,
        OPEN_SEARCH,
        UPDATE,
        DELETE,
        VERIFYING,
        DELETING
    }

    public final String packKey;
    public final String title;
    public final String metadata;
    public final String badge;
    public final String status;
    public final String detail;
    public final State state;
    public final int progressPercent;

    public PackRowModel(
            String packKey,
            String title,
            String metadata,
            String badge,
            String status,
            String detail,
            State state,
            int progressPercent
    ) {
        this.packKey = requireText(packKey, "packKey");
        this.title = requireText(title, "title");
        this.metadata = normalize(metadata);
        this.badge = normalize(badge);
        this.status = requireText(status, "status");
        this.detail = normalize(detail);
        this.state = Objects.requireNonNull(state, "state");
        if (progressPercent < NO_PROGRESS || progressPercent > 100) {
            throw new IllegalArgumentException("progressPercent must be -1 or between 0 and 100");
        }
        if (progressPercent != NO_PROGRESS && !state.supportsProgress) {
            throw new IllegalArgumentException("state does not support download progress: " + state);
        }
        this.progressPercent = progressPercent;
    }

    public Action primaryAction() {
        return state.primaryAction;
    }

    public boolean isPrimaryEnabled() {
        return state.primaryEnabled;
    }

    public Action secondaryAction() {
        return state.secondaryAction;
    }

    public boolean isSecondaryEnabled() {
        return state.secondaryEnabled;
    }

    public boolean hasProgress() {
        return progressPercent != NO_PROGRESS;
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PackRowModel)) {
            return false;
        }
        PackRowModel row = (PackRowModel) other;
        return progressPercent == row.progressPercent
                && packKey.equals(row.packKey)
                && title.equals(row.title)
                && metadata.equals(row.metadata)
                && badge.equals(row.badge)
                && status.equals(row.status)
                && detail.equals(row.detail)
                && state == row.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                packKey,
                title,
                metadata,
                badge,
                status,
                detail,
                state,
                progressPercent
        );
    }
}
