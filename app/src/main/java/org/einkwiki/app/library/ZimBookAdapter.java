package org.einkwiki.app.library;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.TextView;

import org.einkwiki.app.R;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Static, animation-free rows for locally imported ZIM books. */
public final class ZimBookAdapter extends BaseAdapter {
    public interface Listener {
        void onPrimary(ZimBook book);

        void onDelete(ZimBook book);
    }

    private final LayoutInflater inflater;
    private final Listener listener;
    private final List<ZimBook> books = new ArrayList<>();
    private String selectedFile = "";
    private String busyFile = "";

    public ZimBookAdapter(Context context, Listener listener) {
        this.inflater = LayoutInflater.from(context);
        this.listener = listener;
    }

    public void submit(List<ZimBook> next, String selectedFile, String busyFile) {
        books.clear();
        if (next != null) {
            books.addAll(next);
        }
        this.selectedFile = selectedFile == null ? "" : selectedFile;
        this.busyFile = busyFile == null ? "" : busyFile;
        notifyDataSetChanged();
    }

    public ZimBook itemAt(int position) {
        return books.get(position);
    }

    @Override
    public int getCount() {
        return books.size();
    }

    @Override
    public ZimBook getItem(int position) {
        return books.get(position);
    }

    @Override
    public long getItemId(int position) {
        return books.get(position).fileName.hashCode();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        Holder holder;
        if (row == null) {
            row = inflater.inflate(R.layout.item_zim_book, parent, false);
            holder = new Holder(row);
            row.setTag(holder);
        } else {
            holder = (Holder) row.getTag();
        }
        ZimBook book = books.get(position);
        boolean selected = book.fileName.equals(selectedFile);
        boolean busy = book.fileName.equals(busyFile);
        holder.title.setText(book.title);
        holder.metadata.setText(metadata(book));
        holder.badge.setVisibility(selected ? View.VISIBLE : View.GONE);
        holder.primary.setText(selected
                ? R.string.pack_row_action_search
                : R.string.pack_row_action_set_current);
        holder.primary.setEnabled(!busy);
        holder.delete.setEnabled(!busy);
        holder.primary.setOnClickListener(view -> listener.onPrimary(book));
        holder.delete.setOnClickListener(view -> listener.onDelete(book));
        return row;
    }

    private static String metadata(ZimBook book) {
        String size = ZimLibraryStore.formatBytes(book.sizeBytes);
        if (book.articleCount <= 0) {
            return book.fileName + " · " + size;
        }
        return NumberFormat.getIntegerInstance(Locale.CHINA).format(book.articleCount)
                + " 篇条目 · " + size + "\n" + book.fileName;
    }

    private static final class Holder {
        final TextView title;
        final TextView metadata;
        final TextView badge;
        final Button primary;
        final Button delete;

        Holder(View row) {
            title = row.findViewById(R.id.zim_book_title);
            metadata = row.findViewById(R.id.zim_book_metadata);
            badge = row.findViewById(R.id.zim_book_badge);
            primary = row.findViewById(R.id.zim_book_primary_action);
            delete = row.findViewById(R.id.zim_book_delete_action);
        }
    }
}
