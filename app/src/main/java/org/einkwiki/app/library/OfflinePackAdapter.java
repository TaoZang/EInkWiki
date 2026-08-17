package org.einkwiki.app.library;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import org.einkwiki.app.EInkProgressView;
import org.einkwiki.app.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Animation-free classic Views adapter for offline-pack rows. */
public final class OfflinePackAdapter extends BaseAdapter {
    /** The host owns all effects; the adapter only reports a row and semantic action. */
    public interface Listener {
        void onPackAction(PackRowModel row, PackRowModel.Action action);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final Listener listener;
    private final List<PackRowModel> rows = new ArrayList<>();

    public OfflinePackAdapter(Context context, Listener listener) {
        this.context = Objects.requireNonNull(context, "context");
        this.inflater = LayoutInflater.from(context);
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** Replaces the already-sorted row snapshot. No animation or implicit reordering is added. */
    public void submitRows(List<PackRowModel> newRows) {
        submitRows(newRows, null);
    }

    /**
     * Rebinds only changed visible rows when ordering is stable. This avoids a full-screen
     * refresh for each download-progress tick on e-ink displays.
     */
    public void submitRows(List<PackRowModel> newRows, ListView listView) {
        Objects.requireNonNull(newRows, "newRows");
        ArrayList<PackRowModel> copy = new ArrayList<>(newRows.size());
        for (PackRowModel row : newRows) {
            copy.add(Objects.requireNonNull(row, "row"));
        }
        if (rows.equals(copy)) {
            return;
        }
        boolean stableOrder = rows.size() == copy.size();
        if (stableOrder) {
            for (int index = 0; index < rows.size(); index++) {
                if (!rows.get(index).packKey.equals(copy.get(index).packKey)) {
                    stableOrder = false;
                    break;
                }
            }
        }
        List<PackRowModel> previous = new ArrayList<>(rows);
        rows.clear();
        rows.addAll(copy);
        if (!stableOrder || listView == null) {
            notifyDataSetChanged();
            return;
        }
        int firstVisible = listView.getFirstVisiblePosition();
        int lastVisible = listView.getLastVisiblePosition();
        int headerCount = listView.getHeaderViewsCount();
        for (int index = 0; index < rows.size(); index++) {
            if (previous.get(index).equals(rows.get(index))) {
                continue;
            }
            int listPosition = headerCount + index;
            if (listPosition < firstVisible || listPosition > lastVisible) {
                continue;
            }
            View rowView = listView.getChildAt(listPosition - firstVisible);
            if (rowView != null && rowView.getTag() instanceof Holder) {
                bind((Holder) rowView.getTag(), rows.get(index));
            }
        }
    }

    public List<PackRowModel> rows() {
        return Collections.unmodifiableList(rows);
    }

    public PackRowModel rowAt(int position) {
        return rows.get(position);
    }

    @Override
    public int getCount() {
        return rows.size();
    }

    @Override
    public PackRowModel getItem(int position) {
        return rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        return stableId(rows.get(position).packKey);
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public boolean areAllItemsEnabled() {
        return true;
    }

    @Override
    public boolean isEnabled(int position) {
        // Enabled rows let D-pad users enter their focusable action buttons. The host does not
        // register a row click, so tapping or pressing a row itself never changes libraries.
        return true;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View rowView = convertView;
        Holder holder;
        if (rowView == null) {
            rowView = inflater.inflate(R.layout.item_offline_pack, parent, false);
            holder = new Holder(rowView);
            rowView.setTag(holder);
        } else {
            holder = (Holder) rowView.getTag();
        }
        bind(holder, rows.get(position));
        return rowView;
    }

    private void bind(Holder holder, PackRowModel row) {
        holder.boundRow = row;
        holder.title.setText(row.title);
        setOptionalText(holder.metadata, row.metadata);
        setOptionalText(holder.badge, row.badge);
        holder.status.setText(row.status);
        setOptionalText(holder.detail, row.detail);

        if (row.hasProgress()) {
            holder.progress.setVisibility(View.VISIBLE);
            holder.progress.setProgress(row.progressPercent);
            holder.progress.setContentDescription(context.getString(
                    R.string.pack_row_progress_percent,
                    row.progressPercent
            ));
        } else {
            holder.progress.setVisibility(View.GONE);
            holder.progress.setContentDescription(null);
        }

        bindButton(
                holder.primaryAction,
                row.primaryAction(),
                row.isPrimaryEnabled(),
                holder
        );
        bindButton(
                holder.secondaryAction,
                row.secondaryAction(),
                row.isSecondaryEnabled(),
                holder
        );
        holder.actions.setVisibility(
                row.primaryAction() == PackRowModel.Action.NONE
                        && row.secondaryAction() == PackRowModel.Action.NONE
                        ? View.GONE
                        : View.VISIBLE
        );
    }

    private void bindButton(
            Button button,
            PackRowModel.Action action,
            boolean enabled,
            Holder holder
    ) {
        if (action == PackRowModel.Action.NONE) {
            button.setVisibility(View.GONE);
            button.setEnabled(false);
            button.setTag(PackRowModel.Action.NONE);
            return;
        }
        button.setVisibility(View.VISIBLE);
        button.setText(actionLabel(action));
        button.setTag(action);
        button.setEnabled(enabled);
        button.setOnClickListener(view -> {
            PackRowModel.Action clicked = (PackRowModel.Action) view.getTag();
            PackRowModel currentRow = holder.boundRow;
            if (view.isEnabled()
                    && currentRow != null
                    && clicked != PackRowModel.Action.NONE
                    && clicked != PackRowModel.Action.PREPARING
                    && clicked != PackRowModel.Action.VERIFYING
                    && clicked != PackRowModel.Action.DELETING) {
                listener.onPackAction(currentRow, clicked);
            }
        });
    }

    private int actionLabel(PackRowModel.Action action) {
        switch (action) {
            case DOWNLOAD:
                return R.string.pack_row_action_download;
            case PREPARING:
                return R.string.pack_row_action_preparing;
            case CANCEL:
                return R.string.pack_row_action_cancel;
            case RETRY:
                return R.string.pack_row_action_retry;
            case RETRY_REGISTRY:
                return R.string.pack_row_action_retry_registry;
            case REDOWNLOAD:
                return R.string.pack_row_action_redownload;
            case SET_CURRENT:
                return R.string.pack_row_action_set_current;
            case OPEN_SEARCH:
                return R.string.pack_row_action_search;
            case UPDATE:
                return R.string.pack_row_action_update;
            case DELETE:
                return R.string.pack_row_action_delete;
            case VERIFYING:
                return R.string.pack_row_action_verifying;
            case DELETING:
                return R.string.pack_row_action_deleting;
            case NONE:
            default:
                throw new IllegalArgumentException("Action has no visible label: " + action);
        }
    }

    private static void setOptionalText(TextView view, String text) {
        if (text.isEmpty()) {
            view.setText(null);
            view.setVisibility(View.GONE);
        } else {
            view.setText(text);
            view.setVisibility(View.VISIBLE);
        }
    }

    /** FNV-1a keeps ids stable across processes, unlike String.hashCode widened to a long. */
    private static long stableId(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static final class Holder {
        final TextView title;
        final TextView metadata;
        final TextView badge;
        final TextView status;
        final TextView detail;
        final EInkProgressView progress;
        final View actions;
        final Button primaryAction;
        final Button secondaryAction;
        PackRowModel boundRow;

        Holder(View row) {
            title = row.findViewById(R.id.pack_row_title);
            metadata = row.findViewById(R.id.pack_row_metadata);
            badge = row.findViewById(R.id.pack_row_badge);
            status = row.findViewById(R.id.pack_row_status);
            detail = row.findViewById(R.id.pack_row_detail);
            progress = row.findViewById(R.id.pack_row_progress);
            actions = row.findViewById(R.id.pack_row_actions);
            primaryAction = row.findViewById(R.id.pack_row_primary_action);
            secondaryAction = row.findViewById(R.id.pack_row_secondary_action);
        }
    }
}
